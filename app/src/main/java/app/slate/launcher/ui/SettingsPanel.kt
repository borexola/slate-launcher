package app.slate.launcher.ui

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.slate.launcher.LauncherViewModel
import app.slate.launcher.SlateApp
import app.slate.launcher.data.AppEntry
import app.slate.launcher.data.Appearance
import app.slate.launcher.data.BackgroundKind
import app.slate.launcher.data.CardStyle
import app.slate.launcher.data.LabelMode
import app.slate.launcher.data.LauncherConfig
import app.slate.launcher.data.Photos
import app.slate.launcher.data.SaverSource
import app.slate.launcher.data.Limits
import app.slate.launcher.data.Section
import app.slate.launcher.data.SectionStyle
import app.slate.launcher.data.Wallpaper
import app.slate.launcher.system.SystemStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private sealed interface Page {
    data object Main : Page
    data object Look : Page
    data object Sections : Page
    data class SectionDetail(val id: String) : Page
    data object Apps : Page
    data object Saver : Page
    data object System : Page
}

private class Prompt(val title: String, val initial: String, val maxLength: Int, val onConfirm: (String) -> Unit)

/** How long a "press again" stays open, and how soon after the first press it counts. */
private const val CONFIRM_WITHIN_MS = 8_000L
private const val CONFIRM_NOT_BEFORE_MS = 400L

/** What the System page shows about the device; re-read whenever the launcher resumes. */
private data class DeviceStatus(
    val isDefaultHome: Boolean = false,
    val otherHomes: List<String> = emptyList(),
    val guardEnabled: Boolean = false,
    val version: String = "",
    val hasWallpaper: Boolean = false,
    val exportPath: String = "",
    /** Photos from a computer, and photos added on the TV. */
    val photos: Pair<Int, Int> = 0 to 0,
    val onlineSaved: Int = 0,
    /** Whether Android uses Slate's screensaver; null if it would not say. */
    val saverInUse: Boolean? = null,
)

