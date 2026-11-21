package app.slate.launcher.data

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wikimedia Commons' Picture of the Day, for the screensaver: off unless the user picks it as a
 * source, and then the second thing Slate uses the network for (after Continue watching posters).
 *
 *  - No key and no account. Only three hosts are ever contacted, redirects included:
 *    commons.wikimedia.org (which file is the picture of a given day, its author and licence)
 *    and upload.wikimedia.org / thumb.wikimedia.org (the picture, 1920 pixels wide). See
 *    [SafeFetch] for how each fetch is bounded.
 *  - Wikimedia limits how often an address may ask, so a round asks at most twice, for all the
 *    missing days at once, then fetches the pictures one by one with a pause between them.
 *    Rounds are at most an hour apart (a minute when the user has just switched this on), and
 *    anything that goes wrong ends the round until the next.
 *  - The last [KEEP_DAYS] days' pictures are kept on disk with their credit line, so the
 *    screensaver has something to show offline. Older ones only go once newer ones are in.
 *  - A picture is only kept if it passes the same checks as a wallpaper ([Wallpaper.accepts]),
 *    and is cleaned again whenever it is shown.
 *
 * [allowed] says whether fetching is wanted at all (switched on, not in safe mode). It is asked
 * before each request; once it says no, the round stops.
 */
