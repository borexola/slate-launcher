package app.slate.launcher.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.slate.launcher.data.Appearance

/** Colours derived from the user's appearance settings. */
data class Palette(
    val accent: Color,
    val text: Color = Color(0xFFF4F5F7),
    val textDim: Color = Color(0x99F4F5F7),
    val surface: Color = Color(0x14FFFFFF),
    val surfaceFocused: Color = Color(0x24FFFFFF),
    val panel: Color = Color(0xFF111215),
)

val LocalAppearance = compositionLocalOf { Appearance() }
val LocalPalette = compositionLocalOf { Palette(accent = Color.White) }

fun paletteFor(appearance: Appearance) = Palette(accent = parseColor(appearance.accent, Color.White))

fun parseColor(hex: String, fallback: Color): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    fallback
}

data class NamedColor(val name: String, val hex: String)

val AccentPresets = listOf(
    NamedColor("White", "#FFFFFF"),
    NamedColor("Ice", "#8AB4F8"),
    NamedColor("Mint", "#7DE2B8"),
    NamedColor("Amber", "#FFC66D"),
    NamedColor("Coral", "#FF8A80"),
    NamedColor("Lilac", "#C4A7FF"),
    NamedColor("Rose", "#FF9EC4"),
)

data class GradientPreset(val id: String, val name: String, val colors: List<Color>)

val GradientPresets = listOf(
    GradientPreset("midnight", "Midnight", listOf(Color(0xFF10172B), Color(0xFF090C17), Color(0xFF040509))),
    GradientPreset("aurora", "Aurora", listOf(Color(0xFF0F2027), Color(0xFF17323B), Color(0xFF0A1418))),
    GradientPreset("ember", "Ember", listOf(Color(0xFF2A1210), Color(0xFF170B0A), Color(0xFF070404))),
    GradientPreset("plum", "Plum", listOf(Color(0xFF211335), Color(0xFF120B1F), Color(0xFF06040B))),
    GradientPreset("forest", "Forest", listOf(Color(0xFF10261C), Color(0xFF0A1711), Color(0xFF040806))),
    GradientPreset("graphite", "Graphite", listOf(Color(0xFF202124), Color(0xFF121315), Color(0xFF08080A))),
)

/** The one text primitive used across the launcher, so the text-size setting applies everywhere. */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: Float = 14f,
    color: Color = LocalPalette.current.text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
    spacing: Float = 0f,
) {
    val scale = LocalAppearance.current.fontScale / 100f
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = (size * scale).sp,
            fontWeight = weight,
            textAlign = align,
            letterSpacing = spacing.sp,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