/** The whole customisation UI: a side panel with a few pages of plain rows. */
@Composable
fun SettingsPanel(
    vm: LauncherViewModel,
    onPickWallpaper: () -> Unit,
    onPickPhotos: () -> Unit,
    onAskWatchPermission: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val config by vm.config.collectAsState()
    val installed by vm.installed.collectAsState()
    val tick by vm.statusTick.collectAsState()
    val wallpaperVersion by vm.wallpaperVersion.collectAsState()
    val safeMode by vm.safeMode.collectAsState()
    val photosVersion by vm.photosVersion.collectAsState()
    val onlineVersion by vm.onlineVersion.collectAsState()

    var page by remember { mutableStateOf<Page>(Page.Main) }
    // Which row to land on when coming back to a page, so Back returns to where you were.
    var mainReturn by remember { mutableStateOf<Page>(Page.Look) }
    var sectionReturn by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmPhotos by remember { mutableStateOf(false) }
    // A colour typed into the settings file is kept in the cycle so it can be chosen again,
    // including one that arrives while the panel is open (an import, say).
    // (A plain holder, not observable state: it only needs to be current when the page is built.)
    val customAccentHolder = remember { arrayOfNulls<String>(1) }
    config.appearance.accent.let { now ->
        if (AccentPresets.none { it.hex.equals(now, ignoreCase = true) }) customAccentHolder[0] = now
    }
    val customAccent = customAccentHolder[0]
    val first = remember { FocusRequester() }
    val listState = remember(page) { LazyListState() }
    // The page whose starting row has already been given focus. A plain holder, not state.
    val focusPlacedFor = remember { arrayOfNulls<Page>(1) }

    val canRestore by vm.canRestoreSettings.collectAsState()
    val watchItems by vm.watchItems.collectAsState()
    // A "press again" left waiting must not still be waiting after a trip to another app, nor
    // after the user has plainly moved on.
    LaunchedEffect(tick) {
        confirmReset = false
        confirmRestore = false
        confirmPhotos = false
    }
    // When the first press came, and when the last confirmation was given. The clock is asked
    // at each press, so nothing depends on the timeout below having run yet. A press right on
    // the heels of another is one press of a button that bounces: it neither confirms nor,
    // straight after a confirmation, asks again.
    val armedAt = remember { LongArray(1) }
    val confirmedAt = remember { longArrayOf(-CONFIRM_NOT_BEFORE_MS) }
    // Counts the first presses, so that each one gets a timeout of its own. The timeout goes by
    // the same clock reading as the presses do, and clears only what has really run out: a
    // first press that arrives just as an older timeout ends is not wiped by it.
    var armings by remember { mutableStateOf(0) }
    LaunchedEffect(armings) {
        if (confirmReset || confirmRestore || confirmPhotos) {
            delay((armedAt[0] + CONFIRM_WITHIN_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L) + 1)
            if (SystemClock.uptimeMillis() - armedAt[0] > CONFIRM_WITHIN_MS) {
                confirmReset = false
                confirmRestore = false
                confirmPhotos = false
            }
        }
    }
    fun stillArmed(flag: Boolean) = flag && SystemClock.uptimeMillis() - armedAt[0] <= CONFIRM_WITHIN_MS
    fun pressedTwice() = SystemClock.uptimeMillis() - armedAt[0] >= CONFIRM_NOT_BEFORE_MS
    fun bounced() = SystemClock.uptimeMillis() - confirmedAt[0] < CONFIRM_NOT_BEFORE_MS
    fun arm() {
        armedAt[0] = SystemClock.uptimeMillis()
        armings++
    }

    // Looked up off the main thread: these touch the package manager and the file system.
    val status by produceState(DeviceStatus(), tick, wallpaperVersion, safeMode, photosVersion, onlineVersion) {
        value = withContext(Dispatchers.IO) { readStatus(context, vm) }
    }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val title = when (val current = page) {
        Page.Main -> "Customise"
        Page.Look -> "Appearance"
        Page.Sections -> "Home layout"
        is Page.SectionDetail -> config.sections.firstOrNull { it.id == current.id }?.title ?: "Section"
        Page.Apps -> "Apps"
        Page.Saver -> "Screensaver"
        Page.System -> "System"
    }

    SideSheet(
        title = title,
        onDismiss = {
            confirmReset = false
            confirmRestore = false
            confirmPhotos = false
            when (page) {
                Page.Main -> onClose()
                is Page.SectionDetail -> page = Page.Sections
                else -> page = Page.Main
            }
        },
    ) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (val current = page) {
                Page.Main -> mainPage(first, mainReturn) {
                    mainReturn = it
                    page = it
                }
                Page.Look -> lookPage(config.appearance, customAccent, first, status.hasWallpaper, vm, onPickWallpaper)
                Page.Sections -> sectionsPage(
                    config = config,
                    installed = installed,
                    first = first,
                    focusId = sectionReturn,
                    watchNext = config.watchNext,
                    // Read when the page is built; switching the row on re-reads it.
                    watchAllowed = vm.mayReadWatchNext(),
                    watchEmpty = watchItems.isEmpty(),
                    safeMode = safeMode,
                    onWatchNext = {
                        when {
                            config.watchNext && vm.mayReadWatchNext() -> vm.setWatchNext(false)
                            vm.mayReadWatchNext() -> vm.setWatchNext(true)
                            // Android asks; the answer switches the row on (see HomeScreen).
                            else -> onAskWatchPermission()
                        }
                    },
                    onOpen = {
                        sectionReturn = it
                        page = Page.SectionDetail(it)
                    },
                    onAdd = {
                        if (config.sections.size >= Limits.MAX_SECTIONS) {
                            toast("That is the most sections Slate supports. Delete one first.")
                        } else {
                            prompt = Prompt("New section", "", Limits.MAX_TITLE) { name ->
                                if (!vm.addSection(name)) toast("That is the most sections Slate supports.")
                            }
                        }
                    },
                )
                is Page.SectionDetail -> {
                    val section = config.sections.firstOrNull { it.id == current.id }
                    if (section != null) {
                        sectionPage(
                            section = section,
                            canDelete = config.sections.size > 1,
                            first = first,
                            vm = vm,
                            onRename = {
                                prompt = Prompt("Rename section", section.title, Limits.MAX_TITLE) { name ->
                                    vm.renameSection(section.id, name)
                                }
                            },
                            onDeleted = { page = Page.Sections },
                        )
                    }
                }
                Page.Apps -> appsPage(config, installed, first, vm)
                Page.Saver -> saverPage(
                    config = config,
                    status = status,
                    safeMode = safeMode,
                    first = first,
                    vm = vm,
                    onPickPhotos = onPickPhotos,
                    confirmPhotos = confirmPhotos,
                    onRemovePhotos = {
                        if (bounced()) {
                            // The tail of the press that has just confirmed.
                        } else if (!stillArmed(confirmPhotos)) {
                            confirmReset = false
                            confirmRestore = false
                            confirmPhotos = true
                            arm()
                        } else if (pressedTwice()) {
                            confirmPhotos = false
                            confirmedAt[0] = SystemClock.uptimeMillis()
                            vm.removePickedPhotos(::toast)
                        }
                    },
                )
                Page.System -> systemPage(
                    config = config,
                    status = status,
                    safeMode = safeMode,
                    canRestore = canRestore,
                    first = first,
                    vm = vm,
                    context = context,
                    confirmReset = confirmReset,
                    onReset = {
                        confirmRestore = false
                        confirmPhotos = false
                        if (bounced()) {
                            // The tail of the press that has just confirmed.
                        } else if (!stillArmed(confirmReset)) {
                            confirmReset = true
                            arm()
                        } else if (pressedTwice()) {
                            confirmReset = false
                            confirmedAt[0] = SystemClock.uptimeMillis()
                            vm.resetConfig()
                            toast("Everything is back to defaults")
                        }
                    },
                    confirmRestore = confirmRestore,
                    onRestore = {
                        confirmReset = false
                        confirmPhotos = false
                        if (bounced()) {
                            // The tail of the press that has just confirmed.
                        } else if (!stillArmed(confirmRestore)) {
                            confirmRestore = true
                            arm()
                        } else if (pressedTwice()) {
                            confirmRestore = false
                            confirmedAt[0] = SystemClock.uptimeMillis()
                            vm.restoreEarlierSettings(onDone = ::toast)
                        }
                    },
                    toast = ::toast,
                )
            }
        }
        // What the buttons do here, always in view: nothing in a panel should need guessing.
        Label(
            text = "OK  select   ·   ‹ ›  change a value   ·   Back  return",
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp),
            size = 11f,
            color = LocalPalette.current.textDim,
            maxLines = 2,
        )
        // A section deleted from under its own page (for example by an import) has nothing to show.
        LaunchedEffect(page, config.sections) {
            val current = page
            if (current is Page.SectionDetail && config.sections.none { it.id == current.id }) page = Page.Sections
        }
        LaunchedEffect(page) {
            // The page this run is for; `page` itself may have moved on by the time it finishes.
            val target = page
            // Once per page change, and only then. An effect can be restarted by the framework for
            // reasons of its own; if that moved focus, it would be pulled off the row in use.
            if (focusPlacedFor[0] == target) return@LaunchedEffect
            // Forgotten until this run finishes: if it is cut short (Back pressed at once, say),
            // the page returned to must get its focus placed again.
            focusPlacedFor[0] = null
            // The row to return to may be further down than the list has built so far.
            if (target == Page.Sections) {
                val at = config.sections.indexOfFirst { it.id == sectionReturn }
                if (at > 0) withTimeoutOrNull(500) { listState.scrollToItem(at) }
            }
            repeat(2) { withFrameNanos { } }
            runCatching { first.requestFocus() }
            focusPlacedFor[0] = target
        }
    }

    prompt?.let { current ->
        TextPrompt(
            title = current.title,
            initial = current.initial,
            maxLength = current.maxLength,
            onDismiss = { prompt = null },
            onConfirm = { text ->
                prompt = null
                current.onConfirm(text)
            },
        )
    }
}

