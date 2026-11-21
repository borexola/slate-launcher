package app.slate.launcher.data

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Holds the live configuration and keeps it on disk as a single JSON file.
 *
 * Every value that enters [config] has been through [sanitized], whether it came from disk, an
 * imported file or the settings panel.
 */
class ConfigStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val file = File(context.filesDir, FILE)
    private val backup = File(context.filesDir, BACKUP)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    private val _writeFailed = MutableStateFlow(false)

    /** True while the last attempt to save failed, typically because storage is full. */
    val writeFailed: StateFlow<Boolean> = _writeFailed.asStateFlow()

    private val _config = MutableStateFlow(loadInitial())
    val config: StateFlow<LauncherConfig> = _config.asStateFlow()

    init {
        // Changes arrive in bursts (a held remote button steps a value many times a second), so
        // wait for a pause and write once.
        scope.launch {
            for (signal in dirty) {
                delay(WRITE_DELAY_MS)
                val saved = writeLock.withLock { runCatching { write(file, _config.value, keepBackup = true) }.isSuccess }
                _writeFailed.value = !saved
                if (!saved) {
                    // Keep trying: the change is still held in memory and must not be lost quietly.
                    delay(RETRY_DELAY_MS)
                    dirty.trySend(Unit)
                }
            }
        }
    }

    /** Readable and writable over ADB without any storage permission. */
    val exportFile: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, EXPORT)

    /** Call off the main thread. Whether safe mode set settings aside that could be brought back. */
    fun hasSetAside(): Boolean = setAsideCopies(context.filesDir).isNotEmpty()

    fun update(transform: (LauncherConfig) -> LauncherConfig) {
        _config.update { transform(it).sanitized() }
        dirty.trySend(Unit)
    }

    fun reset() {
        _config.value = LauncherConfig()
        dirty.trySend(Unit)
    }

    /** Call off the main thread. */
    fun exportToFile(): Boolean = runCatching { write(exportFile, _config.value, keepBackup = false) }.isSuccess

    /** Call off the main thread. Returns null on success, otherwise what went wrong. */
    fun importFromFile(): String? =
        import(exportFile, Limits.MAX_IMPORT_BYTES, "No settings file found at ${exportFile.path}")

    /**
     * Call off the main thread. Brings back the settings safe mode set aside most recently.
     * Earlier copies, if there are any, stay available for another go. Returns null on success,
     * otherwise what went wrong.
     */
    suspend fun restoreQuarantined(): String? {
        var failure = "There are no earlier settings to restore"
        for (candidate in setAsideCopies(context.filesDir)) {
            val restored = when (val read = read(candidate, Limits.MAX_SAVED_BYTES)) {
                is ReadResult.Ok -> read.config
                is ReadResult.Failed -> {
                    // A copy that is not usable settings, or too large ever to be read, would
                    // stand in the way of the ones behind it for good. It goes where unreadable
                    // settings go, and the next one is tried. One that merely could not be read
                    // this time stays where it is, to be tried again.
                    val hopeless = read.invalid || runCatching { candidate.length() > Limits.MAX_SAVED_BYTES }.getOrDefault(false)
                    if (!hopeless) return read.reason
                    failure = read.reason
                    runCatching { candidate.renameTo(File(context.filesDir, CORRUPT)) }
                    continue
                }
            }
            // The file itself goes into place before the settings take effect. If they are what
            // was wrong and the launcher dies as soon as it draws them, they are on disk to be
            // counted and set aside again, not held only in memory and lost.
            val moved = writeLock.withLock {
                // A rename replaces the old file in one step, so nothing is deleted beforehand:
                // if it fails, the settings in use are still on disk.
                val ok = candidate.renameTo(file)
                if (ok) {
                    backup.delete()
                    // Inside the lock, so the background writer cannot save the old settings in between.
                    _config.value = restored
                }
                ok
            }
            // Either way, make sure what is in use now gets written.
            dirty.trySend(Unit)
            return if (moved) null else "The earlier settings could not be put back"
        }
        return failure
    }

    private fun import(source: File, limit: Long, missing: String): String? {
        if (!source.isFile) return missing
        return when (val result = read(source, limit)) {
            is ReadResult.Ok -> {
                _config.value = result.config
                dirty.trySend(Unit)
                null
            }
            is ReadResult.Failed -> result.reason
        }
    }

    private fun loadInitial(): LauncherConfig {
        if (file.isFile) {
            when (val result = read(file, Limits.MAX_SAVED_BYTES)) {
                is ReadResult.Ok -> return result.config
                // Keep the unreadable file for inspection instead of silently overwriting it.
                is ReadResult.Failed -> runCatching { file.renameTo(File(context.filesDir, CORRUPT)) }
            }
        }
        if (backup.isFile) {
            (read(backup, Limits.MAX_SAVED_BYTES) as? ReadResult.Ok)?.let { return it.config }
        }
        return LauncherConfig()
    }

    private sealed interface ReadResult {
        class Ok(val config: LauncherConfig) : ReadResult
        /** [invalid]: the file was read and is not usable settings, so trying again cannot help. */
        class Failed(val reason: String, val invalid: Boolean = false) : ReadResult
    }

    private fun read(source: File, limit: Long): ReadResult = try {
        // Read once, with a hard cap, and parse that snapshot.
        val bytes = Untrusted.read(source, limit)
        if (bytes == null) {
            ReadResult.Failed("The settings file is too large or could not be read")
        } else {
            // Some editors put an invisible marker at the start of a text file; it is not JSON.
            val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            ReadResult.Ok(json.decodeFromString(LauncherConfig.serializer(), text).sanitized())
        }
    } catch (e: Throwable) {
        // Throwable on purpose: a hostile file must not take the launcher down, whatever it throws.
        val detail = e.message?.lineSequence()?.firstOrNull()?.take(140).orEmpty()
        // Running out of memory says nothing about the file; that may go better another time.
        ReadResult.Failed("The settings file is not valid. $detail".trim(), invalid = e !is OutOfMemoryError)
    }

    /** Writes via a synced temp file and a rename, so a power cut leaves the old or the new file. */
    private fun write(target: File, value: LauncherConfig, keepBackup: Boolean) {
        val dir = target.parentFile ?: return
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(LauncherConfig.serializer(), value).toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (keepBackup && target.isFile) {
                backup.delete()
                target.renameTo(backup)
            }
            if (!tmp.renameTo(target)) error("Could not replace ${target.name}")
            // The file's contents are synced above; this makes its new name survive a power cut too.
            runCatching {
                val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    Os.close(fd)
                }
            }
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val FILE = "config.json"
        private const val BACKUP = "config.json.bak"
        private const val CORRUPT = "config.corrupt.json"
        private const val SET_ASIDE_PREFIX = "config.crashed."
        private const val SET_ASIDE_SUFFIX = ".json"
        private const val MAX_SET_ASIDE = 6
        private const val MAX_SET_ASIDE_NUMBER = 999_999_999
        private const val EXPORT = "slate-config.json"
        private const val WRITE_DELAY_MS = 300L
        private const val RETRY_DELAY_MS = 15_000L

        /**
         * The settings safe mode has set aside, newest first. Each copy is a file of its own,
         * `config.crashed.<number>.json`, a higher number being a later one.
         */
        private fun setAsideCopies(dir: File): List<File> =
            runCatching { dir.listFiles() }.getOrNull().orEmpty()
                .mapNotNull { file -> setAsideNumber(file)?.let { it to file } }
                .sortedByDescending { it.first }
                .map { it.second }

        private fun setAsideNumber(file: File): Int? {
            val name = file.name
            if (!name.startsWith(SET_ASIDE_PREFIX) || !name.endsWith(SET_ASIDE_SUFFIX)) return null
            if (name.length <= SET_ASIDE_PREFIX.length + SET_ASIDE_SUFFIX.length) return null
            val digits = name.substring(SET_ASIDE_PREFIX.length, name.length - SET_ASIDE_SUFFIX.length)
            if (digits.length > 9 || !digits.all { it in '0'..'9' }) return null
            return if (file.isFile) digits.toInt() else null
        }

        /**
         * Sets the saved settings aside so the next start uses defaults. Called by safe mode,
         * before any [ConfigStore] exists.
         *
         * Every time, they become a copy of their own; copies made earlier are never
         * overwritten. Which copy holds the settings the user cares about cannot be known here
         * (the ones in use before the trouble began, usually, but after several trips through
         * safe mode it may be any of them), so all are kept and can be brought back one after
         * the other, newest first. Only with [MAX_SET_ASIDE] copies already there does one have
         * to go: the second oldest. That keeps the very first (what was in use before any of
         * this) and the most recent ones.
         */
        fun quarantine(context: Context) {
            val dir = context.filesDir
            val current = File(dir, FILE)
            val backup = File(dir, BACKUP)
            // The backup is the live copy if the main file was unreadable at the last start.
            val sources = listOf(current, backup).filter { it.isFile }
            if (sources.isNotEmpty()) {
                val copies = setAsideCopies(dir)
                val newest = copies.firstOrNull()
                var number = (newest?.let(::setAsideNumber) ?: 0) + 1
                // Should the folder not have listed what is in it, a name already taken is not used.
                while (number <= MAX_SET_ASIDE_NUMBER && File(dir, "$SET_ASIDE_PREFIX$number$SET_ASIDE_SUFFIX").exists()) number++
                if (newest != null && number > MAX_SET_ASIDE_NUMBER) {
                    // Out of numbers, which takes a billion trips: the newest copy is replaced.
                    sources.any { it.renameTo(newest) }
                } else {
                    val target = File(dir, "$SET_ASIDE_PREFIX$number$SET_ASIDE_SUFFIX")
                    // One rename, which also takes the settings out of use. Nothing is deleted
                    // before it has worked.
                    val moved = sources.any { it.renameTo(target) }
                    if (moved) {
                        // Looked at afresh each time, so that one which could not be removed on
                        // an earlier occasion goes now. The list is newest first: the second
                        // oldest is the last but one.
                        var kept = setAsideCopies(dir)
                        var attempts = 0
                        while (kept.size > MAX_SET_ASIDE && attempts++ < MAX_SET_ASIDE) {
                            val removed = runCatching { kept[kept.size - 2].delete() }.getOrDefault(false)
                            if (!removed) break
                            kept = setAsideCopies(dir)
                        }
                    }
                }
            }
            current.delete()
            backup.delete()
        }
    }
}
