package app.slate.launcher.data

import android.Manifest
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.AbstractWindowedCursor
import android.database.ContentObserver
import android.database.Cursor
import android.database.CursorWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One entry of the TV's "watch next" list: something an app says is in progress or up next.
 *
 * Every field comes from another app and has been cut down to what is safe to show and use; see
 * [WatchNextRepository].
 */
data class WatchItem(
    /** The row's id in the TV's list, as text. Unique among the items shown together. */
    val key: String,
    val packageName: String,
    val title: String,
    /** Season and episode, where the app gives them. May be empty. */
    val episode: String,
    /** Where the poster is, or null if there is none Slate would fetch. Always https. */
    val posterUrl: String?,
    /** The poster's width divided by its height, as the app declares it. */
    val aspect: Float,
    /** How far through, from 0 to 1, or null if the app does not say. */
    val progress: Float?,
    /** What the app wants started when this is chosen. Never used as it stands: see [WatchNextRepository.open]. */
    val intentUri: String,
    /** The app's name as it appears on the home screen. Filled in by the view model. */
    val appName: String = "",
    /** How much is left to watch, in milliseconds, where the app says; at most [MAX_REMAINING_MS]. */
    val remainingMs: Long? = null,
)

/** Longer than this left to watch is taken for a mistake, and not shown. */
const val MAX_REMAINING_MS = 12 * 60 * 60_000L

/** A decoded poster and what it weighs. */
class Poster(val image: ImageBitmap, val bytes: Int)

/**
 * A picture to fill the screen behind the home screen while its entry has focus, with a dark
 * colour taken from it for the parts of the screen the picture does not cover.
 */
class Backdrop(val image: ImageBitmap, val tint: Int, val bytes: Int)

/**
 * Reads the TV's "watch next" list, fetches poster images for it, and opens its entries.
 *
 * Everything in that list is written by other apps, and this is the one part of Slate that uses
 * the network, so nothing is taken on trust:
 *
 *  - Text is cut to one short printable line. The list is read one app at a time, so however
 *    many rows an app writes, however large, and whatever it claims about when they were
 *    watched, it gets a few entries at most and cannot push other apps' entries out. An app
 *    whose rows are slow to read is asked after the others and not waited for. (What no
 *    reader can prevent: an app that keeps the TV's list itself busy slows it for everybody.)
 *  - A poster is only fetched from an https address with a plain host name, on the standard
 *    port, that is not on this TV or its local network, and only for an app that itself may
 *    use the network: otherwise an app without that permission could send data out by asking
 *    Slate to "fetch a poster" from an address of its choosing. Redirects are followed a few
 *    times, each checked the same way. Nothing is sent but the request itself: no cookies, no
 *    identifiers, a fixed client name.
 *  - The fetch is done by [SafeFetch], byte by byte, not by the system's web client: every part of the
 *    answer, from its first line on, is read under a fixed size, and the whole exchange under
 *    a fixed time after which the connection is cut. A server cannot keep a fetch open, nor
 *    make it hold more than those few megabytes.
 *  - What comes back goes through the same cleaning as any other image Slate did not make
 *    (see [Untrusted]) before the system decodes it.
 *  - Opening an entry never starts whatever the entry says. A new intent is built from the
 *    plain parts of the app's one, addressed to an exported activity of the very app that
 *    published the entry; failing that, the app is opened the ordinary way.
 *
 * [allowed] is false in safe mode: then nothing is read, watched or fetched. [visible] says
 * whether the home screen is showing: the list is not read while it is not.
 */