private fun readStatus(context: Context, vm: LauncherViewModel) = DeviceStatus(
    isDefaultHome = SystemStatus.isDefaultHome(context),
    otherHomes = SystemStatus.otherHomePackages(context),
    guardEnabled = SystemStatus.isGuardEnabled(context),
    version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty(),
    hasWallpaper = Wallpaper.exists(context),
    exportPath = vm.exportPath,
    photos = Photos.count(context),
    onlineSaved = (context.applicationContext as SlateApp).pictureOfTheDay.saved().size,
    saverInUse = SystemStatus.isScreensaver(context),
)

// ---- row helpers ----

private fun LazyListScope.row(
    title: String,
    modifier: Modifier = Modifier,
    key: Any = title,
    value: String? = null,
    subtitle: String? = null,
    danger: Boolean = false,
    onAdjust: ((Int) -> Unit)? = null,
    adjustOnRepeat: Boolean = true,
    canLower: Boolean = true,
    canRaise: Boolean = true,
    onClick: () -> Unit,
) {
    item(key = key) {
        SheetRow(
            title = title,
            modifier = modifier,
            value = value,
            subtitle = subtitle,
            danger = danger,
            onAdjust = onAdjust,
            adjustOnRepeat = adjustOnRepeat,
            canLower = canLower,
            canRaise = canRaise,
            onClick = onClick,
        )
    }
}

