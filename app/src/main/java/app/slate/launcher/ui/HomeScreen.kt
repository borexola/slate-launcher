@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)

package app.slate.launcher.ui

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import app.slate.launcher.HomeActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.IntState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import app.slate.launcher.LauncherViewModel
import app.slate.launcher.ResolvedSection
import app.slate.launcher.SlateApp
import app.slate.launcher.data.AppArt
import app.slate.launcher.data.AppEntry
import app.slate.launcher.data.CardStyle
import app.slate.launcher.data.LabelMode
import app.slate.launcher.data.Limits
import app.slate.launcher.data.SectionStyle
import app.slate.launcher.data.WatchItem
import app.slate.launcher.system.SystemStatus
import app.slate.launcher.system.TvInput
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Space between cards, in dp. Half of it sits inside each card's focus area. */
private const val GAP = 16

/** How often a card asks for its picture, and how long it will wait for another go. */
private const val POSTER_TRIES = 4
private const val POSTER_RETRY_WITHIN_MS = 60_000L

/**
 * Screen care, for OLED screens, which wear unevenly under a picture that does not change: after
 * this long without a key press the home screen dims, and every [ORBIT_STEP_MS] everything on it
 * moves by a pixel or two, round a small circle ([ORBIT]).
 */
private const val IDLE_DIM_MS = 5 * 60_000L
private const val IDLE_DIM = 0.75f
private const val ORBIT_STEP_MS = 3 * 60_000L
private const val ORBIT_REACH_DP = 3
private val ORBIT = arrayOf(0 to 0, 2 to 0, 2 to 2, 0 to 2, -2 to 2, -2 to 0, -2 to -2, 0 to -2)

/** After this many apps opened, the hint about holding OK has had its chance and goes. */
private const val HINT_LAUNCHES = 5

/** What Android calls the permission to read the TV's "watch next" list. */
private const val WATCH_PERMISSION = "android.permission.READ_TV_LISTINGS"

/**
 * One card on the home screen: an app as it appears in a particular section. Cards of the
 * "continue watching" row are addressed the same way, under [WATCH_SECTION] and with the
 * entry's key in place of a package name.
 */
data class CardTarget(val sectionId: String, val pkg: String)

/**
 * Stands for the "continue watching" row wherever a section id is expected. It starts with a
 * character no section id can contain (ids are stripped of control characters), so it can never
 * be mistaken for a section, nor a section for it.
 */
private const val WATCH_SECTION = "\u0000watch-next"

/** A card's artwork, and whether loading has finished (it may finish with nothing). */
private class ArtLoad(val art: AppArt?, val done: Boolean)

/** What the home screen needs in order to address one card: give it focus, or scroll it into view. */
private class CardHandle(val focus: FocusRequester, val bring: BringIntoViewRequester)

/** Which part of the home screen has focus, as far as the picture behind it is concerned. */
private enum class Zone { NONE, TOP, WATCH, APPS }

/**
 * Scroll positions and focus bookkeeping for the home screen. Deliberately plain fields rather
 * than observable state: reading or writing them must never cause recomposition.
 */
@OptIn(ExperimentalFoundationApi::class)
private class HomeState {
    val listState = LazyListState()
    val rowStates = HashMap<String, LazyListState>()
    val customise = FocusRequester()
    val cards = HashMap<CardTarget, CardHandle>()

    /** The card that holds focus right now, if a card does. */
    var focusedCard: CardTarget? = null
    var cardHasFocus = false

    /**
     * The card the USER put focus on, with the remote or by an action that says where focus
     * should go. Android also moves focus by itself (when the focused card is removed it picks
     * another, usually the first on screen); those moves are not recorded here. So this is the
     * card focus belongs on whenever the list of apps changes. Null when the user's focus is in
     * the top bar.
     */
    var chosenCard: CardTarget? = null
    var chosenIndex = 0

    /** When a key from the remote last reached the home screen (uptime milliseconds). */
    var lastKeyAt = 0L

    /** Whether the launcher has put focus on a card at start-up yet. */
    var startFocusPlaced = false

    /**
     * The "continue watching" entry that last held focus, as its key and its place in the row,
     * until the user moves elsewhere with the remote. If that entry disappears (watched to the
     * end, say) focus goes to its neighbour.
     */
    var watchFocus: Pair<String, Int>? = null

    /** Whether the "continue watching" row had anything in it the last time the list was looked at. */
    var watchShown = false

    /**
     * The card that last held focus in each scrolling row (by section id), so that coming back to
     * a row lands on the card that was left. See [rememberedIn].
     */
    val rowFocus = HashMap<String, CardTarget>()

    /**
     * Where focus should land on entering the row [sectionId]: the card that last had focus
     * there, provided it is still in the row and on screen; otherwise wherever Android would put
     * it.
     *
     * This replaces Compose's `focusRestorer()`. That one pins the remembered card and crashes
     * ("Release should only be called once") on the next entry into the row if the card has been
     * removed in the meantime: an entry in "continue watching" that an app rewrote, or an app
     * that was uninstalled or updated. Nothing is pinned here, so nothing can go stale.
     */
    fun rememberedIn(sectionId: String): FocusRequester {
        val target = rowFocus[sectionId] ?: return FocusRequester.Default
        val handle = cards[target] ?: return FocusRequester.Default
        val row = rowStates[sectionId] ?: return FocusRequester.Default
        // A card can exist without being on screen (a lazy row prepares cards ahead of a scroll);
        // focus given to one of those would be invisible.
        if (row.layoutInfo.visibleItemsInfo.none { it.key == target.pkg }) return FocusRequester.Default
        return handle.focus
    }

    /**
     * Where focus is, and the "continue watching" entry it was last on (by key). Observable,
     * unlike the rest: the picture behind the home screen and the details above the row follow
     * them. Only the small parts that show those read them, so a move costs little.
     */
    val zone = mutableStateOf(Zone.NONE)
    val featuredKey = mutableStateOf<String?>(null)

    /** Whether the featured entry's details are the list's first item (see SectionList). */
    var heroShown = false

    /** True if focus arriving now is the direct result of a key press. */
    fun keyJustPressed() = SystemClock.uptimeMillis() - lastKeyAt < 300
}

