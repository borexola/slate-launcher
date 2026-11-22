package app.slate.launcher.data

import kotlinx.serialization.Serializable

/**
 * The whole launcher is driven by this one model. It is stored as JSON, so everything here can
 * also be edited by hand on a computer and pushed back to the TV.
 *
 * Nothing outside [sanitized] may assume a value is in range: a hand-edited file can hold
 * anything that parses. Every config passes through [sanitized] before the UI sees it.
 */
@Serializable
data class LauncherConfig(
    val version: Int = 1,
    val appearance: Appearance = Appearance(),
    val sections: List<Section> = defaultSections(),
    /** Package names that never appear on the home screen. */
    val hidden: Set<String> = emptySet(),
    val overrides: Map<String, AppOverride> = emptyMap(),
    /** When true, apps pinned to another section are left out of "all apps" sections. */
    val allExcludesPinned: Boolean = false,
    /** Home button guard: swallow the Home key and open Slate instead of the stock launcher. */
    val interceptHomeKey: Boolean = true,
    /** Show a row of what apps report as in progress. Off unless the user turns it on. */
    val watchNext: Boolean = false,
    /** Slate's screensaver, for when Android starts it (see `scripts/slate.sh screensaver-on`). */
    val screensaver: Screensaver = Screensaver(),
    /** Whether the options of an app have been opened once; until then a hint says how. */
    val menuUsed: Boolean = false,
    /** Apps opened while that hint was showing; after a few, the hint goes anyway. */
    val launchesSeen: Int = 0,
)

@Serializable
data class Section(
    val id: String,
    val title: String,
    val style: SectionStyle = SectionStyle.ROW,
    /** Listed apps come first in the given order; with includeAll every other app follows A–Z. */
    val includeAll: Boolean = false,
    val showTitle: Boolean = true,
    val apps: List<String> = emptyList(),
)

@Serializable
data class AppOverride(val label: String? = null)

@Serializable
data class Appearance(
    /** Focus highlight colour, "#RRGGBB". */
    val accent: String = "#FFFFFF",
    val background: BackgroundKind = BackgroundKind.GRADIENT,
    val gradient: String = "midnight",
    val solidColor: String = "#000000",
    /** Percent of black laid over the wallpaper. */
    val wallpaperDim: Int = 45,
    /** Wallpaper blur strength; 0 is off. */
    val wallpaperBlur: Int = 0,
    val cardStyle: CardStyle = CardStyle.BANNER,
    /** Card width in dp for row sections. */
    val cardWidth: Int = 176,
    val cornerRadius: Int = 14,
    /** Size of the focused card in percent, 100 = no zoom. */
    val focusScale: Int = 108,
    val focusBorder: Boolean = true,
    val dimUnfocused: Boolean = true,
    val labels: LabelMode = LabelMode.FOCUSED,
    val gridColumns: Int = 6,
    /** Distance from the screen edge in dp (overscan margin). */
    val edgePadding: Int = 48,
    /** Text size in percent. */
    val fontScale: Int = 100,
    val showClock: Boolean = true,
    val clock24h: Boolean = true,
    val showDate: Boolean = true,
    val animations: Boolean = true,
    /** The focused "continue watching" entry fills the background, with its details above the row. */
    val featuredTitle: Boolean = true,
    /** Status chips in the top bar. */
    val showMemory: Boolean = true,
    val showProcessor: Boolean = true,
    val showStorage: Boolean = true,
    val showNetwork: Boolean = true,
    /** For OLED screens: dim when idle, and move everything a little now and then. */
    val screenCare: Boolean = true,
)

@Serializable
data class Screensaver(
    /** Where the pictures come from. Only [SaverSource.ONLINE] and [SaverSource.BOTH] use the network. */
    val source: SaverSource = SaverSource.PHOTOS,
    /** How long each picture stays, in seconds. */
    val seconds: Int = 20,
    val shuffle: Boolean = true,
    val clock: Boolean = true,
)

/** Own photos on the TV, Wikimedia Commons' Picture of the Day, or both. */
@Serializable
enum class SaverSource { PHOTOS, ONLINE, BOTH }

val SaverSource.usesPhotos get() = this != SaverSource.ONLINE
val SaverSource.usesOnline get() = this != SaverSource.PHOTOS

@Serializable
enum class SectionStyle { ROW, GRID }

@Serializable
enum class CardStyle { BANNER, ICON }

@Serializable
enum class LabelMode { ALWAYS, FOCUSED, NEVER }

@Serializable
enum class BackgroundKind { SOLID, GRADIENT, WALLPAPER }

fun defaultSections(): List<Section> = listOf(
    Section(id = "favorites", title = "Favorites", style = SectionStyle.ROW),
    Section(id = "apps", title = "Apps", style = SectionStyle.GRID, includeAll = true),
)