/**
 * What OK asks of a value, as opposed to Right: the next one, and after the last one the first
 * again. Left and Right stop at the ends of a range of numbers, where they would otherwise leave
 * someone who only uses OK with no way back down.
 */
private const val NEXT = 2

/**
 * A row whose value is changed in place: Left and Right step it, OK moves on to the next value.
 * For a number, [at] and [range] say where it stands, so the arrows can show which way is left.
 */
private fun LazyListScope.stepper(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    at: Int? = null,
    range: IntRange? = null,
    onStep: (Int) -> Unit,
) = row(
    title = title,
    modifier = modifier,
    value = value,
    subtitle = subtitle,
    onAdjust = onStep,
    canLower = at == null || range == null || at > range.first,
    canRaise = at == null || range == null || at < range.last,
    onClick = { onStep(NEXT) },
)

private fun LazyListScope.toggle(
    title: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onToggle: () -> Unit,
) = row(
    title = title,
    modifier = modifier,
    value = if (on) "On" else "Off",
    subtitle = subtitle,
    onAdjust = { onToggle() },
    adjustOnRepeat = false,
    onClick = onToggle,
)

private fun <T> List<T>.cycle(current: T, step: Int): T {
    val index = indexOf(current).coerceAtLeast(0)
    val move = if (step == NEXT) 1 else step
    return this[(index + move + size) % size]
}

private fun stepped(value: Int, step: Int, by: Int, range: IntRange): Int = when {
    step == NEXT && value >= range.last -> range.first
    step == NEXT -> (value + by).coerceIn(range.first, range.last)
    else -> (value + step * by).coerceIn(range.first, range.last)
}

// ---- pages ----

private fun LazyListScope.mainPage(first: FocusRequester, focusPage: Page, open: (Page) -> Unit) {
    fun at(page: Page) = if (page == focusPage) Modifier.focusRequester(first) else Modifier
    row("Appearance", at(Page.Look), subtitle = "Colours, background, cards, text") { open(Page.Look) }
    row("Home layout", at(Page.Sections), subtitle = "Sections, rows, grids, Continue watching") { open(Page.Sections) }
    row("Apps", at(Page.Apps), subtitle = "Show or hide apps") { open(Page.Apps) }
    row("Screensaver", at(Page.Saver), subtitle = "Your photos, or Wikimedia's Picture of the Day") { open(Page.Saver) }
    row("System", at(Page.System), subtitle = "Home button, backup, reset") { open(Page.System) }
}

private class BackgroundOption(val name: String, val kind: BackgroundKind, val gradient: String = "")