@Composable
fun HomeScreen(vm: LauncherViewModel) {
    val config by vm.config.collectAsState()
    val sections by vm.sections.collectAsState()
    val watch by vm.watchItems.collectAsState()
    val inputs by vm.inputs.collectAsState()
    val wallpaperVersion by vm.wallpaperVersion.collectAsState()
    val artVersion by vm.repo.artVersion.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val safeMode by vm.safeMode.collectAsState()
    val tick by vm.statusTick.collectAsState()
    val saveFailed by vm.saveFailed.collectAsState()
    val context = LocalContext.current
    val appearance = config.appearance
    val palette = remember(appearance.accent) { paletteFor(appearance) }
    val state = remember { HomeState() }

    var menu by remember { mutableStateOf<CardTarget?>(null) }
    var moving by remember { mutableStateOf<CardTarget?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var inputsOpen by remember { mutableStateOf(false) }

    val hasApps = sections.any { it.apps.isNotEmpty() }
    val latestHasApps = rememberUpdatedState(hasApps)
    val latestSections = rememberUpdatedState(sections)
    val latestWatch = rememberUpdatedState(watch)
    val latestAnimations = rememberUpdatedState(appearance.animations)
    val latestColumns = rememberUpdatedState(appearance.gridColumns.coerceIn(Limits.gridColumns))

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    /** A card's position in the vertical list, and its position within its own section. */
    fun locate(target: CardTarget): Pair<Int, Int>? {
        val columns = latestColumns.value
        // The "continue watching" row, when there is one, is the first thing in the list: its
        // title, then the row itself.
        val watching = latestWatch.value
        if (target.sectionId == WATCH_SECTION) {
            val at = watching.indexOfFirst { it.key == target.pkg }
            // With the details above it, the list's very top is where the row is seen whole.
            return if (at >= 0) (if (state.heroShown) 0 else 1) to at else null
        }
        var item = if (watching.isEmpty()) 0 else if (state.heroShown) 3 else 2
        for (resolved in latestSections.value) {
            if (resolved.apps.isEmpty()) continue
            val section = resolved.section
            if (section.showTitle) item++
            val at = if (section.id == target.sectionId) {
                resolved.apps.indexOfFirst { it.packageName == target.pkg }
            } else {
                -1
            }
            if (section.style == SectionStyle.ROW) {
                if (at >= 0) return item to at
                item++
            } else {
                if (at >= 0) return (item + at / columns) to at
                item += (resolved.apps.size + columns - 1) / columns
            }
        }
        return null
    }

    /** Scrolls so that [target] is on screen. Bounded in time: a scroll must never stall focus. */
    suspend fun reveal(target: CardTarget) {
        val (item, at) = locate(target) ?: return
        try {
            withTimeoutOrNull(500) {
                state.listState.scrollToItem(item)
                withFrameNanos { }
                state.rowStates[target.sectionId]?.scrollToItem(at)
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
        repeat(2) { withFrameNanos { } }
    }

    /** Puts focus on one particular card, scrolling to it first if it is off screen. */
    suspend fun focusCard(target: CardTarget): Boolean {
        val startedAt = SystemClock.uptimeMillis()
        // Deliberate, so it is where focus belongs from now on.
        state.chosenCard = target
        locate(target)?.let { state.chosenIndex = it.second }
        if (state.cards[target] == null) {
            reveal(target)
            // Scrolling there takes a moment. If the user chose something else meanwhile (another
            // card, or the top bar), that stands. No request has gone out yet, so this cannot
            // be a row passing one on.
            if (state.lastKeyAt >= startedAt && state.chosenCard != target) {
                // The scrolling may have taken their choice off screen with it.
                try {
                    state.chosenCard?.let { chosen -> state.cards[chosen]?.bring?.bringIntoView() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The card left the screen in the meantime; nothing to scroll to.
                }
                return true
            }
        }
        // Asked twice on purpose. A row remembers which of its cards last had focus and sends
        // the first request arriving from outside to that card; once focus is inside the row,
        // the second request goes where it is meant to.
        repeat(2) {
            val handle = state.cards[target] ?: return false
            val askedAt = SystemClock.uptimeMillis()
            if (runCatching { handle.focus.requestFocus() }.isFailure) return false
            repeat(2) { withFrameNanos { } }
            // If the user pressed a key since this request and chose another card, leave it at
            // that. (A key from before the request does not count: the row's own redirect of
            // this request would otherwise be taken for the user's choice.)
            // Leaving the cards for the top bar (no chosen card) is a choice as well.
            val choseElsewhere = state.chosenCard != target && (state.cardHasFocus || state.chosenCard == null)
            if (state.lastKeyAt >= askedAt && choseElsewhere) return true
            // The first request may have been sent to another card of the same row, and that card
            // gaining focus can look like a choice. Say again which card is meant.
            state.chosenCard = target
            locate(target)?.let { state.chosenIndex = it.second }
            if (state.focusedCard == target && state.cardHasFocus) return true
        }
        return true
    }

    /** Puts focus on one entry of the "continue watching" row. */
    suspend fun focusWatch(target: CardTarget): Boolean =
        try {
            focusCard(target)
        } finally {
            // "The chosen card" is a notion for apps, whose list focus is kept in step with. An
            // entry of this row never is one, or a change to the apps would pull focus about.
            // (Also when this was cut short by a newer change.)
            if (state.chosenCard == target) state.chosenCard = null
        }

    /**
     * Puts focus where it starts: on the first "continue watching" entry if that row is there,
     * else on the first app, or on Customise when there are no cards at all.
     */
    suspend fun focusStart() {
        repeat(2) { withFrameNanos { } }
        val top = latestWatch.value.firstOrNull()?.let { CardTarget(WATCH_SECTION, it.key) }
        if (top != null && latestHasApps.value && focusWatch(top)) return
        val first = latestSections.value.firstOrNull { it.apps.isNotEmpty() }
            ?.let { CardTarget(it.section.id, it.apps[0].packageName) }
        if (first == null || !focusCard(first)) runCatching { state.customise.requestFocus() }
    }

    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.setWallpaper(uri) { message -> toast(message) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.addPhotos(uris) { message -> toast(message) }
    }
    // Android asks the user whether Slate may read the TV's "watch next" list.
    // One question at a time: a second request while the first is on screen is answered "no"
    // at once by Android, which would put a refusal on screen over the question itself.
    val askingWatch = remember { BooleanArray(1) }
    val watchPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askingWatch[0] = false
        if (granted || vm.mayReadWatchNext()) {
            vm.setWatchNext(true)
        } else {
            // After a refusal or two Android stops asking and answers "no" by itself.
            // One message, whether Android asked and was told no or did not ask at all (it
            // stops asking after a refusal or two): both ways out are in it.
            Toast.makeText(
                context,
                "Not allowed. Allow it under TV settings › Apps › Slate › Permissions, or from a " +
                    "computer: scripts/slate.sh watch-next",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(saveFailed) {
        if (saveFailed) toast("Settings could not be saved. The TV's storage may be full.")
    }

    // First focus, once the app list is known. Android puts focus on the first focusable thing
    // it finds (a top-bar button) as soon as the window appears, so this deliberately overrides
    // that, once, as soon as there is a card to focus.
    LaunchedEffect(loaded, hasApps) {
        if (!loaded || state.startFocusPlaced) return@LaunchedEffect
        focusStart()
        if (hasApps) {
            state.startFocusPlaced = true
            // The "continue watching" row may have turned up while focus was being put on the
            // first app; the effect that watches for it was not to act yet. Start at the top
            // after all, if the remote has not been touched.
            if (latestWatch.value.isNotEmpty() && state.lastKeyAt == 0L &&
                state.focusedCard?.sectionId != WATCH_SECTION
            ) {
                focusStart()
            }
        }
    }

    // Apps come and go (uninstalled, hidden, unpinned, renamed, reordered). Whenever that
    // happens, drop anything that pointed at a card which no longer exists, and make sure focus
    // is on the card the user chose, and that the card is on screen.
    LaunchedEffect(sections) {
        fun indexOf(target: CardTarget): Int =
            sections.firstOrNull { it.section.id == target.sectionId }
                ?.apps?.indexOfFirst { it.packageName == target.pkg } ?: -1
        menu?.let { if (indexOf(it) < 0) menu = null }
        moving?.let { if (indexOf(it) < 0) moving = null }

        // While a card is being moved, that card is by definition where focus belongs.
        // (An entry of the "continue watching" row is never the chosen card, but is noted as
        // one for the moment focus is being handed to it; that is not for this to act on.)
        val chosen = moving ?: state.chosenCard?.takeIf { it.sectionId != WATCH_SECTION } ?: return@LaunchedEffect
        val wanted: CardTarget? = if (indexOf(chosen) >= 0) {
            chosen
        } else {
            // It is gone: take the card now in its place, or the last one in that section.
            sections.firstOrNull { it.section.id == chosen.sectionId && it.apps.isNotEmpty() }?.let {
                CardTarget(it.section.id, it.apps[state.chosenIndex.coerceIn(0, it.apps.lastIndex)].packageName)
            }
        }

        repeat(2) { withFrameNanos { } }
        // The user may have moved on during those two frames; their choice stands.
        if ((moving ?: state.chosenCard) != chosen) return@LaunchedEffect
        if (wanted == null) {
            focusStart()
        } else if (state.focusedCard == wanted && state.cardHasFocus) {
            // Focus is already there (it never left, or Android happened to pick the same card).
            // Record it as the choice all the same, so the next change starts from this card.
            state.chosenCard = wanted
            state.chosenIndex = indexOf(wanted).coerceAtLeast(0)
            // The card may have moved out of view (renamed to the far end of a long row, or the
            // list shifted under it). Nothing else would scroll to it.
            try {
                state.cards[wanted]?.bring?.bringIntoView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The card left the screen in the meantime; nothing to scroll to.
            }
        } else if (!focusCard(wanted)) {
            focusStart()
        }
    }

    // The "continue watching" row changes by itself: entries come and go as things are watched.
    LaunchedEffect(watch) {
        val had = state.watchFocus
        val wasShown = state.watchShown
        val appeared = watch.isNotEmpty() && !wasShown
        state.watchShown = watch.isNotEmpty()
        // A row that comes back starts at its beginning, not where the last one was left.
        if (watch.isEmpty()) {
            state.rowStates.remove(WATCH_SECTION)
            state.rowFocus.remove(WATCH_SECTION)
        }
        if (had != null && watch.none { it.key == had.first }) {
            // The entry the user was on has gone. Focus belongs on the one now in its place.
            val since = SystemClock.uptimeMillis()
            repeat(2) { withFrameNanos { } }
            // (Forgotten only now: had this run been cut short by a newer change, that one
            // would still need to know where the user was. And only if it still says the same:
            // focus may have reached another entry meanwhile.)
            if (state.watchFocus == had) state.watchFocus = null
            // Unless the user has moved on in the meantime; their choice stands.
            if (state.lastKeyAt >= since || state.chosenCard != null) return@LaunchedEffect
            val next = watch.getOrNull(had.second.coerceIn(0, (watch.size - 1).coerceAtLeast(0)))
            if (next == null || !focusWatch(CardTarget(WATCH_SECTION, next.key))) focusStart()
        } else if (appeared && state.startFocusPlaced && state.lastKeyAt == 0L) {
            // The row turned up after the launcher had already put focus on the first app, and
            // the remote has not been touched yet: start at the top after all.
            focusStart()
        } else if (state.cardHasFocus) {
            // The entries put themselves in a new order (the one just watched moves to the
            // front), or the row went away from above the apps. Either can leave the focused
            // card off screen, and nothing else would scroll to it. Not when the row has just
            // appeared above an app: that would scroll the new row straight out of sight.
            val focused = state.focusedCard ?: return@LaunchedEffect
            val inRow = focused.sectionId == WATCH_SECTION
            if (!inRow && !(wasShown && watch.isEmpty())) return@LaunchedEffect
            repeat(2) { withFrameNanos { } }
            if (state.focusedCard != focused || !state.cardHasFocus) return@LaunchedEffect
            if (inRow) {
                val at = watch.indexOfFirst { it.key == focused.pkg }
                if (at >= 0) {
                    state.watchFocus = focused.pkg to at
                    // Entries slide to their new places, and for as long as one is sliding it
                    // still counts as being where it was. So whether the focused entry will be
                    // in view is read from where the row says it rests, and the row is moved
                    // to it directly if it will not be.
                    state.rowStates[WATCH_SECTION]?.let { row ->
                        val info = row.layoutInfo
                        val resting = info.visibleItemsInfo.firstOrNull { it.key == focused.pkg }
                        val inView = resting != null && resting.offset >= 0 &&
                            resting.offset + resting.size <= info.viewportEndOffset - info.afterContentPadding
                        if (!inView) {
                            // A card that sticks out at the far side is brought in at that
                            // side, by its own width; any other to the usual place at the start.
                            val room = info.viewportEndOffset - info.afterContentPadding
                            val offset = if (resting != null && resting.offset + resting.size > room) resting.size - room else 0
                            try {
                                withTimeoutOrNull(500) { row.scrollToItem(at, offset) }
                            } catch (e: CancellationException) {
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }
                }
            }
            try {
                state.cards[focused]?.bring?.bringIntoView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The card left the screen in the meantime; nothing to scroll to.
            }
        }
    }

    // Screen care (see IDLE_DIM_MS). When the user last did something here, whether the screen
    // is dimmed, and the key whose press woke it (its release is not passed on either).
    val care = appearance.screenCare
    val activeAt = remember { longArrayOf(SystemClock.uptimeMillis()) }
    var dimmed by remember { mutableStateOf(false) }
    val waking = remember { intArrayOf(-1) }
    val orbit = remember { mutableIntStateOf(0) }
    val panelOpen = rememberUpdatedState(settingsOpen || inputsOpen || menu != null)
    // Coming back to the home screen counts as doing something.
    LaunchedEffect(tick) {
        activeAt[0] = SystemClock.uptimeMillis()
        dimmed = false
    }
    LaunchedEffect(care) {
        if (!care) {
            dimmed = false
            orbit.intValue = 0
            return@LaunchedEffect
        }
        var stepAt = SystemClock.uptimeMillis() + ORBIT_STEP_MS
        while (true) {
            delay(10_000)
            val clock = SystemClock.uptimeMillis()
            // A panel in use is the user at work, in a window of its own whose keys this does not see.
            if (panelOpen.value || !HomeActivity.isShowing) activeAt[0] = clock
            if (!dimmed && clock - activeAt[0] > IDLE_DIM_MS) dimmed = true
            if (clock >= stepAt) {
                orbit.intValue = (orbit.intValue + 1) % ORBIT.size
                stepAt = clock + ORBIT_STEP_MS
            }
        }
    }

    // Home pressed while already home: close everything and return to the top.
    LaunchedEffect(Unit) {
        vm.homeSignal.collectLatest {
            activeAt[0] = SystemClock.uptimeMillis()
            dimmed = false
            menu = null
            moving = null
            settingsOpen = false
            inputsOpen = false
            try {
                withTimeoutOrNull(1_500) {
                    val firstId = latestSections.value.firstOrNull { it.apps.isNotEmpty() }?.section?.id
                    firstId?.let { state.rowStates[it] }?.scrollToItem(0)
                    state.rowStates[WATCH_SECTION]?.scrollToItem(0)
                    if (latestAnimations.value) state.listState.animateScrollToItem(0) else state.listState.scrollToItem(0)
                }
            } catch (e: CancellationException) {
                // A key press during the scroll interrupts it. That must not end this collector.
                currentCoroutineContext().ensureActive()
            }
            focusStart()
        }
    }

    // A home screen has nowhere to go back to; Back only ends a move in progress.
    BackHandler { moving = null }

    CompositionLocalProvider(LocalAppearance provides appearance, LocalPalette provides palette) {
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent {
                // Notes that a key arrived. Consumes nothing, except the press that wakes a
                // dimmed screen: that one only wakes it, and does not also open or move anything.
                state.lastKeyAt = SystemClock.uptimeMillis()
                activeAt[0] = state.lastKeyAt
                val key = it.nativeKeyEvent
                when {
                    dimmed && key.action == KeyEvent.ACTION_DOWN -> {
                        dimmed = false
                        waking[0] = key.keyCode
                        true
                    }
                    key.keyCode == waking[0] -> {
                        if (key.action == KeyEvent.ACTION_UP) waking[0] = -1
                        true
                    }
                    else -> false
                }
            },
        ) {
            Box(Modifier.fillMaxSize().orbiting(orbit, behind = true)) {
                Background(appearance, wallpaperVersion, safeMode)
                FeaturedBehind(state, watch, appearance.featuredTitle)
            }
            Column(Modifier.fillMaxSize().orbiting(orbit)) {
                TopBar(
                    state = state,
                    tick = tick,
                    showInputs = inputs.isNotEmpty(),
                    onInputs = { inputsOpen = true },
                    onSystem = { if (!SystemStatus.openSystemSettings(context)) toast("Could not open the TV's settings") },
                    onCustomise = { settingsOpen = true },
                )
                if (safeMode) {
                    Notice(
                        "Safe mode. Slate failed to start several times in a row, so your settings were set " +
                            "aside and artwork is switched off. Open Customise › System to leave safe mode.",
                    )
                }
                when {
                    hasApps -> SectionList(
                        sections = sections,
                        watch = watch,
                        tick = tick,
                        onHoldWatch = { toast("Press OK to open it. These entries have no options.") },
                        onOpenWatch = { item ->
                            // (The app's name, not the entry's title, which is the app's own wording.)
                            if (!vm.openWatchItem(item)) toast("Could not open ${item.appName}")
                        },
                        state = state,
                        artVersion = artVersion,
                        moving = moving,
                        onOpen = { app ->
                            if (moving != null) {
                                moving = null
                            } else if (!vm.launch(app)) {
                                toast("Could not open ${app.label}")
                            } else if (!config.menuUsed && config.launchesSeen < HINT_LAUNCHES) {
                                vm.updateConfig { it.copy(launchesSeen = it.launchesSeen + 1) }
                            }
                        },
                        onMenu = { target ->
                            if (moving == null) {
                                menu = target
                                // The hint that says how to get here has done its job.
                                if (!config.menuUsed) vm.updateConfig { it.copy(menuUsed = true) }
                            }
                        },
                        onMove = { target, delta -> vm.moveApp(target.sectionId, target.pkg, delta) },
                        onMoveDone = { moving = null },
                    )
                    loaded -> Notice(
                        "Nothing to show yet. Open Customise › Home layout and turn on " +
                            "“Include all apps” for a section, or un-hide apps under Customise › Apps.",
                    )
                }
            }
            when {
                moving != null -> Hint("Arrows to move  ·  OK or Back when done", Modifier.align(Alignment.BottomCenter).orbiting(orbit))
                // Shown until it has been done once: nothing else on screen gives it away.
                hasApps && !config.menuUsed && config.launchesSeen < HINT_LAUNCHES && menu == null && !settingsOpen && !inputsOpen ->
                    Hint("Hold OK on an app for its options", Modifier.align(Alignment.BottomCenter).orbiting(orbit))
            }
            // Read only when drawn, so that fading does not rebuild the screen.
            val dim by animateFloatAsState(if (dimmed && care) IDLE_DIM else 0f, tween(if (dimmed) 2_500 else 200), label = "idleDim")
            Box(Modifier.fillMaxSize().drawBehind { if (dim > 0f) drawRect(Color.Black.copy(alpha = dim)) })
        }

        val menuTarget = menu
        val menuApp = menuTarget?.let { target ->
            sections.firstOrNull { it.section.id == target.sectionId }
                ?.apps?.firstOrNull { it.packageName == target.pkg }
        }
        if (menuTarget != null && menuApp != null) {
            AppMenu(
                vm = vm,
                app = menuApp,
                config = config,
                onMove = {
                    menu = null
                    moving = menuTarget
                },
                onClose = { menu = null },
            )
        }

        if (settingsOpen) {
            SettingsPanel(
                vm = vm,
                onPickWallpaper = {
                    try {
                        wallpaperPicker.launch("image/*")
                    } catch (e: ActivityNotFoundException) {
                        toast("No file picker on this device. Push the image over ADB instead.")
                    }
                },
                onPickPhotos = {
                    try {
                        photoPicker.launch("image/*")
                    } catch (e: ActivityNotFoundException) {
                        toast("No file picker on this device. From a computer, run: scripts/slate.sh photos add <folder>")
                    }
                },
                onAskWatchPermission = {
                    if (!askingWatch[0]) {
                        askingWatch[0] = true
                        try {
                            watchPermission.launch(WATCH_PERMISSION)
                        } catch (e: Exception) {
                            // No permission screen on this device.
                            askingWatch[0] = false
                            toast("This TV cannot ask here. From a computer, run: scripts/slate.sh watch-next")
                        }
                    }
                },
                onClose = { settingsOpen = false },
            )
        }

        if (inputsOpen) {
            InputsSheet(
                inputs = inputs,
                onOpen = { input ->
                    inputsOpen = false
                    if (!SystemStatus.openInput(context, input)) toast("Could not switch to ${input.label}")
                },
                onClose = { inputsOpen = false },
            )
        }
    }
}

/** The current time, refreshed by the system's minute tick, clock changes, and on resume. */
@Composable
private fun rememberNow(tick: Int): LocalDateTime {
    val appContext = LocalContext.current.applicationContext
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(tick) { now = LocalDateTime.now() }
    DisposableEffect(appContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                now = LocalDateTime.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        onDispose {
            if (registered) runCatching { appContext.unregisterReceiver(receiver) }
        }
    }
    return now
}

/**
 * The picture of the featured "continue watching" entry behind the home screen: while focus is
 * on that row, or above it with the row in view. Its own composable, so that focus moving about
 * only redraws this.
 */
@Composable
private fun FeaturedBehind(state: HomeState, watch: List<WatchItem>, on: Boolean) {
    val atTop by remember { derivedStateOf { state.listState.firstVisibleItemIndex == 0 } }
    val zone = state.zone.value
    val featured = if (on) watch.firstOrNull { it.key == state.featuredKey.value } ?: watch.firstOrNull() else null
    FeaturedBackdrop(featured, show = featured != null && (zone == Zone.WATCH || (zone != Zone.APPS && atTop)))
}

/** Where screen care has moved things to just now, in dp. */
private fun orbitOffset(orbit: IntState): Pair<Int, Int> = ORBIT[orbit.intValue % ORBIT.size]

/**
 * Moves this by screen care's current offset. Read when drawn, so a step costs one redraw.
 * Something [behind] everything fills the screen: it is enlarged by a little more than the
 * offset, so that moving it never shows an edge.
 */
private fun Modifier.orbiting(orbit: IntState, behind: Boolean = false): Modifier = graphicsLayer {
    val (x, y) = orbitOffset(orbit)
    if (behind) {
        val grow = 1f + 2 * ORBIT_REACH_DP.dp.toPx() / size.minDimension.coerceAtLeast(1f)
        scaleX = grow
        scaleY = grow
    }
    translationX = x.dp.toPx()
    translationY = y.dp.toPx()
}

@Composable
private fun TopBar(
    state: HomeState,
    tick: Int,
    showInputs: Boolean,
    onInputs: () -> Unit,
    onSystem: () -> Unit,
    onCustomise: () -> Unit,
) {
    val appearance = LocalAppearance.current
    val palette = LocalPalette.current
    val now = rememberNow(tick)
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) }
    val timeFormat = remember(appearance.clock24h, locale) {
        DateTimeFormatter.ofPattern(if (appearance.clock24h) "HH:mm" else "h:mm", locale)
    }
    val edge = appearance.edgePadding.coerceIn(Limits.edgePadding)
    val onPillFocus = {
        state.zone.value = Zone.TOP
        state.focusedCard = null
        state.cardHasFocus = false
        // Only when the user went there; Android parking focus here by itself changes nothing.
        if (state.keyJustPressed()) {
            state.chosenCard = null
            state.watchFocus = null
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = edge.dp, end = (edge - 14).coerceAtLeast(0).dp)
            .padding(top = 26.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The time, and the date under it, at the left; status and buttons at the right.
        Column {
            if (appearance.showClock) {
                Label(now.format(timeFormat), size = 26f, weight = FontWeight.Light)
            }
            if (appearance.showDate) {
                Label(now.format(dateFormat), size = 12.5f, color = palette.textDim)
            }
        }
        Spacer(Modifier.width(16.dp))
        // What room is left once the time and the buttons have theirs, the chips at its right.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            StatusChips(appearance.showMemory, appearance.showProcessor, appearance.showStorage, appearance.showNetwork)
        }
        Spacer(Modifier.width(14.dp))
        // Grouped so that Left and Right stay among the buttons instead of dropping into the cards.
        Row(Modifier.keepHorizontalFocus(), verticalAlignment = Alignment.CenterVertically) {
            if (showInputs) Pill("Inputs", onFocus = onPillFocus, onClick = onInputs)
            Pill("TV settings", onFocus = onPillFocus, onClick = onSystem)
            Pill("Customise", Modifier.focusRequester(state.customise), onFocus = onPillFocus, onClick = onCustomise)
        }
    }
}

/**
 * Left and right stop at the ends of a row instead of jumping to some other row. With [onEnter],
 * focus arriving in the row from outside goes where that says (see [HomeState.rememberedIn]).
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.keepHorizontalFocus(onEnter: (() -> FocusRequester)? = null): Modifier = this
    .focusProperties {
        if (onEnter != null) enter = { onEnter() }
        exit = { direction ->
            if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
                FocusRequester.Cancel
            } else {
                FocusRequester.Default
            }
        }
    }
    .focusGroup()

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun SectionList(
    sections: List<ResolvedSection>,
    watch: List<WatchItem>,
    tick: Int,
    onOpenWatch: (WatchItem) -> Unit,
    onHoldWatch: () -> Unit,
    state: HomeState,
    artVersion: Int,
    moving: CardTarget?,
    onOpen: (AppEntry) -> Unit,
    onMenu: (CardTarget) -> Unit,
    onMove: (CardTarget, Int) -> Unit,
    onMoveDone: () -> Unit,
) {
    val appearance = LocalAppearance.current
    val density = LocalDensity.current
    val listState = state.listState
    val visible = sections.filter { it.apps.isNotEmpty() }
    val edgePadding = appearance.edgePadding.coerceIn(Limits.edgePadding)
    val edge = (edgePadding - GAP / 2).coerceAtLeast(0)
    val columns = appearance.gridColumns.coerceIn(Limits.gridColumns)
    val cardWidth = appearance.cardWidth.coerceIn(Limits.cardWidth)
    val iconStyle = appearance.cardStyle == CardStyle.ICON
    val rowCardWidth = (if (iconStyle) cardWidth * 0.62f else cardWidth.toFloat()) + GAP

    // With the featured details above the "continue watching" row, focus on that row shows the
    // list from its very top (see WatchCard): the usual scrolling to a fixed height stands aside.
    val pivot = remember { PivotSpec(0.14f) { state.heroShown && state.zone.value == Zone.WATCH } }
    val heroOn = appearance.featuredTitle && watch.isNotEmpty()
    SideEffect { state.heroShown = heroOn }
    val heroHeight = (LocalConfiguration.current.screenHeightDp * 0.30f).dp
    val margin = remember(edge, density) { MarginSpec(with(density) { edge.dp.toPx() }) }

    // A lazy list keeps its first visible item in place when items are added above it. At the
    // very top that would hide a section which has just appeared (the first favourite, say), so
    // there the list is told to stay at the top instead.
    // (The "continue watching" row counts as well: it appears above everything else.)
    val structure = (if (watch.isEmpty()) emptyList() else listOf(WATCH_SECTION to heroOn)) +
        visible.map { it.section.id to it.section.showTitle }
    // The row is taller than a row of apps, so that posters (which are narrow) stay legible.
    val watchHeight = cardWidth * 0.75f

    val lastStructure = remember { arrayOf(structure) }
    if (lastStructure[0] != structure) {
        SideEffect {
            val before = lastStructure[0]
            lastStructure[0] = structure
            // Except when it is the "continue watching" row (or its details) that comes or goes
            // above an app in focus: then the list keeps that app where it is, or it could be
            // pushed below the screen's edge. The row is there to be seen on the way up.
            val watchPart = { it: List<Pair<String, Boolean>> -> it.firstOrNull()?.takeIf { part -> part.first == WATCH_SECTION } }
            val aroundApp = watchPart(before) != watchPart(structure) && state.zone.value == Zone.APPS && state.cardHasFocus
            if (listState.firstVisibleItemIndex == 0 && !aroundApp) {
                listState.requestScrollToItem(0, listState.firstVisibleItemScrollOffset)
            }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 72.dp),
        ) {
            if (watch.isNotEmpty()) {
                // These keys cannot collide with a section's: those all begin with "title:",
                // "row:" or "grid:".
                if (heroOn) {
                    item(key = "watch:hero", contentType = "hero") {
                        val featured = watch.firstOrNull { it.key == state.featuredKey.value } ?: watch.first()
                        FeaturedDetails(featured, heroHeight, edgePadding, appearance.clock24h)
                    }
                }
                item(key = "watch:title", contentType = "title") {
                    Label(
                        text = "CONTINUE WATCHING",
                        modifier = Modifier.padding(start = edgePadding.dp, end = edgePadding.dp, top = 18.dp, bottom = 2.dp),
                        size = 11.5f,
                        color = LocalPalette.current.textDim,
                        weight = FontWeight.Medium,
                        spacing = 1.4f,
                    )
                }
                item(key = "watch:row", contentType = "watch") {
                    val rowState = remember { state.rowStates.getOrPut(WATCH_SECTION) { LazyListState() } }
                    // A lazy row keeps its first visible entry in place when entries are added
                    // before it. New entries arrive at the front of this row, so while it
                    // stands at its start it is told to stay there, or the newest entry would
                    // sit out of sight to the left.
                    val firstKey = watch.first().key
                    val lastFirst = remember { arrayOfNulls<String>(1) }
                    if (lastFirst[0] != firstKey) {
                        SideEffect {
                            lastFirst[0] = firstKey
                            if (rowState.firstVisibleItemIndex == 0 && rowState.firstVisibleItemScrollOffset == 0) {
                                rowState.requestScrollToItem(0)
                            }
                        }
                    }
                    CompositionLocalProvider(LocalBringIntoViewSpec provides margin) {
                        Box(Modifier.fillMaxWidth().keepHorizontalFocus { state.rememberedIn(WATCH_SECTION) }) {
                            LazyRow(
                                state = rowState,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = edge.dp),
                            ) {
                                itemsIndexed(watch, key = { _, item -> item.key }) { index, item ->
                                    WatchCard(
                                        item = item,
                                        index = index,
                                        retry = tick,
                                        imageHeight = watchHeight,
                                        state = state,
                                        modifier = if (appearance.animations) Modifier.animateItem() else Modifier,
                                        onOpen = { onOpenWatch(item) },
                                        onHold = onHoldWatch,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            visible.forEach { resolved ->
                val section = resolved.section
                if (section.showTitle) {
                    item(key = "title:${section.id}", contentType = "title") {
                        Label(
                            text = section.title.uppercase(),
                            modifier = Modifier.padding(start = edgePadding.dp, end = edgePadding.dp, top = 18.dp, bottom = 2.dp),
                            size = 11.5f,
                            color = LocalPalette.current.textDim,
                            weight = FontWeight.Medium,
                            spacing = 1.4f,
                        )
                    }
                }
                if (section.style == SectionStyle.ROW) {
                    item(key = "row:${section.id}", contentType = "row") {
                        val rowState = remember(section.id) { state.rowStates.getOrPut(section.id) { LazyListState() } }
                        CompositionLocalProvider(LocalBringIntoViewSpec provides margin) {
                            Box(Modifier.fillMaxWidth().keepHorizontalFocus { state.rememberedIn(section.id) }) {
                                LazyRow(
                                    state = rowState,
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = edge.dp),
                                ) {
                                    itemsIndexed(resolved.apps, key = { _, app -> app.packageName }) { index, app ->
                                        val target = CardTarget(section.id, app.packageName)
                                        AppCard(
                                            app = app,
                                            target = target,
                                            index = index,
                                            state = state,
                                            artVersion = artVersion,
                                            moving = moving == target,
                                            modifier = (if (appearance.animations) Modifier.animateItem() else Modifier)
                                                .width(rowCardWidth.dp),
                                            onOpen = { onOpen(app) },
                                            onMenu = { onMenu(target) },
                                            onMove = { dx, _ ->
                                                val to = index + dx
                                                if (dx != 0 && to in resolved.apps.indices) {
                                                    // Hold the row still; otherwise it scrolls along with the
                                                    // moved card and the card appears not to move at all.
                                                    rowState.requestScrollToItem(
                                                        rowState.firstVisibleItemIndex,
                                                        rowState.firstVisibleItemScrollOffset,
                                                    )
                                                    onMove(target, dx)
                                                }
                                            },
                                            onMoveDone = onMoveDone,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val rows = resolved.apps.chunked(columns)
                    val count = resolved.apps.size
                    itemsIndexed(
                        items = rows,
                        key = { rowIndex, _ -> "grid:${section.id}:$rowIndex" },
                        contentType = { _, _ -> "grid" },
                    ) { rowIndex, rowApps ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = edge.dp).keepHorizontalFocus()) {
                            rowApps.forEachIndexed { column, app ->
                                key(app.packageName) {
                                    val index = rowIndex * columns + column
                                    val target = CardTarget(section.id, app.packageName)
                                    AppCard(
                                        app = app,
                                        target = target,
                                        index = index,
                                        state = state,
                                        artVersion = artVersion,
                                        moving = moving == target,
                                        modifier = Modifier.weight(1f),
                                        onOpen = { onOpen(app) },
                                        onMenu = { onMenu(target) },
                                        onMove = { dx, dy ->
                                            val to = when {
                                                dy == 0 -> index + dx
                                                (index + dy * columns) in 0 until count -> index + dy * columns
                                                // Down from the row above a short last row lands at its end.
                                                dy > 0 && index / columns < (count - 1) / columns -> count - 1
                                                else -> index
                                            }
                                            if (to != index && to in 0 until count) onMove(target, to - index)
                                        },
                                        onMoveDone = onMoveDone,
                                    )
                                }
                            }
                            repeat(columns - rowApps.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCard(
    app: AppEntry,
    target: CardTarget,
    index: Int,
    state: HomeState,
    artVersion: Int,
    moving: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onMove: (dx: Int, dy: Int) -> Unit,
    onMoveDone: () -> Unit,
) {
    val appearance = LocalAppearance.current
    val palette = LocalPalette.current
    val repo = (LocalContext.current.applicationContext as SlateApp).apps
    val style = appearance.cardStyle

    var focused by remember { mutableStateOf(false) }
    val cached = repo.cachedArt(app, style)
    val artLoad by produceState(ArtLoad(cached, cached != null), app.packageName, style, artVersion) {
        value = ArtLoad(repo.loadArt(app, style), done = true)
    }
    // Left-to-right languages lay rows out from the left; right-to-left ones from the right.
    val towardsEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1

    val duration = if (appearance.animations) 160 else 0
    val scale by animateFloatAsState(
        targetValue = if (focused) appearance.focusScale.coerceIn(Limits.focusScale) / 100f else 1f,
        animationSpec = tween(duration),
        label = "cardScale",
    )
    val shade by animateFloatAsState(
        targetValue = if (focused || !appearance.dimUnfocused) 0f else 0.34f,
        animationSpec = tween(duration),
        label = "cardShade",
    )
    val shape = RoundedCornerShape(appearance.cornerRadius.coerceIn(Limits.cornerRadius).dp)

    val bring = remember { BringIntoViewRequester() }
    val requester = remember { FocusRequester() }
    val handle = remember { CardHandle(requester, bring) }
    val isMoving = rememberUpdatedState(moving)
    val move = rememberUpdatedState(onMove)
    val moveDone = rememberUpdatedState(onMoveDone)
    val latestIndex = rememberUpdatedState(index)

    // Lets the home screen hand focus to this card by name.
    DisposableEffect(target) {
        state.cards[target] = handle
        onDispose {
            if (state.cards[target] === handle) state.cards.remove(target)
        }
    }
    SideEffect {
        if (state.chosenCard == target) state.chosenIndex = index
    }

    // While being moved, the card keeps focus and stays on screen as its position changes.
    LaunchedEffect(moving, index) {
        if (moving) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            try {
                bring.bringIntoView()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The card left the screen mid-move; nothing to scroll to.
            }
        }
    }

    Column(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .bringIntoViewRequester(bring)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    state.zone.value = Zone.APPS
                    state.focusedCard = target
                    state.cardHasFocus = true
                    state.rowFocus[target.sectionId] = target
                    if (state.keyJustPressed()) {
                        state.chosenCard = target
                        state.chosenIndex = latestIndex.value
                        state.watchFocus = null
                    }
                } else if (state.focusedCard == target) {
                    state.cardHasFocus = false
                }
            }
            .onPreviewKeyEvent { event ->
                if (!isMoving.value) return@onPreviewKeyEvent false
                val key = event.nativeKeyEvent
                val down = key.action == KeyEvent.ACTION_DOWN
                when {
                    key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) move.value(-towardsEnd, 0); true }
                    key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) move.value(towardsEnd, 0); true }
                    key.keyCode == KeyEvent.KEYCODE_DPAD_UP -> { if (down) move.value(0, -1); true }
                    key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) move.value(0, 1); true }
                    isConfirmKey(key.keyCode) -> { if (!down) moveDone.value(); true }
                    key.keyCode == KeyEvent.KEYCODE_MENU -> true
                    else -> false
                }
            }
            .tvClickable(onLongClick = onMenu, onClick = onOpen)
            .padding(horizontal = (GAP / 2).dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val loaded = artLoad.art
        // No artwork to show (none exists, it could not be read, or safe mode): show the name.
        val nameOnly = loaded == null && artLoad.done
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (style == CardStyle.ICON) 1f else 16f / 9f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(palette.surface)
                // Unfocused cards are darkened with a scrim rather than made translucent: it is
                // cheaper to draw and keeps cards solid over a wallpaper.
                .drawWithContent {
                    drawContent()
                    if (shade > 0f) drawRect(Color.Black.copy(alpha = shade))
                }
                .then(
                    if ((focused && appearance.focusBorder) || moving) {
                        Modifier.border(2.dp, palette.accent, shape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                nameOnly -> Label(
                    text = app.label,
                    modifier = Modifier.padding(horizontal = 10.dp),
                    size = 12.5f,
                    maxLines = 2,
                    align = TextAlign.Center,
                )
                loaded == null -> Unit
                loaded.isBanner -> Image(
                    bitmap = loaded.image,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                style == CardStyle.ICON -> Image(
                    bitmap = loaded.image,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxSize(0.58f),
                )
                else -> Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        bitmap = loaded.image,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Label(app.label, modifier = Modifier.padding(start = 10.dp), size = 12.5f, maxLines = 2)
                }
            }
            if (moving) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Label("‹   ›", size = 20f, weight = FontWeight.Light)
                }
            }
        }

        if (appearance.labels != LabelMode.NEVER) {
            // The name is already printed inside cards that have no banner of their own.
            val nameInside = nameOnly || (loaded != null && !loaded.isBanner && style == CardStyle.BANNER)
            val wanted = !nameInside && (appearance.labels == LabelMode.ALWAYS || focused)
            val labelAlpha by animateFloatAsState(if (wanted) 1f else 0f, tween(duration), label = "labelAlpha")
            Label(
                text = app.label,
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp).graphicsLayer { alpha = labelAlpha },
                size = 12f,
                align = TextAlign.Center,
            )
        }
    }
}

/**
 * One entry of the "continue watching" row: a poster where there is one, else the title, with a
 * line across the bottom showing how far through it is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchCard(
    item: WatchItem,
    index: Int,
    retry: Int,
    imageHeight: Float,
    state: HomeState,
    modifier: Modifier,
    onOpen: () -> Unit,
    onHold: () -> Unit,
) {
    val appearance = LocalAppearance.current
    val palette = LocalPalette.current
    val repo = (LocalContext.current.applicationContext as SlateApp).watchNext
    val target = CardTarget(WATCH_SECTION, item.key)
    val scope = rememberCoroutineScope()

    var focused by remember { mutableStateOf(false) }
    val url = item.posterUrl
    // A new address starts with nothing (an entry moving on to its next episode keeps its place
    // in the row). Coming back to the launcher only makes a card that has no picture ask again:
    // one that is showing its picture keeps it, also when memory was freed in the meantime.
    val poster by key(url) {
        produceState(url?.let(repo::cachedPoster), retry) {
            // A picture that could not be reached (no network yet, straight after the TV woke
            // up, say) is asked for again a few times while its card is on screen.
            var tries = 0
            while (url != null && value == null && tries++ < POSTER_TRIES) {
                value = repo.poster(url, item.packageName)
                if (value != null) break
                val wait = repo.retryIn(url, item.packageName) ?: break
                if (wait > POSTER_RETRY_WITHIN_MS) break
                delay(wait + 500)
            }
        }
    }

    val duration = if (appearance.animations) 160 else 0
    val scale by animateFloatAsState(
        targetValue = if (focused) appearance.focusScale.coerceIn(Limits.focusScale) / 100f else 1f,
        animationSpec = tween(duration),
        label = "watchScale",
    )
    val shade by animateFloatAsState(
        targetValue = if (focused || !appearance.dimUnfocused) 0f else 0.34f,
        animationSpec = tween(duration),
        label = "watchShade",
    )
    val shape = RoundedCornerShape(appearance.cornerRadius.coerceIn(Limits.cornerRadius).dp)
    // Wide enough for the picture's shape, within reason whatever shape the app claims.
    // An entry without a picture shows its title, which needs the room of a wide card.
    val shapeRatio = if (item.posterUrl == null) 16f / 9f else item.aspect.coerceIn(2f / 3f, 16f / 9f)
    val width = imageHeight * shapeRatio + GAP

    val bring = remember { BringIntoViewRequester() }
    val requester = remember { FocusRequester() }
    val handle = remember { CardHandle(requester, bring) }
    val latestIndex = rememberUpdatedState(index)

    // Lets the home screen hand focus to this card by name.
    DisposableEffect(target) {
        state.cards[target] = handle
        onDispose {
            if (state.cards[target] === handle) state.cards.remove(target)
        }
    }

    Column(
        modifier = modifier
            .width(width.dp)
            .zIndex(if (focused) 1f else 0f)
            .bringIntoViewRequester(bring)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    state.zone.value = Zone.WATCH
                    state.featuredKey.value = item.key
                    // The featured details sit above this row: show the list from its top, and then
                    // make sure the row itself is whole on screen (a large text size can make the
                    // details tall enough to push it down).
                    val list = state.listState
                    if (state.heroShown) {
                        scope.launch {
                            try {
                                // A key already on its way may have moved focus on meanwhile.
                                if (state.zone.value != Zone.WATCH) return@launch
                                if (list.firstVisibleItemIndex != 0) {
                                    if (appearance.animations) list.animateScrollToItem(0) else list.scrollToItem(0)
                                }
                                // Two frames: the details of this entry are composed in the first and
                                // measured by the second, and their height decides where the row is.
                                repeat(2) { withFrameNanos { } }
                                if (state.zone.value != Zone.WATCH || list.firstVisibleItemIndex != 0) return@launch
                                val info = list.layoutInfo
                                val row = info.visibleItemsInfo.firstOrNull { it.key == "watch:row" } ?: return@launch
                                // From the top, unless that would leave the row's lower edge off screen.
                                val now = list.firstVisibleItemScrollOffset
                                val wanted = (row.offset + now + row.size - info.viewportEndOffset).coerceAtLeast(0)
                                if (wanted != now) {
                                    if (appearance.animations) list.animateScrollToItem(0, wanted) else list.scrollToItem(0, wanted)
                                }
                            } catch (e: CancellationException) {
                                // A newer scroll (the next key press) took over.
                            }
                        }
                    }
                    state.focusedCard = target
                    state.cardHasFocus = true
                    state.rowFocus[target.sectionId] = target
                    state.watchFocus = item.key to latestIndex.value
                    // The user's focus is no longer on an app; see HomeState.chosenCard.
                    if (state.keyJustPressed()) state.chosenCard = null
                } else if (state.focusedCard == target) {
                    state.cardHasFocus = false
                }
            }
            // Entries have no options. A hold is taken (and ignored) so that it does not open
            // the entry when let go: someone trying "hold OK" here meant to see a menu.
            .tvClickable(onLongClick = onHold, onClick = onOpen)
            .padding(horizontal = (GAP / 2).dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shown = poster
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(imageHeight.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(palette.surface)
                .drawWithContent {
                    drawContent()
                    if (shade > 0f) drawRect(Color.Black.copy(alpha = shade))
                }
                .then(if (focused && appearance.focusBorder) Modifier.border(2.dp, palette.accent, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                Image(
                    bitmap = shown.image,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // No picture (none given, not fetched yet, or it could not be used): the title.
                Label(
                    text = item.title,
                    modifier = Modifier.padding(horizontal = 10.dp),
                    size = 12.5f,
                    maxLines = 3,
                    align = TextAlign.Center,
                )
            }
            val progress = item.progress
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.45f)))
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(palette.accent),
                )
            }
        }

        // Shown on the focused card whatever the setting for app names says: a title and a
        // picture are the publishing app's own, and only the app's name here says whose they are.
        val wanted = appearance.labels == LabelMode.ALWAYS || focused
        val labelAlpha by animateFloatAsState(if (wanted) 1f else 0f, tween(duration), label = "watchLabelAlpha")
        // The title is already printed inside a card that has no picture: there the app's name
        // takes the first line.
        Label(
            text = if (shown != null) item.title else item.appName,
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp).graphicsLayer { alpha = labelAlpha },
            size = 12f,
            align = TextAlign.Center,
        )
        Label(
            // The app's name first: on a narrow card it is what must not be cut off.
            text = if (shown != null) {
                listOf(item.appName, item.episode).filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                item.episode
            },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).graphicsLayer { alpha = labelAlpha },
            size = 10.5f,
            color = palette.textDim,
            align = TextAlign.Center,
        )
    }
}

@Composable
private fun Notice(text: String) {
    val appearance = LocalAppearance.current
    val edge = appearance.edgePadding.coerceIn(Limits.edgePadding)
    Label(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = edge.dp, vertical = 10.dp),
        size = 13f,
        color = LocalPalette.current.textDim,
        maxLines = 4,
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier
            .padding(bottom = 28.dp)
            .clip(RoundedCornerShape(50))
            .background(palette.panel)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Label(text, size = 13f, color = palette.textDim)
    }
}

@Composable
private fun InputsSheet(inputs: List<TvInput>, onOpen: (TvInput) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    SideSheet(title = "Inputs", onDismiss = onClose) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(inputs, key = { _, input -> input.id }) { index, input ->
                SheetRow(
                    title = input.label,
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { onOpen(input) },
                )
            }
        }
        LaunchedEffect(Unit) {
            repeat(2) { withFrameNanos { } }
            runCatching { first.requestFocus() }
        }
    }
}
