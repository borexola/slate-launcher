package app.slate.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Rules for files that did not come from Slate itself: images and settings a user (or, on older
 * Android versions, another app) put where Slate will read them.
 *
 * The danger with images is not the picture but everything else a file can carry. A PNG can
 * hold compressed text that the system decoder inflates to gigabytes while merely reading the
 * header; a JPEG can hold a second, enormous image (an HDR gain map) that newer Android versions
 * decode alongside the first. Running out of memory inside the decoder kills the process, and no
 * code here could catch it.
 *
 * So the system decoder never sees an untrusted file as it arrived. Slate reads the file's
 * structure itself, takes the picture's dimensions from it, and rebuilds a copy containing only
 * the parts needed to produce pixels. Only that copy is decoded. Anything not understood is
 * rejected rather than passed along.
 */
internal object Untrusted {

    /**
     * Reads a whole file, or returns null if it is larger than [limit] or cannot be read.
     *
     * Everything is decided from this one snapshot, so the file cannot be swapped for a different
     * one between being checked and being used.
     */
    fun read(file: File, limit: Long): ByteArray? = try {
        val size = file.length()
        if (!file.isFile || size > limit) {
            null
        } else {
            file.inputStream().use { input ->
                val bytes = ByteArray(size.toInt())
                var filled = 0
                while (filled < bytes.size) {
                    val read = input.read(bytes, filled, bytes.size - filled)
                    if (read < 0) break
                    filled += read
                }
                // Shorter or longer than it claimed: it is changing underneath us, so reject it.
                if (filled != bytes.size || input.read() >= 0) null else bytes
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * An image reduced to what is needed to draw it, with the size its own header declares.
     *
     * [buffered] marks files that make the decoder hold the whole picture in memory at full
     * size, however small the result is asked to be. [orientation] is the camera's note of
     * which way up the picture is (1 to 8, 1 meaning as stored); see [upright].
     */
    class CleanImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val buffered: Boolean,
        val orientation: Int = 1,
        val channels: Int = 3,
    )

    /**
     * Turns untrusted image bytes into a [CleanImage], or returns null if they are not a plain
     * JPEG or PNG within the limits.
     *
     * [maxBufferedPixels] is the tighter limit for images the decoder must hold whole. It is
     * stated for an ordinary three-channel picture; one with four channels needs a third more
     * memory per pixel, so it is allowed correspondingly fewer.
     */
    fun cleanImage(raw: ByteArray, maxSide: Int, maxPixels: Long, maxBufferedPixels: Long): CleanImage? = try {
        val clean = when {
            isPng(raw) -> cleanPng(raw)
            isJpeg(raw) -> cleanJpeg(raw)
            else -> null
        }
        when {
            clean == null -> null
            clean.width <= 0 || clean.height <= 0 -> null
            clean.width > maxSide || clean.height > maxSide -> null
            clean.width.toLong() * clean.height > maxPixels -> null
            clean.buffered && clean.width.toLong() * clean.height * clean.channels > maxBufferedPixels * 3 -> null
            // Last, let the system read the cleaned header and confirm it sees the same picture.
            !agreesWithSystem(clean) -> null
            else -> clean
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun agreesWithSystem(image: CleanImage): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
        return (options.outMimeType == "image/jpeg" || options.outMimeType == "image/png") &&
            options.outWidth == image.width && options.outHeight == image.height
    }

    /**
     * Turns a decoded picture the right way up, following the camera's orientation note. The
     * system decoder ignores that note, and it is in a part of the file that is not passed on.
     */
    fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.postScale(-1f, 1f)
            3 -> matrix.postRotate(180f)
            4 -> matrix.postScale(1f, -1f)
            5 -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.postRotate(90f)
            7 -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    // ---- PNG ----

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun isPng(raw: ByteArray) =
        raw.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { raw[it] == PNG_SIGNATURE[it] }

    /**
     * The only optional chunks that affect how the picture looks, and whether a given length is
     * what the format allows for each. Text, colour profiles, animation and everything else are
     * dropped; an allowed chunk of the wrong size means the file is not to be trusted.
     */
    private fun pngChunkAllowed(type: String, length: Int): Boolean = when (type) {
        "PLTE" -> length in 3..768 && length % 3 == 0
        "tRNS" -> length in 1..256
        "gAMA" -> length == 4
        "cHRM" -> length == 32
        "sRGB" -> length == 1
        else -> false
    }

    private val PNG_OPTIONAL = setOf("PLTE", "tRNS", "gAMA", "cHRM", "sRGB")

    private fun cleanPng(raw: ByteArray): CleanImage? {
        val out = ByteArrayOutputStream(raw.size)
        out.write(raw, 0, PNG_SIGNATURE.size)
        var position = PNG_SIGNATURE.size
        var width = 0
        var height = 0
        var sawHeader = false
        var sawData = false
        val seen = HashSet<String>()
        while (true) {
            // Each chunk: 4-byte length, 4-byte type, the data, 4-byte checksum.
            if (position + 12 > raw.size) return null
            val length = int32(raw, position)
            if (length < 0 || length > raw.size - position - 12) return null
            val type = String(raw, position + 4, 4, Charsets.ISO_8859_1)
            val whole = 12 + length
            if (!sawHeader) {
                if (type != "IHDR" || length != 13) return null
                val data = position + 8
                width = int32(raw, data)
                height = int32(raw, data + 4)
                // 16 bits per channel doubles the memory of the decoded picture: not accepted.
                val depth = raw[data + 8].toInt()
                if (depth != 1 && depth != 2 && depth != 4 && depth != 8) return null
                // Compression and filter method have one legal value each. Interlaced files make
                // the decoder hold the full-size picture: not accepted either.
                if (raw[data + 10].toInt() != 0 || raw[data + 11].toInt() != 0 || raw[data + 12].toInt() != 0) return null
                sawHeader = true
                out.write(raw, position, whole)
            } else {
                when (type) {
                    "IHDR" -> return null
                    "IDAT" -> {
                        sawData = true
                        out.write(raw, position, whole)
                    }
                    "IEND" -> {
                        if (!sawData || length != 0) return null
                        out.write(raw, position, whole)
                        return CleanImage(out.toByteArray(), width, height, buffered = false)
                    }
                    in PNG_OPTIONAL -> {
                        // Once each, before the picture data, and of a legal size; else reject.
                        if (sawData || !seen.add(type) || !pngChunkAllowed(type, length)) return null
                        out.write(raw, position, whole)
                    }
                    else -> Unit
                }
            }
            position += whole
        }
    }

    // ---- JPEG ----

    private const val MAX_JPEG_SCANS = 64

    private fun isJpeg(raw: ByteArray) =
        raw.size > 4 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()

    /**
     * Rebuilds the first image of a JPEG from an explicit list of parts: quantisation and
     * Huffman tables, the restart interval, one frame header (baseline, extended or progressive,
     * 8 bits, 1 to 4 channels), its scans, and the two application segments that say how to read
     * the colour channels. Comments and all other application segments are dropped (so no
     * thumbnails, no profiles, no pointers to further images), and so is whatever follows the
     * end of the first image. Any part not on the list means the file is refused.
     */
    private fun cleanJpeg(raw: ByteArray): CleanImage? {
        val out = ByteArrayOutputStream(raw.size)
        out.write(0xFF)
        out.write(0xD8)
        var position = 2
        var width = 0
        var height = 0
        var components = 0
        var progressive = false
        var sawFrame = false
        var scans = 0
        var partialScan = false
        var orientation = 1
        var sawExif = false
        while (true) {
            if (position + 1 >= raw.size || unsigned(raw, position) != 0xFF) return null
            var marker = unsigned(raw, position + 1)
            while (marker == 0xFF) {
                // Padding before a marker.
                position++
                if (position + 1 >= raw.size) return null
                marker = unsigned(raw, position + 1)
            }
            position += 2

            if (marker == 0xD9) {
                if (!sawFrame || scans == 0) return null
                out.write(0xFF)
                out.write(0xD9)
                // More than one scan, or a scan covering only some channels, means the decoder
                // cannot work strip by strip and keeps the whole picture, as for progressive.
                val buffered = progressive || scans > 1 || partialScan
                return CleanImage(out.toByteArray(), width, height, buffered, orientation, components)
            }

            // Every part accepted from here on carries a two-byte length.
            if (position + 2 > raw.size) return null
            val length = unsigned(raw, position) shl 8 or unsigned(raw, position + 1)
            if (length < 2 || length > raw.size - position) return null

            val keep: Boolean
            when (marker) {
                0xC0, 0xC1, 0xC2 -> {
                    // Frame header: precision, height, width, channel count, 3 bytes per channel.
                    if (sawFrame || length < 8) return null
                    if (unsigned(raw, position + 2) != 8) return null
                    height = unsigned(raw, position + 3) shl 8 or unsigned(raw, position + 4)
                    width = unsigned(raw, position + 5) shl 8 or unsigned(raw, position + 6)
                    components = unsigned(raw, position + 7)
                    if (components !in 1..4 || length != 8 + 3 * components) return null
                    progressive = marker == 0xC2
                    sawFrame = true
                    keep = true
                }
                0xDA -> {
                    // Scan header: channel count, 2 bytes per channel, 3 more bytes.
                    if (!sawFrame || length < 6) return null
                    val scanComponents = unsigned(raw, position + 2)
                    if (scanComponents !in 1..components || length != 6 + 2 * scanComponents) return null
                    if (scanComponents < components) partialScan = true
                    if (++scans > MAX_JPEG_SCANS) return null
                    keep = true
                }
                0xC4, 0xDB -> keep = true // Huffman and quantisation tables
                0xDD -> {
                    if (length != 4) return null // restart interval
                    keep = true
                }
                0xE0, 0xEE -> keep = true // JFIF and Adobe: how to interpret the colour channels
                0xE1 -> {
                    // Exif: note which way up the picture is, then drop the segment.
                    // The same segment type also carries other metadata (XMP), sometimes first.
                    if (!sawExif) {
                        val found = exifOrientation(raw, position + 2, length - 2)
                        if (found != null) {
                            sawExif = true
                            orientation = found
                        }
                    }
                    keep = false
                }
                in 0xE2..0xEF, 0xFE -> keep = false // other application data, comments
                else -> return null
            }
            if (keep) {
                out.write(0xFF)
                out.write(marker)
                out.write(raw, position, length)
            }
            position += length

            if (marker == 0xDA) {
                // Compressed picture data runs from here until the next real marker.
                val start = position
                while (true) {
                    if (position + 1 >= raw.size) return null
                    if (unsigned(raw, position) == 0xFF) {
                        val next = unsigned(raw, position + 1)
                        if (next == 0x00 || next in 0xD0..0xD7) {
                            position += 2
                            continue
                        }
                        if (next == 0xFF) {
                            position++
                            continue
                        }
                        break
                    }
                    position++
                }
                out.write(raw, start, position - start)
            }
        }
    }

    /**
     * Reads the orientation (1 to 8) from an Exif segment's contents, which start at [start] and
     * are [length] bytes long. Returns null if this is not an Exif segment at all, and 1, "as
     * stored", if it is one but says nothing usable. Every read is checked against the
     * segment's own bounds.
     */
    private fun exifOrientation(raw: ByteArray, start: Int, length: Int): Int? {
        val end = start + length
        // "Exif", two zero bytes, then an 8-byte header.
        if (length < 6 || String(raw, start, 6, Charsets.ISO_8859_1) != "Exif\u0000\u0000") return null
        if (length < 14) return 1
        val base = start + 6
        val little = when (String(raw, base, 2, Charsets.ISO_8859_1)) {
            "II" -> true
            "MM" -> false
            else -> return 1
        }
        fun u16(at: Int): Int {
            if (at < base || at + 2 > end) return -1
            val a = unsigned(raw, at)
            val b = unsigned(raw, at + 1)
            return if (little) (b shl 8) or a else (a shl 8) or b
        }
        fun u32(at: Int): Long {
            if (at < base || at + 4 > end) return -1
            val low = u16(if (little) at else at + 2)
            val high = u16(if (little) at + 2 else at)
            return (high.toLong() shl 16) or low.toLong()
        }
        if (u16(base + 2) != 42) return 1
        val offset = u32(base + 4)
        if (offset < 8 || offset > length) return 1
        val directory = base + offset.toInt()
        val entries = u16(directory)
        if (entries < 0) return 1
        for (i in 0 until minOf(entries, 64)) {
            // Each entry: tag, type, count, value; 12 bytes.
            val entry = directory + 2 + i * 12
            val tag = u16(entry)
            if (tag < 0) return 1
            if (tag == 0x0112) {
                if (u16(entry + 2) != 3 || u32(entry + 4) != 1L) return 1
                val value = u16(entry + 8)
                return if (value in 1..8) value else 1
            }
        }
        return 1
    }

    private fun unsigned(raw: ByteArray, at: Int) = raw[at].toInt() and 0xFF

    private fun int32(raw: ByteArray, at: Int): Int =
        (unsigned(raw, at) shl 24) or (unsigned(raw, at + 1) shl 16) or (unsigned(raw, at + 2) shl 8) or unsigned(raw, at + 3)
}