private fun LazyListScope.lookPage(
    a: Appearance,
    customAccent: String?,
    first: FocusRequester,
    hasWallpaper: Boolean,
    vm: LauncherViewModel,
    onPickWallpaper: () -> Unit,
) {
    val accents = if (customAccent != null) AccentPresets + NamedColor("Custom $customAccent", customAccent) else AccentPresets
    val accent = accents.firstOrNull { it.hex.equals(a.accent, ignoreCase = true) }
    stepper("Accent colour", accent?.name ?: a.accent, Modifier.focusRequester(first)) { step ->
        val next = if (accent == null) accents.first() else accents.cycle(accent, step)
        vm.updateAppearance { it.copy(accent = next.hex) }
    }

    val solidName = if (a.solidColor.equals("#000000", ignoreCase = true)) "Black" else "Colour ${a.solidColor}"
    val backgrounds = GradientPresets.map { BackgroundOption(it.name, BackgroundKind.GRADIENT, it.id) } +
        BackgroundOption(solidName, BackgroundKind.SOLID) +
        BackgroundOption("Wallpaper", BackgroundKind.WALLPAPER)
    val background = backgrounds.firstOrNull {
        it.kind == a.background && (it.kind != BackgroundKind.GRADIENT || it.gradient == a.gradient)
    } ?: backgrounds.first()
    stepper("Background", background.name) { step ->
        val next = backgrounds.cycle(background, step)
        vm.updateAppearance {
            it.copy(background = next.kind, gradient = next.gradient.ifEmpty { it.gradient })
        }
    }
    row(
        title = "Choose wallpaper image",
        subtitle = if (hasWallpaper) "An image is set. Pick another to replace it." else "Or push one over ADB: see the README.",
        onClick = onPickWallpaper,
    )
    if (a.background == BackgroundKind.WALLPAPER) {
        stepper("Wallpaper dimming", "${a.wallpaperDim}%", at = a.wallpaperDim, range = Limits.wallpaperDim) { step ->
            vm.updateAppearance { it.copy(wallpaperDim = stepped(it.wallpaperDim, step, 5, Limits.wallpaperDim)) }
        }
        stepper("Wallpaper blur", if (a.wallpaperBlur == 0) "Off" else "${a.wallpaperBlur}", at = a.wallpaperBlur, range = Limits.wallpaperBlur) { step ->
            vm.updateAppearance { it.copy(wallpaperBlur = stepped(it.wallpaperBlur, step, 4, Limits.wallpaperBlur)) }
        }
    }

    stepper("Card style", if (a.cardStyle == CardStyle.BANNER) "Banners" else "Icons") { step ->
        vm.updateAppearance { it.copy(cardStyle = CardStyle.entries.cycle(it.cardStyle, step)) }
    }
    stepper("Card size", "${a.cardWidth}", subtitle = "Applies to rows. Grids use the column count.", at = a.cardWidth, range = Limits.cardWidth) { step ->
        vm.updateAppearance { it.copy(cardWidth = stepped(it.cardWidth, step, 16, Limits.cardWidth)) }
    }
    stepper("Grid columns", "${a.gridColumns}", at = a.gridColumns, range = Limits.gridColumns) { step ->
        vm.updateAppearance { it.copy(gridColumns = stepped(it.gridColumns, step, 1, Limits.gridColumns)) }
    }
    stepper("Corner rounding", "${a.cornerRadius}", at = a.cornerRadius, range = Limits.cornerRadius) { step ->
        vm.updateAppearance { it.copy(cornerRadius = stepped(it.cornerRadius, step, 2, Limits.cornerRadius)) }
    }
    stepper("Focus zoom", if (a.focusScale <= 100) "Off" else "+${a.focusScale - 100}%", at = a.focusScale, range = Limits.focusScale) { step ->
        vm.updateAppearance { it.copy(focusScale = stepped(it.focusScale, step, 2, Limits.focusScale)) }
    }
    toggle("Focus outline", a.focusBorder) { vm.updateAppearance { it.copy(focusBorder = !it.focusBorder) } }
    toggle("Dim unfocused cards", a.dimUnfocused) { vm.updateAppearance { it.copy(dimUnfocused = !it.dimUnfocused) } }

    val labelName = when (a.labels) {
        LabelMode.ALWAYS -> "Always"
        LabelMode.FOCUSED -> "On focus"
        LabelMode.NEVER -> "Never"
    }
    stepper("App names", labelName) { step ->
        vm.updateAppearance { it.copy(labels = LabelMode.entries.cycle(it.labels, step)) }
    }
    stepper("Text size", "${a.fontScale}%", at = a.fontScale, range = Limits.fontScale) { step ->
        vm.updateAppearance { it.copy(fontScale = stepped(it.fontScale, step, 10, Limits.fontScale)) }
    }
    stepper("Screen margin", "${a.edgePadding}", subtitle = "Raise this if the picture is cut off at the edges.", at = a.edgePadding, range = Limits.edgePadding) { step ->
        vm.updateAppearance { it.copy(edgePadding = stepped(it.edgePadding, step, 8, Limits.edgePadding)) }
    }

    val clocks = listOf("Off", "24-hour", "12-hour")
    val clock = when {
        !a.showClock -> "Off"
        a.clock24h -> "24-hour"
        else -> "12-hour"
    }
    stepper("Clock", clock) { step ->
        val next = clocks.cycle(clock, step)
        vm.updateAppearance { it.copy(showClock = next != "Off", clock24h = next != "12-hour") }
    }
    toggle("Date", a.showDate) { vm.updateAppearance { it.copy(showDate = !it.showDate) } }
    toggle(
        title = "Featured title",
        on = a.featuredTitle,
        subtitle = "With Continue watching on: the entry in focus fills the background with its picture, " +
            "with its title and time left above the row.",
    ) { vm.updateAppearance { it.copy(featuredTitle = !it.featuredTitle) } }
    toggle(
        title = "Screen care",
        on = a.screenCare,
        subtitle = "For OLED screens, which can keep a faint mark of a picture that never changes: after 5 " +
            "minutes without a key press the home screen dims, and every few minutes everything moves " +
            "by a pixel or two. The key that wakes it does nothing else.",
    ) { vm.updateAppearance { it.copy(screenCare = !it.screenCare) } }
    toggle("Status: memory", a.showMemory, subtitle = "Free memory, in the top bar.") {
        vm.updateAppearance { it.copy(showMemory = !it.showMemory) }
    }
    toggle(
        title = "Status: processor",
        on = a.showProcessor,
        subtitle = "How busy the processor is. Where Android does not let apps see that, its speed; where neither, nothing.",
    ) { vm.updateAppearance { it.copy(showProcessor = !it.showProcessor) } }
    toggle("Status: storage", a.showStorage, subtitle = "Free storage on the TV.") {
        vm.updateAppearance { it.copy(showStorage = !it.showStorage) }
    }
    toggle("Status: network", a.showNetwork, subtitle = "Wi-Fi with its strength, Ethernet, or offline.") {
        vm.updateAppearance { it.copy(showNetwork = !it.showNetwork) }
    }
    toggle("Animations", a.animations) { vm.updateAppearance { it.copy(animations = !it.animations) } }
}

