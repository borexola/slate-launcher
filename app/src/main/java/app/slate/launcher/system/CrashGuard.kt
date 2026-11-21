package app.slate.launcher.system

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.slate.launcher.data.ConfigStore
import java.io.File

/**
 * Stops a home screen that keeps dying from doing so forever.
 *
 * Not every death is a crash this code could catch: running out of memory inside an image
 * decoder, or being killed by the system, ends the process without warning. So a death is
 * recognised by what did NOT happen. While the home screen is on screen a marker file exists;
 * it is removed when the screen leaves in the normal way. A marker still there at the next
 * start means the process died with the home screen showing.
 *
 * After [THRESHOLD] such deaths within [WINDOW_MS], the next start is in safe mode: saved
 * settings are set aside, and no artwork or wallpaper is loaded, until the user leaves safe mode.
 *
 * Details that keep this honest:
 *  - Everything is recorded in file NAMES, never in file contents, and the files are empty. A
 *    full disk can stop data being written to a file, but an empty file can usually still be
 *    created.
 *  - For file systems where even that fails, a small pool of empty spare files is kept from a
 *    time when there was room. Recording something then means renaming a spare, which needs no
 *    new space, and a record that is no longer needed is renamed back into the pool rather than
 *    deleted. The pool therefore does not run down through ordinary use.
 *  - The marker's name holds the boot it was written in. One left by an earlier boot (a power
 *    cut, a reboot) is ignored: the system going down is not the launcher dying.
 *  - It also holds which install wrote it. Being replaced by an update is not a death either.
 *  - Deaths are only counted together if they happened in the same boot, by time since boot,
 *    so a changing wall clock cannot confuse the count.
 */
object CrashGuard {

    private const val THRESHOLD = 3
    private const val WINDOW_MS = 5 * 60_000L
    private const val EARLY_MS = 20_000L
    private const val ON_SCREEN_PREFIX = "on-screen-"
    private const val DEATH_PREFIX = "died-"
    private const val MARKER_FILE = "safe-mode"
    private const val SPARE_PREFIX = "spare-"

    /** Enough for the marker, the deaths up to the threshold, and the safe-mode marker, twice over. */
    private const val SPARES = 10

    private var processStartedAt = 0L

    /**
     * When the launcher last died, as far as this start could tell: in milliseconds since boot,
     * or -1. Kept in memory because the files that record deaths are cleared on entering safe
     * mode, and on a full disk may not have been written at all.
     */
    @Volatile
    private var lastDeathAt = -1L
    private var bootCount = 0
    private var installStamp = ""

    @Volatile
    private var uiStarted = false

    /** Kept in memory as well, so a crash is still counted if the marker could not be written. */
    @Volatile
    private var showing = false
    private lateinit var dir: File

    /** `on-screen-<install stamp>-<boot count>` */
    private val screenMarker get() = File(dir, "$ON_SCREEN_PREFIX$installStamp-$bootCount")
    private val marker get() = File(dir, MARKER_FILE)
    private fun deathFile() = File(dir, "$DEATH_PREFIX$bootCount-${SystemClock.elapsedRealtime()}")