class PictureOfTheDay(private val context: Context, private val allowed: () -> Boolean) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val api = SafeFetch(scope, MAX_ANSWER_BYTES, "application/json", USER_AGENT, setOf(API_HOST), maxLookups = 1)
    private val images = SafeFetch(scope, Wallpaper.MAX_BYTES, "image/jpeg, image/png", USER_AGENT, IMAGE_HOSTS, maxLookups = 1)
    private val dir get() = File(context.filesDir, "potd")
    private val running = AtomicBoolean(false)

    /** When the last round started, in time since boot. */
    @Volatile
    private var lastRound = Long.MIN_VALUE / 2

    private val _version = MutableStateFlow(0)

    /** Goes up whenever a picture has been added or removed. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** A saved picture of the day and the line that credits it. */
    data class Picture(val file: File, val credit: String)

    /** The saved pictures, newest first. Call off the main thread. */
    fun saved(): List<Picture> = savedDates().take(KEEP_DAYS).mapNotNull { date ->
        val image = File(dir, "$date.img")
        val credit = Untrusted.read(File(dir, "$date.txt"), MAX_CREDIT_BYTES)?.let { cleanText(String(it, Charsets.UTF_8), MAX_CREDIT) }
        if (credit.isNullOrEmpty() || !image.isFile) null else Picture(image, credit)
    }

    private fun savedDates(): List<String> =
        runCatching { dir.listFiles() }.getOrNull().orEmpty()
            .map { it.name }
            .filter { it.endsWith(".img") && DATE.matches(it.removeSuffix(".img")) }
            .map { it.removeSuffix(".img") }
            .sortedDescending()

    /**
     * Fetches what is missing, in the background, unless a round ran lately. [soon] shortens
     * "lately" to a minute: for when the user has just switched this on.
     */
    fun refresh(soon: Boolean = false) {
        if (!allowed()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRound < if (soon) MIN_GAP_MS else ROUND_GAP_MS) return
        if (!running.compareAndSet(false, true)) return
        lastRound = now
        scope.launch {
            try {
                round()
            } catch (e: Throwable) {
                // Includes OutOfMemoryError: a bad answer must not take the launcher (or the
                // screensaver) down. The next round tries again.
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                running.set(false)
            }
        }
    }

    /** Cuts any fetch in progress. For when the user switches this off. */
    fun stop() {
        api.cutAll()
        images.cutAll()
    }

    /** Stops, and deletes every saved picture. Call off the main thread. */
    fun clear() {
        stop()
        runCatching { dir.listFiles() }.getOrNull().orEmpty().forEach { it.delete() }
        _version.update { it + 1 }
    }

    private fun round() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val window = (0 until KEEP_DAYS).map { today.minusDays(it.toLong()).toString() }
        val wanted = window.filter { !File(dir, "$it.img").isFile && !File(dir, "$it.none").exists() }
        if (wanted.isNotEmpty() && (dir.isDirectory || dir.mkdirs())) {
            val files = fileOfEachDay(wanted)
            val details = if (files.isEmpty()) emptyMap() else detailsOf(files.values.distinct())
            var unanswered = 0
            for (date in wanted) {
                if (!allowed()) return
                val title = files[date]
                val found = title?.let { details[it] }
                if (found == null) {
                    // A day that has passed and has no usable picture (a video, say) is not asked
                    // about again. Today's may simply not be there yet.
                    if (date != window.first()) mark(date)
                    continue
                }
                val status = IntArray(1)
                val bytes = try {
                    images.download(found.url, OWNER, allowed, status)
                } catch (e: SafeFetch.Refused) {
                    null
                }
                if (!allowed()) return
                if (bytes == null) {
                    if (status[0] in 400..499 && status[0] != 429) {
                        // Gone or refused for good (a deleted file, say): not asked for again.
                        mark(date)
                    } else if (++unanswered >= 2) {
                        // "Come back later", a server error, or an answer too large: tried again
                        // next round. Two of those, and this round stops asking.
                        break
                    }
                } else if (!Wallpaper.accepts(bytes)) {
                    mark(date)
                } else {
                    save(date, bytes, found.credit)
                }
                pause()
            }
        }
        if (allowed()) prune(window)
    }

    /** Which file is the picture of each of [dates]. Throws [IOException] if Commons could not be asked. */
    private fun fileOfEachDay(dates: List<String>): Map<String, String> {
        val titles = dates.joinToString("|") { "$TEMPLATE$it" }
        val answer = ask("action=query&format=json&formatversion=2&prop=images&imlimit=50&titles=${encode(titles)}")
        val pages = answer.optJSONObject("query")?.optJSONArray("pages") ?: return emptyMap()
        val found = HashMap<String, String>()
        for (page in pages.objects()) {
            val date = page.optString("title").removePrefix(TEMPLATE).takeIf { DATE.matches(it) && it in dates } ?: continue
            val file = page.optJSONArray("images")?.objects()?.map { it.optString("title") }
                ?.firstOrNull { it.startsWith("File:") && it.length <= MAX_TITLE_LENGTH } ?: continue
            found[date] = file
        }
        return found
    }

    private class Details(val url: String, val credit: String)

    /** Where the 1920-pixel version of each file is, and its credit line. */
    private fun detailsOf(files: List<String>): Map<String, Details> {
        val answer = ask(
            "action=query&format=json&formatversion=2&prop=imageinfo&iiprop=url%7Cmime%7Cextmetadata" +
                "&iiurlwidth=$WIDTH&iiextmetadatafilter=Artist%7CLicenseShortName%7CObjectName" +
                "&titles=${encode(files.joinToString("|"))}",
        )
        val found = HashMap<String, Details>()
        val pages = answer.optJSONObject("query")?.optJSONArray("pages") ?: return found
        for (page in pages.objects()) {
            val title = page.optString("title")
            if (title !in files) continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            // A video or a sound has a still frame or nothing worth showing: not a picture of the day here.
            val mime = info.optString("mime")
            if (mime.startsWith("video/") || mime.startsWith("audio/")) continue
            // The query part only carries Wikimedia's own campaign tags; the picture does not need it.
            val url = info.optString("thumburl").substringBefore('?')
            if (url.length > SafeFetch.MAX_URL_LENGTH || images.checked(url) == null) continue
            val meta = info.optJSONObject("extmetadata")
            fun field(name: String, max: Int) = plain(meta?.optJSONObject(name)?.optString("value").orEmpty(), max)
            val name = field("ObjectName", 100).ifEmpty {
                cleanText(title.removePrefix("File:").substringBeforeLast('.'), 100)
            }
            val artist = field("Artist", 80).ifEmpty { "author unknown" }
            val license = field("LicenseShortName", 40).ifEmpty { "licence on Wikimedia Commons" }
            found[title] = Details(url, cleanText("$name · $artist · $license · Wikimedia Commons", MAX_CREDIT))
        }
        return found
    }

    /** One question to the Commons API. Throws [IOException] if there is no usable answer. */
    private fun ask(query: String): JSONObject {
        val bytes = try {
            api.download("https://$API_HOST/w/api.php?$query", OWNER, allowed)
        } catch (e: SafeFetch.Refused) {
            null
        } ?: throw IOException("no answer")
        val answer = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("not an answer")
        }
        // MediaWiki reports most of its own trouble (overload, maintenance) as a normal answer
        // with an error in it. That is "try later", never "there is no picture".
        if (answer.has("error") || answer.optJSONObject("query") == null) throw IOException("no usable answer")
        return answer
    }

    private fun save(date: String, bytes: ByteArray, credit: String) {
        // Not after the user switched this off or cleared it while the picture was on its way.
        if (!allowed()) return
        // The credit goes in first: a picture is never on disk without it.
        if (!writeAtomically(File(dir, "$date.txt"), credit.toByteArray(Charsets.UTF_8))) return
        if (writeAtomically(File(dir, "$date.img"), bytes)) _version.update { it + 1 }
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val tmp = File(dir, "${target.name}.tmp")
        return try {
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            tmp.renameTo(target)
        } catch (e: IOException) {
            false
        } finally {
            tmp.delete()
        }
    }

    /** Notes that [date] has no picture to be had, so it is not asked about again. */
    private fun mark(date: String) {
        runCatching { File(dir, "$date.none").createNewFile() }
    }

    /** Keeps the newest [KEEP_DAYS] pictures, and only the notes for days still asked about. */
    private fun prune(window: List<String>) {
        val keep = savedDates().take(KEEP_DAYS).toSet()
        var removed = false
        for (file in runCatching { dir.listFiles() }.getOrNull().orEmpty()) {
            val date = file.name.substringBefore('.')
            val stays = when (file.name.substringAfter('.')) {
                "img", "txt" -> date in keep
                "none" -> date in window
                else -> false
            }
            if (!stays && file.delete() && file.name.endsWith(".img")) removed = true
        }
        if (removed) _version.update { it + 1 }
    }

    private fun pause() {
        runCatching { Thread.sleep(PAUSE_MS) }
    }

    private companion object {
        const val API_HOST = "commons.wikimedia.org"
        val IMAGE_HOSTS = setOf("upload.wikimedia.org", "thumb.wikimedia.org")
        const val USER_AGENT = "SlateLauncher/0.5 (Android TV screensaver; Picture of the Day)"
        const val OWNER = "potd"
        const val TEMPLATE = "Template:Potd/"
        val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        const val KEEP_DAYS = 7
        const val WIDTH = 1920
        const val MAX_ANSWER_BYTES = 256L * 1024
        const val MAX_TITLE_LENGTH = 400
        const val MAX_CREDIT = 280
        const val MAX_CREDIT_BYTES = 4L * 1024
        const val ROUND_GAP_MS = 60 * 60_000L
        const val MIN_GAP_MS = 60_000L
        const val PAUSE_MS = 3_000L

        fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

        fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

        private val TAG = Regex("<[^>]*>")
        private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);")
        private val SPACES = Regex("\\s+")

        /** Text with HTML in it, as one plain line: tags gone, the common entities decoded. */
        fun plain(html: String, max: Int): String {
            val text = TAG.replace(html.take(4_000), " ")
            val decoded = ENTITY.replace(text) { match ->
                when (val name = match.groupValues[1]) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> {
                        val code = if (name[1] == 'x' || name[1] == 'X') name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
                        if (code != null && Character.isValidCodePoint(code) && !Character.isISOControl(code)) {
                            String(Character.toChars(code))
                        } else {
                            ""
                        }
                    }
                }
            }
            // Cut with a mark, so a credit is never mistaken for being complete.
            val whole = cleanText(SPACES.replace(decoded, " "), 4_000)
            return if (whole.length <= max) whole else whole.takeWhole(max - 1).trimEnd() + "…"
        }
    }
}