private fun LazyListScope.sectionsPage(
    config: LauncherConfig,
    installed: List<AppEntry>,
    first: FocusRequester,
    focusId: String?,
    watchNext: Boolean,
    watchAllowed: Boolean,
    watchEmpty: Boolean,
    safeMode: Boolean,
    onWatchNext: () -> Unit,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val showable = installed.asSequence().map { it.packageName }.filter { it !in config.hidden }.toSet()
    val focusIndex = config.sections.indexOfFirst { it.id == focusId }.coerceAtLeast(0)
    config.sections.forEachIndexed { index, section ->
        val layout = if (section.style == SectionStyle.ROW) "Row" else "Grid"
        val contents = if (section.includeAll) "all apps" else "${section.apps.count { it in showable }} pinned"
        row(
            title = section.title,
            modifier = if (index == focusIndex) Modifier.focusRequester(first) else Modifier,
            key = "section:${section.id}",
            value = "$layout · $contents",
        ) { onOpen(section.id) }
    }
    row("Add section", key = "add-section", subtitle = "Then hold OK on any app to add it.", onClick = onAdd)
    // After the sections, so that their rows keep the positions the page scrolls to.
    val watchOn = watchNext && watchAllowed
    row(
        title = "Continue watching",
        key = "watch-next",
        value = if (watchOn) "On" else "Off",
        // A switch like the others: Left and Right flip it as well as OK.
        onAdjust = { onWatchNext() },
        adjustOnRepeat = false,
        subtitle = when {
            watchOn && safeMode -> "Not shown while in safe mode."
            watchOn && watchEmpty -> "On. Nothing is in progress right now: the row appears at the top " +
                "when one of your apps reports something."
            watchOn -> "A row at the top with what your apps report as in progress. Their poster " +
                "images are fetched from the internet."
            watchNext -> "Switched on, but Android has not allowed Slate to read the list. Press OK to ask."
            else -> "A row at the top with what your apps report as in progress. Android will ask " +
                "whether Slate may read that list; poster images are fetched from the internet."
        },
        onClick = onWatchNext,
    )
}

private fun LazyListScope.sectionPage(
    section: Section,
    canDelete: Boolean,
    first: FocusRequester,
    vm: LauncherViewModel,
    onRename: () -> Unit,
    onDeleted: () -> Unit,
) {
    row("Rename", Modifier.focusRequester(first), onClick = onRename)
    stepper("Layout", if (section.style == SectionStyle.ROW) "Row" else "Grid") { step ->
        vm.setSectionStyle(section.id, SectionStyle.entries.cycle(section.style, step))
    }
    toggle("Show title", section.showTitle) { vm.toggleSectionTitle(section.id) }
    toggle(
        title = "Include all apps",
        on = section.includeAll,
        subtitle = "Off: only apps you add. On: your apps first, then everything else A–Z.",
    ) { vm.toggleSectionIncludeAll(section.id) }
    row("Move up") { vm.moveSection(section.id, -1) }
    row("Move down") { vm.moveSection(section.id, 1) }
    if (canDelete) {
        row("Delete section", danger = true, subtitle = "The apps themselves are not removed.") {
            onDeleted()
            vm.deleteSection(section.id)
        }
    }
}

