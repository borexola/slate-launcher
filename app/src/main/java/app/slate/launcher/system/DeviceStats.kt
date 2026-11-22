package app.slate.launcher.system

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File

/** How the TV is connected right now. */
enum class Link { WIFI, ETHERNET, OTHER, OFFLINE }

/**
 * A reading of the TV's memory, storage, processor and connection. Anything Android does not let
 * an app see is null, and is then simply not shown.
 */
data class DeviceStats(
    val memoryFree: Long?,
    val memoryTotal: Long?,
    val storageFree: Long?,
    val storageTotal: Long?,
    /** How busy the processor was since the last reading, 0 to 100. */
    val cpuLoad: Int?,
    /** Where the load cannot be seen: the processor's current speed, in MHz (fastest core). */
    val cpuMhz: Int?,
    val link: Link?,
    /** Wi-Fi strength from 0 to 4, where Android says. */
    val bars: Int?,
)

/**
 * Takes readings for the status chips. Cheap: a few small files and system calls, no permission
 * beyond seeing the network state. Since Android 8 an ordinary app may not read the processor's
 * load (`/proc/stat`); on such TVs the speed of its cores is used where it can be read, and
 * otherwise the processor is left out. Not thread-safe: one reader per caller.
 */
class DeviceStatsReader(context: Context) {
    private val app = context.applicationContext
    private var lastBusy = -1L
    private var lastTotal = -1L
    private var loadReadable = true
    private var speedReadable = true

    /** Forgets the last processor reading, so the next load is measured from the next call. */
    fun restart() {
        runCatching { cpuLoad() }
    }

    fun read(): DeviceStats {
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            info.availMem to info.totalMem
        }.getOrNull()
        val storage = runCatching {
            val fs = StatFs(Environment.getDataDirectory().path)
            fs.availableBytes to fs.totalBytes
        }.getOrNull()
        val (link, bars) = network()
        val load = if (loadReadable) cpuLoad() else null
        return DeviceStats(
            memoryFree = memory?.first,
            memoryTotal = memory?.second,
            storageFree = storage?.first,
            storageTotal = storage?.second,
            cpuLoad = load,
            cpuMhz = if (load == null && !loadReadable && speedReadable) cpuSpeed() else null,
            link = link,
            bars = bars,
        )
    }

    /** Percent busy since the previous call; null the first time, or if it cannot be read. */
    private fun cpuLoad(): Int? {
        val line = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        } catch (e: Exception) {
            loadReadable = false
            return null
        }
        val fields = line?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() }
        if (fields == null || fields.size < 4) {
            loadReadable = false
            return null
        }
        val idle = fields[3] + (fields.getOrNull(4) ?: 0L)
        val total = fields.take(8).sum()
        val busy = total - idle
        val previousBusy = lastBusy
        val previousTotal = lastTotal
        lastBusy = busy
        lastTotal = total
        if (previousTotal < 0 || total <= previousTotal) return null
        return (100 * (busy - previousBusy) / (total - previousTotal)).toInt().coerceIn(0, 100)
    }

    /** Where each core's current speed is, as far as there are such files; looked up once. */
    private val speedFiles: List<File> by lazy {
        (0 until Runtime.getRuntime().availableProcessors().coerceIn(1, 64))
            .map { File("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") }
            .filter { runCatching { it.canRead() }.getOrDefault(false) }
    }

    /** The fastest core's current speed in MHz, or null if no core's speed can be read. */
    private fun cpuSpeed(): Int? {
        val khz = speedFiles.mapNotNull { file ->
            runCatching { file.readText().trim().toLong() }.getOrNull()
        }.maxOrNull()
        // Below 100 MHz is no real reading (emulators report such numbers).
        if (khz == null || khz < 100_000) {
            speedReadable = false
            return null
        }
        return (khz / 1000).toInt()
    }

    private fun network(): Pair<Link?, Int?> = try {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        when {
            caps == null -> Link.OFFLINE to null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link.ETHERNET to null
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) caps.signalStrength else Int.MIN_VALUE
                val bars = when {
                    dbm == Int.MIN_VALUE || dbm >= 0 -> null
                    dbm >= -55 -> 4
                    dbm >= -66 -> 3
                    dbm >= -77 -> 2
                    dbm >= -88 -> 1
                    else -> 0
                }
                Link.WIFI to bars
            }
            else -> Link.OTHER to null
        }
    } catch (e: Exception) {
        // No permission to look, or the service is not there: not shown.
        null to null
    }
}
