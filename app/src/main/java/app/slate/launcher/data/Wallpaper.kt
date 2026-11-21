package app.slate.launcher.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.slate.launcher.SlateApp
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * The wallpaper is a plain image file: either picked on the TV (copied into private storage) or
 * pushed over ADB as `files/wallpaper.jpg` (also .jpeg or .png) in the app's external folder.
 * Whichever was set most recently wins.
 *
 * The image is user-supplied, and on older Android versions other apps can write to the external
 * folder, so it is never trusted: see [Untrusted]. The decoded bitmap is always bounded to
 * roughly the size of the screen.
 */
object Wallpaper {

    private const val SCREEN_WIDTH = 1920
    private const val SCREEN_HEIGHT = 1080
    private const val MAX_DECODE_SIDE = 3072
    private const val MAX_SOURCE_SIDE = 16384
    private const val MAX_SOURCE_PIXELS = 25_000_000L
    private const val MAX_BUFFERED_PIXELS = 12_000_000L
    /** The largest image file accepted, here and for the screensaver's photos. */
    const val MAX_BYTES = 16L * 1024 * 1024
    private const val IMPORT_TIME_LIMIT_MS = 60_000L
    private const val STALE_TEMP_MS = 10 * 60_000L
    private const val TEMP_PREFIX = "wallpaper-import"
    private const val BLUR_DOWNSCALE = 8
    private val PUSHED_NAMES = listOf("wallpaper.jpg", "wallpaper.jpeg", "wallpaper.png")

    /** One validation at a time, and one decode at a time: both are memory-hungry. */
    private val importGate = Mutex()
    private val loadGate = Mutex()
    private val copySlots = Semaphore(2)

    private fun clean(bytes: ByteArray) =
        Untrusted.cleanImage(bytes, MAX_SOURCE_SIDE, MAX_SOURCE_PIXELS, MAX_BUFFERED_PIXELS)

    /** Whether [bytes] are an image within the wallpaper's limits that Slate is prepared to show. */
    internal fun accepts(bytes: ByteArray): Boolean = clean(bytes) != null

    private fun picked(context: Context) = File(context.filesDir, "wallpaper")

    /** Remembers which pushed file existed when an image was last picked on the TV. */
    private fun pushedSeen(context: Context) = File(context.filesDir, "wallpaper.pushed-seen")

    private fun pushed(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return PUSHED_NAMES.map { File(dir, it) }.filter { it.isFile }.maxByOrNull { it.lastModified() }
    }

    private fun describe(file: File) = "${file.name}:${file.lastModified()}:${file.length()}"

    fun file(context: Context): File? {
        if ((context.applicationContext as? SlateApp)?.safeMode?.value == true) return null
        val picked = picked(context).takeIf { it.isFile }
        val pushed = pushed(context)
        if (picked == null || pushed == null) return picked ?: pushed
        // A picked image stays until a different file is pushed afterwards. File dates cannot
        // decide this: an upload keeps the date the picture had on the computer.
        val seen = runCatching { pushedSeen(context).readText() }.getOrDefault("")
        return if (describe(pushed) == seen) picked else pushed
    }

    fun exists(context: Context): Boolean = file(context) != null

    /** Identifies the current file and its contents, to tell whether a reload is needed. */
    fun stamp(context: Context): String =
        file(context)?.let { "${it.path}:${it.lastModified()}:${it.length()}" }.orEmpty()

    /**
     * Copies a picked image into private storage. The previous wallpaper is only replaced once
     * the new file is complete, within the limits, and a picture Slate is prepared to show.
     * Call off the main thread.
     */
    suspend fun import(context: Context, uri: Uri): Boolean =
        importTo(context, uri, picked(context)) {
            runCatching { pushedSeen(context).writeText(pushed(context)?.let(::describe).orEmpty()) }
        }