private fun LazyListScope.appsPage(
    config: LauncherConfig,
    installed: List<AppEntry>,
    first: FocusRequester,
    vm: LauncherViewModel,
) {
    toggle(
        title = "Show pinned apps only once",
        on = config.allExcludesPinned,
        modifier = Modifier.focusRequester(first),
        subtitle = "An app you added to a section such as Favorites is left out of sections that list all apps.",
    ) { vm.updateConfig { it.copy(allExcludesPinned = !it.allExcludesPinned) } }

    val named = installed
        .map { app -> app to (config.overrides[app.packageName]?.label ?: app.label) }
        .sortedBy { it.second.lowercase() }
    named.forEach { (app, name) ->
        val hidden = app.packageName in config.hidden
        // A switch like any other in these panels: Left and Right flip it as well as OK.
        row(
            title = name,
            key = "app:${app.packageName}",
            value = if (hidden) "Hidden" else "Shown",
            onAdjust = { vm.setHidden(app.packageName, !hidden) },
            adjustOnRepeat = false,
        ) { vm.setHidden(app.packageName, !hidden) }
    }
}

private val SaverSeconds = listOf(5, 10, 20, 30, 60, 120, 300, 600)

private fun seconds(value: Int) = if (value < 60 || value % 60 != 0) "$value s" else "${value / 60} min"

private fun LazyListScope.saverPage(
    config: LauncherConfig,
    status: DeviceStatus,
    safeMode: Boolean,
    first: FocusRequester,
    vm: LauncherViewModel,
    onPickPhotos: () -> Unit,
    confirmPhotos: Boolean,
    onRemovePhotos: () -> Unit,
) {
    val s = config.screensaver
    row(
        title = "Screensaver",
        modifier = Modifier.focusRequester(first),
        value = when (status.saverInUse) {
            true -> "Slate"
            false -> "Another"
            null -> "Unknown"
        },
        subtitle = when (status.saverInUse) {
            true -> "Android shows this slideshow when the TV has been idle for a while."
            else -> "To use it, choose \"Slate pictures\" in the TV's screensaver settings, or from a computer " +
                "run scripts/slate.sh screensaver-on (needed on Google TV)."
        },
    ) { }

    val (pushed, picked) = status.photos
    val photoText = when (pushed + picked) {
        0 -> "No photos on the TV yet."
        1 -> "1 image file on the TV."
        else -> "${pushed + picked} image files on the TV."
    }
    val onlineText = "From Wikimedia Commons, fetched over the internet once a day; " +
        "${status.onlineSaved} of the last 7 days saved for offline use."
    stepper(
        title = "Pictures",
        value = when (s.source) {
            SaverSource.PHOTOS -> "Your photos"
            SaverSource.ONLINE -> "Picture of the day"
            SaverSource.BOTH -> "Both"
        },
        subtitle = when {
            safeMode -> "Not shown while in safe mode."
            s.source == SaverSource.PHOTOS -> "$photoText Picture of the day (internet) is the next choice."
            s.source == SaverSource.ONLINE -> onlineText
            else -> "$photoText $onlineText"
        },
    ) { step -> vm.setSaverSource(SaverSource.entries.cycle(s.source, step)) }

    val at = SaverSeconds.indexOfFirst { it >= s.seconds }.let { if (it < 0) SaverSeconds.lastIndex else it }
    stepper("Time per picture", seconds(s.seconds), at = at, range = SaverSeconds.indices) { step ->
        val next = when {
            // A value typed into the settings file: the nearest choice in the direction pressed.
            s.seconds !in SaverSeconds && step < 0 -> SaverSeconds.lastOrNull { it < s.seconds } ?: SaverSeconds.first()
            s.seconds !in SaverSeconds -> SaverSeconds.firstOrNull { it > s.seconds } ?: SaverSeconds.first()
            step == NEXT -> SaverSeconds[(at + 1) % SaverSeconds.size]
            else -> SaverSeconds[(at + step).coerceIn(SaverSeconds.indices)]
        }
        vm.updateSaver { it.copy(seconds = next) }
    }
    toggle("Shuffle", s.shuffle) { vm.updateSaver { it.copy(shuffle = !it.shuffle) } }
    toggle("Clock", s.clock, subtitle = "In the style chosen under Appearance.") {
        vm.updateSaver { it.copy(clock = !it.clock) }
    }
    row(
        title = "Add photos",
        subtitle = "Opens the TV's file picker, where it has one. Or from a computer: scripts/slate.sh " +
            "photos add <folder>. JPEG or PNG, up to 16 MB each.",
        onClick = onPickPhotos,
    )
    if (picked > 0) {
        row(
            title = if (confirmPhotos) "Press again to remove them" else "Remove photos added on this TV",
            key = "remove-photos",
            danger = true,
            subtitle = "$picked added here. Photos from a computer are removed with scripts/slate.sh photos clear.",
            onClick = onRemovePhotos,
        )
    }
}