    /** Call first thing in Application.onCreate. Returns whether safe mode is active. */
    fun install(context: Context): Boolean {
        processStartedAt = SystemClock.elapsedRealtime()
        dir = context.filesDir
        bootCount = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(0)
        installStamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")

        // A marker from this install and this boot: the previous process ended with the home
        // screen showing. It becomes the record of that death. Markers from another install or
        // another boot mean nothing and go back into the pool.
        val mine = screenMarker
        var lastDeath = -1L
        if (mine.exists()) {
            // The start that finds the marker is the one that follows the death.
            lastDeath = processStartedAt
            if (!runCatching { mine.renameTo(deathFile()) }.getOrDefault(false)) recordDeath()
        }
        filesNamed(ON_SCREEN_PREFIX).forEach(::release)
        // Deaths recorded by the crash handler rather than by a marker count as well.
        val newestRecorded = filesNamed(DEATH_PREFIX)
            .mapNotNull(::parseDeath)
            .filter { it.first == bootCount && it.second <= processStartedAt }
            .maxOfOrNull { it.second } ?: -1L
        lastDeathAt = maxOf(lastDeath, newestRecorded)

        var enteredNow = false
        if (recentDeaths() >= THRESHOLD) {
            mark(marker)
            runCatching { ConfigStore.quarantine(context) }
            clearDeaths()
            enteredNow = true
        }
        // Top the pool up, now that there may be room again.
        runCatching { for (i in 1..SPARES) File(dir, "$SPARE_PREFIX$i").createNewFile() }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (showing) {
                    // Recorded here, so the marker must not count it a second time.
                    showing = false
                    if (!runCatching { screenMarker.renameTo(deathFile()) }.getOrDefault(false)) recordDeath()
                } else if (!uiStarted && SystemClock.elapsedRealtime() - processStartedAt < EARLY_MS) {
                    // Died before the home screen was even created.
                    recordDeath()
                }
            } catch (ignored: Throwable) {
                // Recording the crash must never mask it.
            }
            previous?.uncaughtException(thread, error)
        }
        // Also true when the marker could not be written at all: settings were set aside just
        // now, and this session at least must say so and offer them back.
        return enteredNow || marker.exists()
    }

    /**
     * Call when the home screen starts being created (before anything that could fail) and
     * whenever it comes on screen.
     */
    fun onScreen() {
        uiStarted = true
        showing = true
        mark(screenMarker)
    }

    /**
     * Call when the home screen leaves the screen. Whatever happens to the process after that
     * (the system reclaiming a background app, say) is not a death worth counting.
     */
    fun offScreen() {
        showing = false
        release(screenMarker)
    }

    fun leaveSafeMode() {
        release(marker)
        clearDeaths()
    }

    /** Whether the launcher died within the last minute, as far as this start could tell. */
    fun diedJustNow(): Boolean {
        val last = lastDeathAt
        return last >= 0 && SystemClock.elapsedRealtime() - last in 0..60_000L
    }

    /** One empty file per death: `died-<boot count>-<milliseconds since boot>`. */
    private fun recordDeath() {
        mark(deathFile())
    }

    /** Makes an empty file called [target], out of a spare if a new one cannot be created. */
    private fun mark(target: File) {
        val made = runCatching { target.createNewFile() || target.exists() }.getOrDefault(false)
        if (!made) runCatching { filesNamed(SPARE_PREFIX).firstOrNull()?.renameTo(target) }
    }

    /** Puts a record that is no longer needed back into the pool of spares, or deletes it. */
    private fun release(file: File) {
        runCatching {
            if (!file.exists()) return
            val free = (1..SPARES).map { File(dir, "$SPARE_PREFIX$it") }.firstOrNull { !it.exists() }
            if (free == null || !file.renameTo(free)) file.delete()
        }
    }

    private fun filesNamed(prefix: String): List<File> =
        runCatching { dir.listFiles { file -> file.name.startsWith(prefix) }?.toList() }.getOrNull().orEmpty()

    private fun parseDeath(file: File): Pair<Int, Long>? {
        val parts = file.name.removePrefix(DEATH_PREFIX).split('-')
        val boot = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return boot to at
    }

    /** Counts deaths since this boot that are recent enough, and forgets all others. */
    private fun recentDeaths(): Int {
        val now = SystemClock.elapsedRealtime()
        var recent = 0
        for (file in filesNamed(DEATH_PREFIX)) {
            val death = parseDeath(file)
            if (death != null && death.first == bootCount && now - death.second in 0..WINDOW_MS) {
                recent++
            } else {
                release(file)
            }
        }
        return recent
    }

    private fun clearDeaths() {
        filesNamed(DEATH_PREFIX).forEach(::release)
    }
}