    /**
     * Copies a picked image to [target], replacing what is there only once the copy is complete,
     * within the wallpaper's limits, and a picture Slate is prepared to show. [placed] runs once
     * the file is in place, under the same lock; it must not fail. Call off the main thread.
     */
    internal suspend fun importTo(context: Context, uri: Uri, target: File, placed: () -> Unit = {}): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        // The other end of this copy is another app, which may stall for ever. So the copy takes
        // no lock (a stuck one must not block the next), but only a couple may run at a time.
        val dir = context.filesDir
        // Leftovers of copies that never finished. Done first, so they are cleared even when
        // every slot below is held by a copy that is stuck.
        runCatching {
            val stale = System.currentTimeMillis() - STALE_TEMP_MS
            dir.listFiles { file -> file.name.startsWith(TEMP_PREFIX) && file.lastModified() < stale }
                ?.forEach { it.delete() }
        }
        if (!copySlots.tryAcquire()) return false
        try {
            // Beside its destination, because a file cannot always be moved between folders;
            // and its own file each time, so a stuck copy cannot write into a later one.
            val tmp = File.createTempFile(TEMP_PREFIX, ".tmp", dir)
            try {
                val input = context.contentResolver.openInputStream(uri) ?: return false
                val deadline = SystemClock.elapsedRealtime() + IMPORT_TIME_LIMIT_MS
                var total = 0L
                input.use { source ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            total += read
                            // It gets neither unlimited space nor unlimited time.
                            if (total > MAX_BYTES || SystemClock.elapsedRealtime() > deadline) return false
                            out.write(buffer, 0, read)
                        }
                    }
                }
                return importGate.withLock {
                    val bytes = Untrusted.read(tmp, MAX_BYTES)
                    if (bytes == null || clean(bytes) == null) {
                        false
                    } else if (!tmp.renameTo(target)) {
                        false
                    } else {
                        // The picture is in place from here on; this must not turn success into failure.
                        runCatching(placed)
                        true
                    }
                }
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return false
        } finally {
            copySlots.release()
        }
    }

    fun clearPicked(context: Context) {
        picked(context).delete()
        pushedSeen(context).delete()
    }

    /**
     * Removes every wallpaper file, picked or pushed. Returns whether none is left.
     * Call off the main thread.
     */
    fun clearAll(context: Context): Boolean {
        clearPicked(context)
        val dir = context.getExternalFilesDir(null)
        if (dir != null) (PUSHED_NAMES + "wallpaper.webp").forEach { File(dir, it).delete() }
        return !picked(context).exists() && pushed(context) == null
    }

    /** Call off the main thread. [blur] of 0 returns the image as is. */
    suspend fun load(context: Context, blur: Int): ImageBitmap? {
        val source = file(context) ?: return null
        return loadFile(source, blur)
    }

    /**
     * Any image file, within the wallpaper's limits, cleaned, the right way up, and scaled to
     * cover the screen (with [contain], to fit inside it); null if it is not one Slate would
     * show. Call off the main thread.
     */
    internal suspend fun loadFile(source: File, blur: Int = 0, contain: Boolean = false): ImageBitmap? = loadGate.withLock {
        try {
            val raw = Untrusted.read(source, MAX_BYTES) ?: return@withLock null
            val image = clean(raw) ?: return@withLock null
            // The size as it will be shown: orientations 5 to 8 turn the picture on its side.
            val turned = image.orientation in 5..8
            val shownWidth = if (turned) image.height else image.width
            val shownHeight = if (turned) image.width else image.height
            var sample = 1
            // Keep at least screen resolution where the image has it...
            while (shownWidth / (sample * 2) >= SCREEN_WIDTH && shownHeight / (sample * 2) >= SCREEN_HEIGHT) sample *= 2
            // ...but never decode a long side beyond a fixed bound, whatever the shape.
            while (image.width / sample > MAX_DECODE_SIDE || image.height / sample > MAX_DECODE_SIDE) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
                ?: return@withLock null
            val fitted = fitToScreen(Untrusted.upright(decoded, image.orientation), contain)
            val result = if (blur > 0) blurred(fitted, blur) else fitted
            result.asImageBitmap()
        } catch (e: Throwable) {
            // Includes OutOfMemoryError: fall back to the gradient rather than crash.
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /** Scales down so the image just covers the screen (or fits inside it), if it is larger than that. */
    private fun fitToScreen(bitmap: Bitmap, contain: Boolean): Bitmap {
        val across = SCREEN_WIDTH.toFloat() / bitmap.width
        val down = SCREEN_HEIGHT.toFloat() / bitmap.height
        val scale = if (contain) min(across, down) else max(across, down)
        if (scale >= 1f) return bitmap
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Blurs once, up front, on a small copy. The result is stretched back over the screen when
     * drawn, which costs nothing per frame and works on every Android version.
     */
    private fun blurred(bitmap: Bitmap, blur: Int): Bitmap {
        var small = bitmap
        var factor = 1
        while (factor < BLUR_DOWNSCALE && small.width >= 4 && small.height >= 4) {
            val half = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
            if (half !== small) small.recycle()
            small = half
            factor *= 2
        }
        val width = small.width
        val height = small.height
        val pixels = IntArray(width * height)
        val scratch = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        small.recycle()

        val radius = (blur / 4).coerceIn(1, 12)
        // Three box blurs approximate a Gaussian.
        repeat(3) {
            boxBlur(pixels, scratch, width, height, radius, horizontal = true)
            boxBlur(scratch, pixels, width, height, radius, horizontal = false)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val step = if (horizontal) 1 else width
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * width else line
            var red = 0
            var green = 0
            var blue = 0
            for (k in -radius..radius) {
                val pixel = src[start + k.coerceIn(0, length - 1) * step]
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
            }
            for (i in 0 until length) {
                dst[start + i * step] =
                    (0xFF shl 24) or ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
                val leaving = src[start + (i - radius).coerceIn(0, length - 1) * step]
                val entering = src[start + (i + radius + 1).coerceIn(0, length - 1) * step]
                red += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                green += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                blue += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