/** The one place that says what is allowed. The settings panel steps within the same ranges. */
object Limits {
    val cardWidth = 112..272
    val cornerRadius = 0..32
    val focusScale = 100..124
    val gridColumns = 3..10
    val edgePadding = 16..88
    val fontScale = 80..140
    val wallpaperDim = 0..90
    val wallpaperBlur = 0..40
    val saverSeconds = 5..3600

    const val MAX_SECTIONS = 24
    const val MAX_TITLE = 40
    const val MAX_LABEL = 60
    const val MAX_APPS = 500
    const val MAX_ID = 64
    const val MAX_PACKAGE = 255

    /** A settings file offered for import. Slate's own saved file may be larger: see below. */
    const val MAX_IMPORT_BYTES = 512L * 1024

    /**
     * Slate's own saved settings. They are written pretty-printed with every field spelled out,
     * so a file that was within the import limit can be larger once saved. This only has to
     * stop something absurd from being read into memory.
     */
    const val MAX_SAVED_BYTES = 8L * 1024 * 1024
}

private val ColorPattern = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
private val PackagePattern = Regex("^[A-Za-z0-9_.]+$")

fun isValidColor(value: String): Boolean = ColorPattern.matches(value)

private fun isPackageName(value: String) =
    value.length <= Limits.MAX_PACKAGE && PackagePattern.matches(value)

/**
 * Single-line, printable, bounded text. Besides control characters, the invisible characters
 * that force a writing direction are removed: an app's name or a title could otherwise show
 * part of itself back to front, or reorder whatever is printed after it.
 */
fun cleanText(raw: String, max: Int): String =
    raw.filterNot {
        it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' ||
            // Line and paragraph separators, and characters that are there but take no room.
            it == '\u2028' || it == '\u2029' || it == '\u200B' || it == '\u2060' || it == '\uFEFF'
    }.trim().takeWhole(max).trim()

/** Like take, but never leaves half of a two-part character (an emoji, say) at the end. */
fun String.takeWhole(max: Int): String {
    val cut = take(max)
    return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

fun Appearance.sanitized(): Appearance {
    val defaults = Appearance()
    return copy(
        accent = accent.takeIf(::isValidColor) ?: defaults.accent,
        solidColor = solidColor.takeIf(::isValidColor) ?: defaults.solidColor,
        gradient = cleanText(gradient, Limits.MAX_ID),
        wallpaperDim = wallpaperDim.coerceIn(Limits.wallpaperDim),
        wallpaperBlur = wallpaperBlur.coerceIn(Limits.wallpaperBlur),
        cardWidth = cardWidth.coerceIn(Limits.cardWidth),
        cornerRadius = cornerRadius.coerceIn(Limits.cornerRadius),
        focusScale = focusScale.coerceIn(Limits.focusScale),
        gridColumns = gridColumns.coerceIn(Limits.gridColumns),
        edgePadding = edgePadding.coerceIn(Limits.edgePadding),
        fontScale = fontScale.coerceIn(Limits.fontScale),
    )
}

/**
 * Returns a config that is safe to render whatever the input was: numbers in range, colours
 * parseable, ids unique, text and lists bounded.
 */
fun LauncherConfig.sanitized(): LauncherConfig {
    val seen = HashSet<String>()
    val cleanSections = sections.asSequence()
        .map { it.copy(id = cleanText(it.id, Limits.MAX_ID)) }
        .filter { it.id.isNotEmpty() && seen.add(it.id) }
        .take(Limits.MAX_SECTIONS)
        .map { section ->
            section.copy(
                title = cleanText(section.title, Limits.MAX_TITLE).ifEmpty { "Section" },
                apps = section.apps.asSequence().filter(::isPackageName).distinct().take(Limits.MAX_APPS).toList(),
            )
        }
        .toList()
    val cleanOverrides = overrides.asSequence()
        .filter { isPackageName(it.key) }
        .mapNotNull { (pkg, override) ->
            val label = override.label?.let { cleanText(it, Limits.MAX_LABEL) }
            if (label.isNullOrEmpty()) null else pkg to AppOverride(label)
        }
        .take(Limits.MAX_APPS)
        .toMap()
    return copy(
        appearance = appearance.sanitized(),
        sections = cleanSections.ifEmpty { defaultSections() },
        hidden = hidden.asSequence().filter(::isPackageName).take(Limits.MAX_APPS * 4).toSet(),
        overrides = cleanOverrides,
        launchesSeen = launchesSeen.coerceIn(0, 1_000),
        screensaver = screensaver.copy(seconds = screensaver.seconds.coerceIn(Limits.saverSeconds)),
    )
}