private fun LazyListScope.systemPage(
    config: LauncherConfig,
    status: DeviceStatus,
    safeMode: Boolean,
    canRestore: Boolean,
    first: FocusRequester,
    vm: LauncherViewModel,
    context: Context,
    confirmReset: Boolean,
    onReset: () -> Unit,
    confirmRestore: Boolean,
    onRestore: () -> Unit,
    toast: (String) -> Unit,
) {
    val homeSubtitle = when {
        status.isDefaultHome -> "The Home button opens Slate."
        status.guardEnabled -> "Slate is kept in front by the Home button guard."
        else -> "Another home screen is still active (${status.otherHomes.joinToString().ifEmpty { "unknown" }}). " +
            "Run scripts/slate.sh takeover from a computer, or turn on the guard below."
    }
    row(
        title = "Home screen",
        modifier = Modifier.focusRequester(first),
        value = if (status.isDefaultHome) "Slate" else if (status.guardEnabled) "Guarded" else "Not yet",
        subtitle = homeSubtitle,
    ) { }

    if (safeMode) {
        row(
            title = "Leave safe mode",
            subtitle = "Keeps the current settings and turns custom wallpaper and icons back on.",
        ) { vm.leaveSafeMode(restoreSettings = false, onDone = toast) }
        row(
            title = "Remove custom wallpaper, artwork and photos",
            subtitle = "Do this first if you added images shortly before safe mode appeared.",
        ) { vm.removeCustomImages(toast) }
        if (canRestore) {
            row(
                title = "Leave safe mode and restore my settings",
                subtitle = "Brings back the settings that were set aside last. If they were the cause, " +
                    "safe mode will return.",
            ) { vm.leaveSafeMode(restoreSettings = true, onDone = toast) }
        }
    }

    if (!safeMode && canRestore) {
        // Outside safe mode the current settings are the user's own work, and this replaces them.
        row(
            title = if (confirmRestore) "Press again to replace the current settings" else "Restore earlier settings",
            key = "restore-earlier",
            subtitle = "Brings back the settings safe mode set aside. They replace the current ones, " +
                "which are not kept.",
            onClick = onRestore,
        )
    }

    row(
        title = "Home button guard",
        value = if (status.guardEnabled) "On" else "Off",
        subtitle = "Only needed if the stock home screen cannot be disabled. Opens Accessibility settings.",
    ) {
        if (!SystemStatus.openAccessibilitySettings(context)) toast("Could not open Accessibility settings")
    }
    // A setting of the guard's, so only there while the guard is switched on.
    if (status.guardEnabled) {
        toggle(
            title = "Guard catches the Home button",
            on = config.interceptHomeKey,
            subtitle = "Turn off if you need the system's long-press Home action.",
        ) { vm.updateConfig { it.copy(interceptHomeKey = !it.interceptHomeKey) } }
    }

    row("TV settings") {
        if (!SystemStatus.openSystemSettings(context)) toast("Could not open the TV's settings")
    }
    row("Export settings", key = "export", subtitle = "Writes ${status.exportPath}") { vm.exportConfig(toast) }
    row("Import settings", subtitle = "Reads the same file back, for example after editing it on a computer.") {
        vm.importConfig(toast)
    }
    row(
        title = if (confirmReset) "Press again to reset everything" else "Reset everything",
        key = "reset",
        danger = true,
        onClick = onReset,
    )
    row(
        title = "Slate ${status.version}",
        key = "version",
        subtitle = "No ads, no tracking. The network is used only for what you switch on: poster " +
            "images for the Continue watching row, and the screensaver's Picture of the Day.",
    ) { }
}