class WatchNextRepository(
    private val context: Context,
    private val allowed: () -> Boolean,
    private val visible: () -> Boolean,
) {

    private val pm = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    private val _items = MutableStateFlow<List<WatchItem>>(emptyList())
    val items: StateFlow<List<WatchItem>> = _items.asStateFlow()

    @Volatile
    private var enabled = false

    /** The apps whose entries may be shown: installed, on the home screen, not hidden. */
    @Volatile
    private var shown: Set<String> = emptySet()

    /** Only touched on the main thread. */
    private var observing = false

    /**
     * Set when the user's own doing asked for a read (coming back to the home screen, a
     * setting): the pause between reads starts short again, and one in progress is cut short.
     */
    @Volatile
    private var asked = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** How many notices of a change have come in. */
    private val notices = AtomicInteger()

    // No handler: the notice only has to be passed on, and need not trouble the main thread.
    private val observer = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            notices.incrementAndGet()
            refreshRequests.trySend(Unit)
        }
    }

    private val posters = object : LruCache<String, Poster>(POSTER_CACHE_KB) {
        // The address counts too: it can be long, and the picture behind it tiny.
        override fun sizeOf(key: String, value: Poster): Int = max(1, (value.bytes + key.length * 2) / 1024 + 1)
    }

    /**
     * The cleaned bytes of posters fetched lately, so that a screen-sized picture of the entry in
     * focus can be made without fetching it again. Nothing else is ever fetched for it.
     */
    private val cleaned = object : LruCache<String, Untrusted.CleanImage>(CLEANED_CACHE_KB) {
        override fun sizeOf(key: String, value: Untrusted.CleanImage): Int = value.bytes.size / 1024 + 1
    }

    private val backdrops = object : LruCache<String, Backdrop>(BACKDROP_CACHE_KB) {
        override fun sizeOf(key: String, value: Backdrop): Int = max(1, value.bytes / 1024 + 1)
    }

    /** One lock per app that has posters to fetch. Guarded by itself. */
    private val appGates = HashMap<String, Mutex>()

    /** Fetches posters: any public https host, pictures only. See [SafeFetch]. */
    private val fetcher = SafeFetch(scope, MAX_POSTER_BYTES, accept = "image/jpeg, image/png", maxLookups = MAX_LOOKUPS)

    /** Addresses that gave nothing usable, and until when (time since boot) not to ask again. */
    private val failures = HashMap<String, Long>()

    /** How many times in a row an address could not be reached. Guarded by [failures]. */
    private val unreached = HashMap<String, Int>()

    private val fetchGate = Semaphore(FETCHES_AT_ONCE)

    /** Decoding is memory-hungry: one poster at a time, however many have arrived. */
    private val decodeLock = Any()

    /** Asks for the row to be put together again from what is known. */
    private val rebuilds = Channel<Unit>(Channel.CONFLATED)

    init {
        // One worker reads the list; requests that pile up collapse into one. Whatever an app
        // does to the list, it is read at most once in a while, and never behind the user's
        // back. When notices of changes keep coming one after the other (an app rewriting its
        // rows without pause, say), the pause between reads grows, up to a minute; the user
        // coming back to the home screen ends it.
        scope.launch {
            var gap = MIN_GAP_MS
            for (request in refreshRequests) {
                if (asked) {
                    asked = false
                    gap = MIN_GAP_MS
                    // What woke an earlier pause, if anything, is used up with it.
                    wake.tryReceive()
                }
                if (!enabled || !allowed()) {
                    forget()
                    rebuilds.trySend(Unit)
                } else if (shown.isEmpty()) {
                    // Nothing to ask about yet (the apps are still being listed at start-up).
                    // No pause either: the real request is on its way.
                    rebuilds.trySend(Unit)
                } else if (visible()) {
                    val seen = notices.get()
                    runCatching { sweep() }
                    rebuilds.trySend(Unit)
                    // An app left to rest this round is asked again when its rest is over, even
                    // if nothing else happens by then.
                    restTimer?.cancel()
                    val restEnd = nextRestEnd
                    restTimer = if (restEnd == Long.MAX_VALUE) {
                        null
                    } else {
                        scope.launch {
                            delay((restEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 1)
                            refreshRequests.trySend(Unit)
                        }
                    }
                    delay(MIN_GAP_MS)
                    if (gap > MIN_GAP_MS && !asked) withTimeoutOrNull(gap - MIN_GAP_MS) { wake.receive() }
                    gap = if (notices.get() == seen) MIN_GAP_MS else min(gap * 2, MAX_GAP_MS)
                }
                // Not visible: left as it is. Coming back to the home screen asks again.
            }
        }
        // The one place the row is put together, so that an older state can never be shown
        // after a newer one. Answers that come in late, one after the other, collapse into a
        // few changes of the row.
        scope.launch {
            for (request in rebuilds) {
                _items.value = if (enabled && allowed()) picked() else emptyList()
                delay(REBUILD_GAP_MS)
            }
        }
    }

    /** Whether Android lets Slate read the list. Granted on the TV, or over ADB. */
    fun hasPermission(): Boolean =
        runCatching { context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Call on the main thread. Turns the row's data on or off; off also drops posters and fetches. */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            if (observing) {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
                observing = false
            }
            dropPosters()
            fetcher.cutAll()
        }
        refresh()
    }

    /** Call on the main thread. Which apps' entries may be shown. */
    fun setShown(packages: Set<String>) {
        if (packages == shown) return
        shown = packages
        // Not the user's doing as far as this can tell (an app can make itself come and go),
        // so it does not shorten the pause between reads, and counts towards lengthening it.
        notices.incrementAndGet()
        refresh(byUser = false)
    }

    /** Call on the main thread. Reads the list again, soon; at once if [byUser]. */
    fun refresh(byUser: Boolean = true) {
        // Here, not only when switched on: the permission may have been given since, and safe
        // mode may have ended.
        if (enabled && !observing && allowed() && hasPermission()) {
            observing = runCatching {
                context.contentResolver.registerContentObserver(LIST, true, observer)
            }.isSuccess
        }
        if (byUser) {
            asked = true
            wake.trySend(Unit)
        }
        refreshRequests.trySend(Unit)
    }

    fun dropPosters() {
        posters.evictAll()
        cleaned.evictAll()
        backdrops.evictAll()
        synchronized(failures) {
            failures.clear()
            unreached.clear()
        }
    }

    // ---- the list ----

    /** What the last finished read found for each app. */
    private val remembered = ConcurrentHashMap<String, List<Pair<Long, WatchItem>>>()

    /**
     * Apps whose last read took long. Nothing is held against them and nothing of theirs is
     * left out: they are asked after the others, a couple at a time, and not waited for. An
     * app is taken off again by its next read that is quick. Changed by the helpers.
     */
    private val suspects: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Until when (time since boot) such an app is left alone: for as long again as its last read took. */
    private val restUntil = ConcurrentHashMap<String, Long>()

    /** Apps asked without an ordering, which is the costly part of a read, and until when. */
    private val unsortedUntil = ConcurrentHashMap<String, Long>()

    /** Apps a read of which is out. Guarded by itself. */
    private val reading = HashSet<String>()

    /** Counts up whenever all that is known is dropped, so that a read begun before cannot bring it back. */
    private val generation = AtomicInteger()

    /** Where the last round ran out of helpers, to start from next time. Only touched by the worker. */
    private var resumeAt: String? = null
    private var resumeSuspectAt: String? = null

    /** When the first app left to rest in the last round may be asked again; Long.MAX_VALUE if none. Only touched by the worker. */
    private var nextRestEnd = Long.MAX_VALUE
    private var restTimer: Job? = null

    /** A round left apps unasked for want of a helper: the next helper to finish asks for another round. */
    @Volatile
    private var unasked = false

    /**
     * The threads on which the TV's list is asked. A question, once put, cannot be called back:
     * the list answers when it answers. So the worker does not ask it itself, and a read that
     * takes long is left to finish on its own. How many are out at once is held down by
     * [regularTurns] and [suspectTurns], and to one for each app by [reading].
     */
    private val readers = ThreadPoolExecutor(0, Int.MAX_VALUE, 30L, TimeUnit.SECONDS, SynchronousQueue()) { task ->
        Thread(task, "slate-watch-next").apply { isDaemon = true }
    }
    private val regularTurns = java.util.concurrent.Semaphore(MAX_REGULAR_READS)
    private val suspectTurns = java.util.concurrent.Semaphore(MAX_SUSPECT_READS)

    private fun forget() {
        generation.incrementAndGet()
        remembered.clear()
        suspects.clear()
        restUntil.clear()
        unsortedUntil.clear()
        resumeAt = null
        resumeSuspectAt = null
    }

    /**
     * Asks the list about every app, one question for each: the TV's list answers for a single
     * app when asked, and that is the defence against an app that misuses it. Whatever an app
     * does to its own rows (thousands of them, some too large to be read, dates far in the
     * future) costs that app its own entries at most.
     *
     * That includes time, as far as it is Slate's to give. Every app is read on a helper thread
     * of its own. The worker waits a moment ([APP_TIME_MS], once for all of them) so that the
     * row can be put together in one go; an answer that comes later is used when it comes. No
     * app is ever left out or marked down for being slow, because slowness says little about
     * whose fault it was: the list serves everybody, and one busy app makes every answer late.
     * An app whose last read took long is only asked after the others, on the few helpers set
     * aside for such apps, so that the ones for everybody else stay free; and it is asked
     * without an ordering for a while, which spares the list most of the work.
     *
     * What remains: [MAX_REGULAR_READS] apps that each answered quickly the time before and
     * then all take long at once hold every helper until the first of them is done.
     */
    private fun sweep() {
        // Before anything else: a round that ends early leaves no rest behind to wake up for.
        nextRestEnd = Long.MAX_VALUE
        if (!hasPermission()) {
            forget()
            return
        }
        val showing = shown
        remembered.keys.retainAll(showing)
        suspects.retainAll(showing)
        restUntil.keys.retainAll(showing)
        unsortedUntil.keys.retainAll(showing)
        val now = System.currentTimeMillis()
        val clock = SystemClock.elapsedRealtime()
        val round = generation.get()
        unasked = false

        val apps = showing.filter { it != context.packageName }.sorted()
        val (late, prompt) = apps.partition { it in suspects }
        // Apps that had entries first, so that they are not the ones to wait if helpers run out.
        val (known, others) = prompt.partition { remembered[it]?.isNotEmpty() == true }

        // The worker waits for these, and puts the row together itself afterwards.
        val waiting = AtomicBoolean(true)
        val waited = ArrayList<Future<*>>()
        val from = resumeAt
        resumeAt = null
        for (pkg in rotated(known + others, from)) {
            if (!enabled || !allowed()) break
            if (!ask(pkg, now, round, regularTurns, waiting, waited)) {
                resumeAt = pkg
                break
            }
        }
        val fromLate = resumeSuspectAt
        resumeSuspectAt = null
        for (pkg in rotated(late, fromLate)) {
            if (!enabled || !allowed()) break
            val rest = restUntil[pkg] ?: 0L
            if (rest > clock) {
                nextRestEnd = min(nextRestEnd, rest)
                continue
            }
            if (!ask(pkg, now, round, suspectTurns, null, null)) {
                resumeSuspectAt = pkg
                break
            }
        }

        val until = clock + APP_TIME_MS
        for (task in waited) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) break
            try {
                task.get(left, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // Not done in time, or failed: either way it is not waited for any longer.
            }
        }
        // From here on an answer has the row put together again itself.
        waiting.set(false)
    }

    /** [apps] starting from [first], if it is among them. */
    private fun rotated(apps: List<String>, first: String?): List<String> {
        val at = apps.indexOf(first)
        return if (at > 0) apps.drop(at) + apps.take(at) else apps
    }

    /**
     * Has a helper read [pkg]'s rows, unless a read of them is still out. False if there was no
     * helper to be had: every one of [turns] is out on a read that is taking long.
     */
    private fun ask(
        pkg: String,
        now: Long,
        round: Int,
        turns: java.util.concurrent.Semaphore,
        waiting: AtomicBoolean?,
        started: MutableList<Future<*>>?,
    ): Boolean {
        if (!synchronized(reading) { reading.add(pkg) }) return true
        var free = turns.tryAcquire()
        if (!free) {
            // Said first and then tried once more: a helper that finishes from now on sees it
            // and asks for another round, and one that finished just before has left its turn.
            unasked = true
            free = turns.tryAcquire()
        }
        if (!free) {
            synchronized(reading) { reading -= pkg }
            return false
        }
        val task = try {
            readers.submit {
                try {
                    read(pkg, now, round, waiting)
                } catch (e: Throwable) {
                    // One app's rows must not end the helper, whatever they hold.
                } finally {
                    synchronized(reading) { reading -= pkg }
                    turns.release()
                    if (unasked) refreshRequests.trySend(Unit)
                }
            }
        } catch (e: Throwable) {
            // No helper could be started (no memory for another thread, say).
            synchronized(reading) { reading -= pkg }
            turns.release()
            return false
        }
        started?.add(task)
        return true
    }

    /** Runs on a helper thread: reads one app's rows and keeps what it found, however long it took. */
    private fun read(pkg: String, now: Long, round: Int, waiting: AtomicBoolean?) {
        val sorted = (unsortedUntil[pkg] ?: 0L) <= SystemClock.elapsedRealtime()
        // Timed from when the list is there to be asked: starting the list's own process, when
        // it is not running (after boot, or after Android ended it for memory), is no app's doing.
        var started = 0L
        val entries = try {
            // A client of its own, which does not tie Slate's fate to the TV list's own process.
            val client = context.contentResolver.acquireUnstableContentProviderClient(LIST)
            if (client == null) {
                null
            } else {
                try {
                    started = SystemClock.elapsedRealtime()
                    testDelay(pkg)
                    entriesOf(client, pkg, now, sorted, started)
                } finally {
                    runCatching { client.close() }
                }
            }
        } catch (e: RemoteException) {
            // The list's own process went away. That says nothing about this app: what is
            // known of it stays.
            null
        } catch (e: Exception) {
            // Its rows cannot be read, so it has none to show.
            emptyList()
        } finally {
            val ended = SystemClock.elapsedRealtime()
            val took = ended - started
            if (started == 0L) {
                // Not asked at all: nothing learnt about this app.
            } else if (took > APP_TIME_MS) {
                Log.i("Slate", "watch next: reading the entries of $pkg took $took ms")
                suspects += pkg
                restUntil[pkg] = ended + min(took, MAX_REST_MS)
                if (sorted) unsortedUntil[pkg] = ended + UNSORTED_MS
            } else {
                suspects -= pkg
                restUntil.remove(pkg)
            }
        }
        if (entries == null || !enabled || !allowed() || pkg !in shown || round != generation.get()) return
        remembered[pkg] = entries
        // Dropped again if everything was dropped in that very moment.
        if (round != generation.get()) remembered.remove(pkg, entries)
        if (waiting?.get() != true) rebuilds.trySend(Unit)
    }

    private val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Test builds only, for trying out a slow list: a file `slow-read` in Slate's own files,
     * holding a package name and a number of milliseconds, makes every read of that app take
     * that much longer. A release build is not debuggable and never looks.
     */
    private fun testDelay(pkg: String) {
        if (!debuggable) return
        val words = runCatching { java.io.File(context.filesDir, "slow-read").readText().trim().split(' ') }.getOrNull() ?: return
        if (words.size == 2 && words[0] == pkg) words[1].toLongOrNull()?.let { Thread.sleep(it.coerceIn(0L, 120_000L)) }
    }

    /** The row as it stands: every app's newest entry, then every app's second, and so on. */
    private fun picked(): List<WatchItem> {
        // Fair shares, so that no app's entries can keep another's out of the row, nor take
        // all of its first places by claiming to be the most recent.
        val perApp = shown.mapNotNull { remembered[it]?.takeIf { entries -> entries.isNotEmpty() } }
        val picked = ArrayList<WatchItem>()
        val keys = HashSet<String>()
        var round = 0
        while (picked.size < MAX_ITEMS && round < MAX_PER_APP) {
            val layer = perApp.mapNotNull { it.getOrNull(round) }.sortedByDescending { it.first }
            if (layer.isEmpty()) break
            for (entry in layer) {
                if (picked.size < MAX_ITEMS && keys.add(entry.second.key)) picked += entry.second
            }
            round++
        }
        return picked
    }

    /**
     * The newest few entries of one app, each with the time it was last watched. [started] is
     * when the read began.
     */
    private fun entriesOf(
        client: ContentProviderClient,
        pkg: String,
        now: Long,
        sorted: Boolean,
        started: Long,
    ): List<Pair<Long, WatchItem>> {
        val uri = LIST.buildUpon().appendQueryParameter("package", pkg).build()
        // Only the columns that are used. Newest first where the list allows an ordering from
        // an ordinary app, and the app has not been slow lately; otherwise as they come.
        var ordered = false
        val cursor = (if (sorted) runCatching { client.query(uri, COLUMNS, null, null, "$COL_ENGAGED DESC") }.getOrNull() else null)
            ?.also { ordered = true }
            ?: client.query(uri, COLUMNS, null, null, null)
            ?: return emptyList()
        val found = ArrayList<Pair<Long, WatchItem>>()
        cursor.use { rows ->
            val columns = Columns(rows)
            if (columns.id < 0 || columns.pkg < 0 || columns.title < 0 || columns.intent < 0) return emptyList()
            var looked = 0
            // In order, the first few usable rows are the newest, and that is enough. Not in
            // order, the newest may be anywhere among the rows that are looked at.
            while ((!ordered || found.size < MAX_PER_APP) && looked < MAX_ROWS_PER_APP) {
                // Rows that came with the answer cost nothing more, however long the answer
                // took. Going past them sends the list off to work again, which is not done for
                // an app that has taken this long already: its turn ends with what there is.
                if (looked > 0 && !inHand(rows) && SystemClock.elapsedRealtime() - started > LIST_READ_TIME_MS) break
                looked++
                // A row too large to cross between the processes cannot be read; this app's
                // turn ends there, with what was read before it.
                val there = try {
                    rows.moveToNext()
                } catch (e: Exception) {
                    break
                }
                if (!there) break
                runCatching { item(rows, columns, pkg, now) }.getOrNull()?.let { found += it }
            }
        }
        return found.sortedByDescending { it.first }.take(MAX_PER_APP)
    }

    /** Whether the row after the current one is among those the list has already handed over. */
    private fun inHand(rows: Cursor): Boolean = runCatching {
        var inner: Cursor = rows
        while (inner is CursorWrapper) inner = inner.wrappedCursor ?: return false
        val window = (inner as? AbstractWindowedCursor)?.window ?: return false
        val next = rows.position + 1
        next >= window.startPosition && next < window.startPosition + window.numRows
    }.getOrDefault(false)

    /** Where each column is in this version of the list, or -1. */
    private class Columns(rows: Cursor) {
        val id = rows.getColumnIndex("_id")
        val pkg = rows.getColumnIndex("package_name")
        val title = rows.getColumnIndex("title")
        val season = rows.getColumnIndex("season_display_number")
        val episode = rows.getColumnIndex("episode_display_number")
        val poster = rows.getColumnIndex("poster_art_uri")
        val thumbnail = rows.getColumnIndex("thumbnail_uri")
        val aspect = rows.getColumnIndex("poster_art_aspect_ratio")
        val position = rows.getColumnIndex("last_playback_position_millis")
        val duration = rows.getColumnIndex("duration_millis")
        val intent = rows.getColumnIndex("intent_uri")
        val engaged = rows.getColumnIndex(COL_ENGAGED)
        val browsable = rows.getColumnIndex("browsable")
    }

    private fun text(rows: Cursor, column: Int, max: Int): String =
        if (column < 0 || rows.isNull(column)) "" else cleanText(rows.getString(column).orEmpty().take(max * 4), max)

    private fun number(rows: Cursor, column: Int): Long =
        if (column < 0 || rows.isNull(column)) 0L else rows.getLong(column)

    /** The row the cursor is on as an entry of [pkg] with the time it was last watched, or null if it is not one to show. */
    private fun item(rows: Cursor, columns: Columns, pkg: String, now: Long): Pair<Long, WatchItem>? {
        // The list was asked for this app's rows only; a version of it that answers with
        // everyone's is not believed.
        if (rows.getString(columns.pkg) != pkg) return null
        if (columns.browsable >= 0 && !rows.isNull(columns.browsable) && rows.getInt(columns.browsable) == 0) return null

        val title = text(rows, columns.title, MAX_TITLE)
        // Something to read, not only characters that take up no room.
        if (title.none { it.isLetterOrDigit() && it !in BLANK_LETTERS }) return null
        val intentUri = rows.getString(columns.intent).orEmpty()
        if (intentUri.isEmpty() || intentUri.length > MAX_INTENT_LENGTH) return null

        // Digits only: nothing that could pass for another app's name beside them.
        val season = text(rows, columns.season, 16).takeWhile { it in '0'..'9' }.take(MAX_NUMBER)
        val episode = text(rows, columns.episode, 16).takeWhile { it in '0'..'9' }.take(MAX_NUMBER)
        val episodeText = when {
            season.isNotEmpty() && episode.isNotEmpty() -> "S$season E$episode"
            episode.isNotEmpty() -> "E$episode"
            else -> ""
        }

        val position = number(rows, columns.position)
        val duration = number(rows, columns.duration)
        val progress = if (duration > 0 && position in 1..duration) position.toFloat() / duration else null
        val remaining = if (progress != null && duration - position in 1..MAX_REMAINING_MS) duration - position else null

        val address = sequenceOf(columns.poster, columns.thumbnail)
            .filter { it >= 0 && !rows.isNull(it) }
            .map { rows.getString(it).orEmpty() }
            .firstOrNull { it.length <= SafeFetch.MAX_URL_LENGTH && fetcher.checked(it) != null }
        // See the class comment: no fetching on behalf of an app that may not use the network itself.
        val mayFetch = address != null &&
            pm.checkPermission(Manifest.permission.INTERNET, pkg) == PackageManager.PERMISSION_GRANTED

        val aspect = when (if (columns.aspect < 0 || rows.isNull(columns.aspect)) -1 else rows.getInt(columns.aspect)) {
            1 -> 3f / 2f
            2 -> 4f / 3f
            3 -> 1f
            4 -> 2f / 3f
            5 -> 1f / 1.441f
            else -> 16f / 9f
        }

        // A time in the future is a claim to be first for ever; it counts as "just now".
        return min(number(rows, columns.engaged), now) to WatchItem(
            key = rows.getLong(columns.id).toString(),
            packageName = pkg,
            title = title,
            episode = episodeText,
            posterUrl = if (mayFetch) address else null,
            aspect = aspect,
            progress = progress,
            intentUri = intentUri,
            remainingMs = remaining,
        )
    }

    // ---- opening ----

    /**
     * Starts what [item] stands for. Returns whether something was started.
     *
     * The app's own description of what to start is only a suggestion: it is narrowed to an
     * exported activity of the app that published it. Anything else it asks for (another app's
     * activity, flags, attached data) is dropped, and if nothing of the app's answers, the app
     * is opened as from its card.
     */
    fun open(item: WatchItem): Boolean {
        val intent = runCatching { narrowed(item) }.getOrNull()
        if (intent != null && start(intent)) return true
        val plain = runCatching {
            pm.getLeanbackLaunchIntentForPackage(item.packageName) ?: pm.getLaunchIntentForPackage(item.packageName)
        }.getOrNull() ?: return false
        plain.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(plain)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun narrowed(item: WatchItem): Intent? {
        val asked = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME)
        val named = asked.component
        if (named != null && named.packageName != item.packageName) return null
        // A new intent with the plain parts of the app's one, and nothing else of it: whatever
        // else an intent can carry, now or in a later Android, is left behind.
        val intent = Intent(asked.action)
        intent.setDataAndType(asked.data, asked.type)
        asked.categories?.forEach { intent.addCategory(it) }
        asked.extras?.let { intent.putExtras(it) }
        intent.setPackage(item.packageName)
        if (named != null) intent.component = named
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val target = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return null
        if (target.packageName != item.packageName || !target.exported || !target.enabled) return null
        intent.setClassName(target.packageName, target.name)
        return intent
    }

    // ---- posters ----

    fun cachedPoster(url: String): Poster? = posters.get(url)

    /**
     * How long from now until [url] may be asked for again, in milliseconds, after it gave
     * nothing; null if nothing is held against it.
     */
    fun retryIn(url: String, pkg: String): Long? = synchronized(failures) {
        failures[failureKey(url, pkg)]?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
    }

    /**
     * Failures are kept for an address as given by one app. An app that names another app's
     * poster and then fails to fetch it (which it can arrange) holds up nobody else's attempt.
     */
    private fun failureKey(url: String, pkg: String): String = "$pkg\n$url"

    /**
     * The poster at [url], which app [pkg] gave, from memory or the network. Null if there is
     * none to be had right now.
     */
    suspend fun poster(url: String, pkg: String): Poster? {
        cachedPoster(url)?.let { return it }
        // Not while another app is in front: nothing is fetched behind the user's back.
        if (!enabled || !allowed() || !visible() || failedLately(url, pkg)) return null
        // One fetch per app at a time, so that an app whose server dawdles holds up its own
        // pictures and leaves the other slots to other apps.
        val own = synchronized(appGates) {
            if (appGates.size > MAX_APP_GATES) appGates.clear()
            appGates.getOrPut(pkg) { Mutex() }
        }
        return withContext(Dispatchers.IO) {
            own.withLock {
                fetchGate.withPermit {
                    cachedPoster(url) ?: run {
                        // Asked again: another card may have tried this very address while this one waited.
                        if (!enabled || !allowed() || failedLately(url, pkg)) return@run null
                        // (Nothing in here suspends, so a card that scrolled away meanwhile cannot
                        // turn a download that was merely abandoned into a recorded failure.)
                        var retryAfter = RETRY_REFUSED_MS
                        var image: Untrusted.CleanImage? = null
                        // runCatching also covers OutOfMemoryError: one bad image must not end the launcher.
                        val poster = runCatching {
                            val raw = try {
                                fetcher.download(url, pkg, active = { enabled && allowed() })
                            } catch (e: SafeFetch.Refused) {
                                null
                            } catch (e: IOException) {
                                // The network, not the picture: worth another go soon, and
                                // sooner the first time than the fourth.
                                retryAfter = -1L
                                null
                            }
                            image = raw?.let { Untrusted.cleanImage(it, MAX_POSTER_SIDE, MAX_POSTER_PIXELS, MAX_POSTER_BUFFERED) }
                            val decoded = synchronized(decodeLock) { scaled(image, POSTER_HEIGHT, MAX_POSTER_WIDTH, MAX_DECODED_PIXELS) }
                            decoded?.let { Poster(it.asImageBitmap(), it.byteCount) }
                        }.getOrNull()
                        // (Neither kept nor held against the address if the row was switched off
                        // under it: that is no answer either way.)
                        if (!enabled || !allowed()) {
                            // Nothing.
                        } else if (poster != null) {
                            posters.put(url, poster)
                            // Kept for a larger picture while this entry has focus; see backdrop().
                            image?.let { cleaned.put(url, it) }
                            synchronized(failures) { unreached.remove(failureKey(url, pkg)) }
                        } else {
                            val clock = SystemClock.elapsedRealtime()
                            val key = failureKey(url, pkg)
                            synchronized(failures) {
                                // Room is made by forgetting what has run out, not everything.
                                if (failures.size >= MAX_FAILURES_KEPT) failures.values.removeAll { it <= clock }
                                if (failures.size >= MAX_FAILURES_KEPT) failures.clear()
                                if (unreached.size >= MAX_FAILURES_KEPT) unreached.clear()
                                val wait = if (retryAfter >= 0) {
                                    unreached.remove(key)
                                    retryAfter
                                } else {
                                    val times = unreached[key] ?: 0
                                    unreached[key] = times + 1
                                    RETRY_UNREACHED_MS[min(times, RETRY_UNREACHED_MS.size - 1)]
                                }
                                failures[key] = clock + wait
                            }
                        }
                        poster
                    }
                }
            }
        }
    }

    private fun failedLately(url: String, pkg: String): Boolean = synchronized(failures) {
        val key = failureKey(url, pkg)
        val until = failures[key] ?: return false
        if (SystemClock.elapsedRealtime() < until) return true
        failures.remove(key)
        false
    }

    /**
     * A picture to fill the screen with while the entry with the poster at [url] has focus, or
     * null if there is none. Made from what was fetched for the poster, never fetched itself:
     * larger where the cleaned picture is still at hand, else the poster as it is.
     */
    suspend fun backdrop(url: String): Backdrop? = withContext(Dispatchers.IO) {
        backdrops.get(url)?.let { return@withContext it }
        if (!enabled || !allowed()) return@withContext null
        // runCatching also covers OutOfMemoryError: a picture that cannot be had is no backdrop,
        // and a larger one that cannot be made leaves the poster as it is.
        val large = runCatching {
            cleaned.get(url)?.let { image ->
                synchronized(decodeLock) { scaled(image, BACKDROP_HEIGHT, BACKDROP_MAX_WIDTH, BACKDROP_PIXELS) }
            }
        }.getOrNull()
        val made = runCatching {
            if (large != null) {
                Backdrop(large.asImageBitmap(), tintOf(large), large.byteCount)
            } else {
                posters.get(url)?.let { Backdrop(it.image, tintOf(it.image.asAndroidBitmap()), it.bytes) }
            }
        }.getOrNull() ?: return@withContext null
        // Only a larger picture is kept here. One made from the card's poster would hold on to
        // that poster after the posters' own room has let it go, and costs little to make again.
        if (large != null && enabled && allowed()) backdrops.put(url, made)
        made
    }

    /**
     * A dark colour that goes with [bitmap]: its most colourful part on average, darkened, for
     * the screen around and behind the picture.
     */
    private fun tintOf(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weights = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val pixel = small.getPixel(x, y)
                android.graphics.Color.colorToHSV(pixel, hsv)
                // Colourful, not too dark: what the eye takes for the picture's colour.
                val weight = 0.05 + hsv[1] * hsv[2]
                r += android.graphics.Color.red(pixel) * weight
                g += android.graphics.Color.green(pixel) * weight
                b += android.graphics.Color.blue(pixel) * weight
                weights += weight
            }
        }
        if (small !== bitmap) small.recycle()
        val average = android.graphics.Color.rgb((r / weights).toInt(), (g / weights).toInt(), (b / weights).toInt())
        android.graphics.Color.colorToHSV(average, hsv)
        hsv[1] = min(hsv[1], 0.65f)
        hsv[2] = 0.16f
        return android.graphics.Color.HSVToColor(hsv)
    }

    /**
     * [image] decoded and scaled to [height] pixels high, at most [maxWidth] wide, with no more
     * than [maxPixels] decoded on the way. Null if it is null or cannot be decoded.
     */
    private fun scaled(image: Untrusted.CleanImage?, height: Int, maxWidth: Int, maxPixels: Long): Bitmap? {
        image ?: return null
        val bytes = image.bytes
        // The size as it will be shown: orientations 5 to 8 turn the picture on its side.
        val turned = image.orientation in 5..8
        val fullWidth = if (turned) image.height else image.width
        val fullHeight = if (turned) image.width else image.height

        var sample = 1
        while (fullHeight / (sample * 2) >= height) sample *= 2
        // A very wide image keeps its width large after sampling by height; bound the total too.
        while ((fullWidth / sample).toLong() * (fullHeight / sample) > maxPixels) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = Untrusted.upright(
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null,
            image.orientation,
        )
        val scale = minOf(1f, height.toFloat() / decoded.height, maxWidth.toFloat() / decoded.width)
        return if (scale < 1f) {
            val out = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
            if (out !== decoded) decoded.recycle()
            out
        } else {
            decoded
        }
    }

    private companion object {
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"
        val LIST: Uri = Uri.parse("content://android.media.tv/watch_next_program")
        const val COL_ENGAGED = "last_engagement_time_utc_millis"

        /** The columns read, and no others. */
        val COLUMNS = arrayOf(
            "_id", "package_name", "title", "season_display_number", "episode_display_number",
            "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio", "last_playback_position_millis",
            "duration_millis", "intent_uri", COL_ENGAGED, "browsable",
        )

        /** Rows looked at for one app before giving up on finding its six. */
        const val MAX_ROWS_PER_APP = 40
        /** How long the worker waits for the answers of a round, and what counts as a quick read. */
        const val APP_TIME_MS = 300L

        /** After this long, a read is not sent back to the list for more rows. */
        const val LIST_READ_TIME_MS = 4_000L

        /** Reads out at once for apps that were quick the time before, and for those that were not. */
        const val MAX_REGULAR_READS = 8
        const val MAX_SUSPECT_READS = 2

        /** The longest an app that was slow is left alone before it is asked again. */
        const val MAX_REST_MS = 2 * 60_000L

        /** How long an app whose ordered read was slow is asked without an ordering. */
        const val UNSORTED_MS = 30 * 60_000L

        /** The least time between two changes of the row. */
        const val REBUILD_GAP_MS = 100L
        const val MIN_GAP_MS = 1_500L
        const val MAX_GAP_MS = 60_000L
        const val MAX_LOOKUPS = 6
        const val MAX_ITEMS = 16
        const val MAX_PER_APP = 6
        const val MAX_TITLE = 80
        const val MAX_NUMBER = 4
        const val MAX_INTENT_LENGTH = 4_000

        const val FETCHES_AT_ONCE = 3
        const val MAX_APP_GATES = 500

        /** Letters, by Unicode's reckoning, that draw nothing. */
        const val BLANK_LETTERS = "\u115F\u1160\u3164\uFFA0"
        const val RETRY_REFUSED_MS = 10 * 60_000L
        /** How long an address that could not be reached is left alone: longer each time in a row. */
        val RETRY_UNREACHED_MS = longArrayOf(5_000L, 10_000L, 20_000L, 30_000L)
        const val MAX_FAILURES_KEPT = 200

        const val MAX_POSTER_BYTES = 3L * 1024 * 1024
        const val MAX_POSTER_SIDE = 4096
        const val MAX_POSTER_PIXELS = 9_000_000L
        const val MAX_POSTER_BUFFERED = 2_500_000L
        const val MAX_DECODED_PIXELS = 1_500_000L

        /** Posters are kept at this height in pixels: a card on a 1080p screen, zoomed by focus. */
        const val POSTER_HEIGHT = 400
        const val MAX_POSTER_WIDTH = 1200
        /** Room for a full row of wide posters (about 1.1 MB each). */
        const val POSTER_CACHE_KB = 20 * 1024

        /** The picture behind the home screen: about 720p, which the screen's darkening hides well. */
        const val BACKDROP_HEIGHT = 720
        const val BACKDROP_MAX_WIDTH = 1280
        const val BACKDROP_PIXELS = 2_400_000L
        /** Room for three such pictures. */
        const val BACKDROP_CACHE_KB = 12 * 1024
        /** Cleaned poster files kept for making them; a typical poster file is 100 to 500 KB. */
        const val CLEANED_CACHE_KB = 8 * 1024
    }
}
