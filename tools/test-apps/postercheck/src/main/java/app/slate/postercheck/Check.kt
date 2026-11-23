package app.slate.postercheck

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import app.slate.launcher.data.SafeFetch
import app.slate.launcher.data.Untrusted
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Says, for each entry of the TV's "watch next" list, whether Slate would show its poster and,
 * if not, why. It runs Slate's own address check, fetch and image cleaning (the same source
 * files), and where those turn a poster away it has a plain look at what is there.
 *
 * Nothing is shown on the TV and nothing is changed. Titles are neither read nor printed, and
 * addresses are printed as their host name only (a server on the home network as "home network").
 *
 * To build it, compile this file together with Slate's SafeFetch.kt and Untrusted.kt (copied from
 * app/src/main/java/app/slate/launcher/data) as an Android app with app.build.gradle.kts.
 *
 *   adb install -g postercheck.apk
 *   adb shell am instrument -w app.slate.postercheck/.Check
 *   adb uninstall app.slate.postercheck
 */
class Check : Instrumentation() {

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val out = StringBuilder()
        try {
            run(context, out)
        } catch (e: Throwable) {
            out.append("failed: ").append(e).append('\n')
        }
        finish(Activity.RESULT_OK, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, out.toString()) })
    }

    private class Row(val pkg: String, val poster: String?, val thumbnail: String?, val aspect: Int, val engaged: Long)

    private val fetcher = SafeFetch(CoroutineScope(Dispatchers.IO), MAX_POSTER_BYTES, accept = "image/jpeg, image/png, image/webp")
    private var maxBuffered = MAX_POSTER_BUFFERED
    private val aside = Executors.newCachedThreadPool()

    private fun run(context: Context, out: StringBuilder) {
        val pm = context.packageManager
        if (context.checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) {
            out.append("The permission to read the list is missing: install with  adb install -g\n")
            return
        }
        maxBuffered = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memory = android.app.ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
            if (!manager.isLowRamDevice && memory.totalMem >= 1_200L * 1024 * 1024) 8_300_000L else MAX_POSTER_BUFFERED
        }.getOrDefault(MAX_POSTER_BUFFERED)
        out.append("pictures the decoder holds whole: up to $maxBuffered pixels on this TV\n")
        val rows = ArrayList<Row>()
        val list = Uri.parse("content://android.media.tv/watch_next_program")
        val columns = arrayOf(
            "package_name", "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio",
            "last_engagement_time_utc_millis", "browsable",
        )
        context.contentResolver.query(list, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(5) && c.getInt(5) == 0) continue
                rows += Row(
                    c.getString(0).orEmpty(),
                    if (c.isNull(1)) null else c.getString(1),
                    if (c.isNull(2)) null else c.getString(2),
                    if (c.isNull(3)) -1 else c.getInt(3),
                    if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        out.append("${rows.size} entries in the list\n")
        var shown = 0
        var blank = 0
        val reasons = sortedMapOf<String, Int>()
        for ((pkg, mine) in rows.groupBy { it.pkg }.toSortedMap()) {
            val newest = mine.sortedByDescending { it.engaged }.take(PER_APP)
            val network = pm.checkPermission(Manifest.permission.INTERNET, pkg) == PackageManager.PERMISSION_GRANTED
            out.append("\n$pkg: ${mine.size} entries, looking at ${newest.size}")
            if (!network) out.append(" (the app may not use the network, so Slate fetches nothing for it)")
            out.append('\n')
            newest.forEachIndexed { index, row ->
                val verdict = entry(context, pm, row, network, out, index + 1)
                if (verdict == null) shown++ else {
                    blank++
                    val key = "$pkg: $verdict"
                    reasons[key] = (reasons[key] ?: 0) + 1
                }
            }
        }
        out.append("\nSUMMARY: $shown would show a poster, $blank would not\n")
        for ((reason, count) in reasons) out.append("  $count x $reason\n")
    }

    /** Null if Slate shows a poster for [row], else why not. */
    private fun entry(context: Context, pm: PackageManager, row: Row, network: Boolean, out: StringBuilder, number: Int): String? {
        val given = listOf("poster" to row.poster, "thumbnail" to row.thumbnail).filter { !it.second.isNullOrEmpty() }
        out.append("  #$number aspect=${row.aspect}\n")
        if (given.isEmpty()) {
            out.append("    no poster or thumbnail address at all\n")
            return "no address given"
        }
        var why: String? = null
        var used = false
        for ((name, address) in given) {
            address!!
            out.append("    $name: ${shape(address)}\n")
            val refusal = addressRefusal(address)
            if (used) continue
            if (refusal == null && address.length <= SafeFetch.MAX_URL_LENGTH && fetcher.checked(address) != null) {
                // The one Slate uses: the first address that passes.
                used = true
                why = if (!network) "app without network permission" else slateFetch(address, row.pkg, out)
            } else if (address.length <= SafeFetch.MAX_URL_LENGTH && own(pm, address, row.pkg) != null) {
                used = true
                why = ownRead(context, own(pm, address, row.pkg)!!, out)
            } else {
                out.append("      Slate: address not accepted (${refusal ?: "by its check"})\n")
                why = why ?: "address: ${refusal ?: "refused"}"
                look(context, pm, address, row.pkg, out)
            }
        }
        out.append(if (why == null) "    => SHOWN\n" else "    => BLANK: $why\n")
        return why
    }

    /** Fetches and cleans as Slate does. Null if that gives a picture, else why not. */
    private fun slateFetch(address: String, pkg: String, out: StringBuilder): String? {
        val status = IntArray(1)
        val raw = try {
            fetcher.download(address, pkg, active = { true }, status = status)
        } catch (e: SafeFetch.Refused) {
            out.append("      Slate fetch: refused (${e.message})\n")
            return "fetch refused: ${e.message}"
        } catch (e: IOException) {
            out.append("      Slate fetch: not reached (${e.message})\n")
            return "not reached: ${e.message}"
        }
        if (raw == null) {
            out.append("      Slate fetch: no usable answer, status ${status[0]}\n")
            look(null, null, address, pkg, out)
            return "server answered ${status[0]}"
        }
        val what = describe(raw)
        out.append("      Slate fetch: ${raw.size / 1024} KB, $what\n")
        val clean = Untrusted.cleanImage(raw, MAX_POSTER_SIDE, MAX_POSTER_PIXELS, maxBuffered, webp = true)
        if (clean != null) return null
        val reason = cleanerReason(raw)
        out.append("      Slate cleaning: refused ($reason)\n")
        return "picture refused: $reason"
    }

    /** As Slate's WatchNextRepository.ownPicture. */
    private fun own(pm: PackageManager, address: String, pkg: String): Uri? {
        if (!address.startsWith("content://") || address.any { it.code !in 0x21..0x7E }) return null
        val uri = runCatching { Uri.parse(address) }.getOrNull() ?: return null
        val authority = uri.encodedAuthority?.takeIf { it.isNotEmpty() && '@' !in it } ?: return null
        val provider = runCatching { pm.resolveContentProvider(authority, 0) }.getOrNull() ?: return null
        return if (provider.packageName == pkg && provider.exported && provider.enabled) uri else null
    }

    /** Reads a picture the app hands out itself and cleans it as Slate does. Null if that gives a picture. */
    private fun ownRead(context: Context, uri: Uri, out: StringBuilder): String? {
        val started = System.currentTimeMillis()
        val raw = try {
            aside.submit(
                Callable {
                    context.contentResolver.acquireUnstableContentProviderClient(uri)?.let { client ->
                        try {
                            client.openAssetFile(uri, "r", null)?.use { file -> file.createInputStream().use { take(it) } }
                        } finally {
                            runCatching { client.close() }
                        }
                    }
                },
            ).get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            out.append("      own picture: not read (${(e.cause ?: e).javaClass.simpleName})\n")
            return "own picture not read: ${(e.cause ?: e).javaClass.simpleName}"
        }
        if (raw == null) {
            out.append("      own picture: nothing handed over\n")
            return "own picture: nothing handed over"
        }
        out.append("      own picture: ${raw.size / 1024} KB in ${System.currentTimeMillis() - started} ms, ${describe(raw)}\n")
        if (raw.size > MAX_POSTER_BYTES) return "own picture over ${MAX_POSTER_BYTES / 1024 / 1024} MB"
        if (Untrusted.cleanImage(raw, MAX_POSTER_SIDE, MAX_POSTER_PIXELS, maxBuffered, webp = true) != null) return null
        val reason = cleanerReason(raw)
        out.append("      Slate cleaning: refused ($reason)\n")
        return "picture refused: $reason"
    }

    /** A plain look at what an address Slate turned away would give. */
    private fun look(context: Context?, pm: PackageManager?, address: String, pkg: String, out: StringBuilder) {
        val uri = Uri.parse(address)
        val result = try {
            aside.submit(
                Callable {
                    when (uri.scheme?.lowercase()) {
                        "http", "https" -> {
                            val connection = URL(address).openConnection() as HttpURLConnection
                            connection.connectTimeout = 5_000
                            connection.readTimeout = 6_000
                            connection.setRequestProperty("User-Agent", "Slate")
                            connection.setRequestProperty("Accept", "image/webp,image/avif,image/*,*/*;q=0.5")
                            try {
                                val code = connection.responseCode
                                if (code != 200) {
                                    "status $code"
                                } else {
                                    val bytes = connection.inputStream.use { take(it) }
                                    "status 200, type ${connection.contentType}, ${bytes.size / 1024} KB, ${describe(bytes)}"
                                }
                            } finally {
                                connection.disconnect()
                            }
                        }
                        "content", "android.resource" -> {
                            if (context == null) {
                                "not looked at"
                            } else {
                                val owner = if (uri.scheme == "content") {
                                    pm?.resolveContentProvider(uri.authority.orEmpty(), 0)?.let {
                                        "provider of ${if (it.packageName == pkg) "the same app" else "another app"}, " +
                                            "${if (it.exported) "open to other apps" else "closed to other apps"}" +
                                            (it.readPermission?.let { p -> ", needs a permission" } ?: "")
                                    } ?: "provider not found"
                                } else {
                                    "resource of ${if (uri.authority == pkg) "the same app" else "another app"}"
                                }
                                val bytes = context.contentResolver.openInputStream(uri)?.use { take(it) }
                                if (bytes == null) "$owner; nothing to read" else "$owner; readable, ${bytes.size / 1024} KB, ${describe(bytes)}"
                            }
                        }
                        else -> "not looked at"
                    }
                },
            ).get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Without addresses: a message may name this TV's own and the server's.
            val message = (e.cause ?: e).message.orEmpty().replace(Regex("/?[0-9a-fA-F:.]*[0-9]+[.:][0-9a-fA-F:.]+"), "(address)").take(80)
            "could not be read (${(e.cause ?: e).javaClass.simpleName}: $message)"
        }
        out.append("      plain look: $result\n")
    }

    private fun take(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < LOOK_BYTES) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** The address without anything that names what is being watched or who is asking. */
    private fun shape(address: String): String {
        val uri = Uri.parse(address)
        val scheme = uri.scheme ?: "?"
        val host = uri.host.orEmpty()
        val where = when (scheme.lowercase()) {
            "http", "https" -> if (looksLocal(host)) "home network" else host
            "content", "android.resource" -> host
            else -> ""
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val ending = uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "-"
        return "$scheme://$where$port ending=$ending query=${if (uri.query != null) "yes" else "no"} length=${address.length}"
    }

    private fun looksLocal(host: String): Boolean {
        if (host.isEmpty()) return false
        if (!host.contains('.') || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")) return true
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) {
            return runCatching {
                val a = InetAddress.getByName(host)
                a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress
            }.getOrDefault(true)
        }
        return false
    }

    /** Why Slate's address check says no, in words; null if it is not one of these. */
    private fun addressRefusal(address: String): String? {
        val uri = Uri.parse(address)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host.orEmpty()
        return when {
            scheme == "content" -> "a content:// address, not a web address"
            scheme == "android.resource" -> "an android.resource:// address, not a web address"
            scheme == "file" -> "a file:// address"
            scheme == "http" -> "http, not https" + if (looksLocal(host)) ", on the home network" else ""
            scheme != "https" -> "scheme $scheme"
            looksLocal(host) -> "https on the home network"
            uri.port > 0 && uri.port != 443 -> "a port other than 443"
            address.length > SafeFetch.MAX_URL_LENGTH -> "address longer than ${SafeFetch.MAX_URL_LENGTH}"
            address.any { it.code !in 0x21..0x7E } -> "characters outside plain ASCII"
            uri.userInfo != null -> "a user name in the address"
            else -> null
        }
    }

    private fun format(raw: ByteArray): String {
        fun at(offset: Int, text: String) =
            raw.size >= offset + text.length && text.indices.all { raw[offset + it] == text[it].code.toByte() }
        return when {
            raw.size > 3 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte() -> "jpeg"
            at(1, "PNG") -> "png"
            at(0, "RIFF") && at(8, "WEBP") -> "webp"
            at(0, "GIF8") -> "gif"
            at(4, "ftypavif") || at(4, "ftypavis") -> "avif"
            at(4, "ftypheic") || at(4, "ftypmif1") -> "heif"
            raw.take(200).all { it in 9..126 } -> "text, not a picture"
            else -> "unknown"
        }
    }

    private class Jpeg(val progressive: Boolean, val scans: Int, val channels: Int, val odd: String?)

    private fun jpeg(raw: ByteArray): Jpeg? {
        var position = 2
        var progressive = false
        var scans = 0
        var channels = 3
        var odd: String? = null
        fun u(at: Int) = raw[at].toInt() and 0xFF
        while (position + 3 < raw.size) {
            if (u(position) != 0xFF) return null
            val marker = u(position + 1)
            if (marker == 0xFF) { position++; continue }
            position += 2
            if (marker == 0xD9) break
            if (marker in 0xD0..0xD7 || marker == 0x01) continue
            if (position + 2 > raw.size) return null
            val length = u(position) shl 8 or u(position + 1)
            when (marker) {
                0xC0, 0xC1 -> channels = u(position + 7)
                0xC2 -> { progressive = true; channels = u(position + 7) }
                0xDA -> scans++
                0xC4, 0xDB, 0xDD, 0xFE -> Unit
                in 0xE0..0xEF -> Unit
                else -> odd = odd ?: "part 0x%02X".format(marker)
            }
            if (marker in 0xC0..0xC2 && u(position + 2) != 8) odd = odd ?: "${u(position + 2)} bits"
            position += length
            if (marker == 0xDA) {
                while (position + 1 < raw.size) {
                    if (u(position) == 0xFF && u(position + 1) != 0x00 && u(position + 1) !in 0xD0..0xD7 && u(position + 1) != 0xFF) break
                    position++
                }
            }
        }
        return Jpeg(progressive, scans, channels, odd)
    }

    private fun bounds(raw: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size, options) }
        return options.outWidth to options.outHeight
    }

    private fun describe(raw: ByteArray): String {
        val kind = format(raw)
        val (w, h) = bounds(raw)
        val size = if (w > 0) " ${w}x$h" else ""
        val more = if (kind == "jpeg") {
            jpeg(raw)?.let { " ${if (it.progressive) "progressive" else "baseline"}, ${it.scans} scans, ${it.channels} channels${it.odd?.let { o -> ", $o" } ?: ""}" }
                ?: " (structure not followed)"
        } else {
            ""
        }
        return "$kind$size$more"
    }

    private fun cleanerReason(raw: ByteArray): String {
        val kind = format(raw)
        if (kind != "jpeg" && kind != "png" && kind != "webp") return "$kind, and only JPEG, PNG and WebP are taken"
        val (w, h) = bounds(raw)
        val pixels = w.toLong() * h
        if (w > MAX_POSTER_SIDE || h > MAX_POSTER_SIDE) return "a side over $MAX_POSTER_SIDE pixels (${w}x$h)"
        if (pixels > MAX_POSTER_PIXELS) return "over ${MAX_POSTER_PIXELS / 1_000_000} million pixels (${w}x$h)"
        if (kind == "jpeg") {
            val j = jpeg(raw)
            if (j != null && (j.progressive || j.scans > 1) && pixels * j.channels > maxBuffered * 3) {
                return "a progressive JPEG over ${maxBuffered / 100_000 / 10.0} million pixels (${w}x$h)"
            }
            if (j?.odd != null) return "JPEG with ${j.odd}"
        }
        return "its structure ($kind ${w}x$h)"
    }

    private companion object {
        const val PER_APP = 6
        const val LOOK_BYTES = 8 * 1024 * 1024

        // As in Slate's WatchNextRepository.
        const val MAX_POSTER_BYTES = 3L * 1024 * 1024
        const val MAX_POSTER_SIDE = 4096
        const val MAX_POSTER_PIXELS = 9_000_000L
        const val MAX_POSTER_BUFFERED = 2_500_000L
    }
}
