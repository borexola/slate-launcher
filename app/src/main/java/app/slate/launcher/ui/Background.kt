package app.slate.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.slate.launcher.data.Appearance
import app.slate.launcher.data.BackgroundKind
import app.slate.launcher.data.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class LoadedWallpaper(val stamp: String, val image: ImageBitmap?)

/** Fills the screen behind everything. The window itself is black, so nothing shows through. */
@Composable
fun Background(appearance: Appearance, wallpaperVersion: Int, safeMode: Boolean) {
    val context = LocalContext.current
    val wantsWallpaper = appearance.background == BackgroundKind.WALLPAPER
    val blur = appearance.wallpaperBlur

    // Re-checked on every resume, but only decoded again when the file or the blur changed.
    val wallpaper by produceState<LoadedWallpaper?>(null, wantsWallpaper, wallpaperVersion, blur, safeMode) {
        if (!wantsWallpaper) {
            value = null
            return@produceState
        }
        val current = value
        // While a value is being stepped with a held button this restarts many times a second;
        // wait for it to settle rather than decoding the image for every step.
        if (current != null) delay(200)
        value = withContext(Dispatchers.IO) {
            val stamp = Wallpaper.stamp(context) + ":" + blur
            if (current?.image != null && current.stamp == stamp) {
                current
            } else {
                // A failed load is remembered with an empty stamp, so the next check tries again.
                val image = Wallpaper.load(context, blur)
                LoadedWallpaper(if (image != null) stamp else "", image)
            }
        }
    }

    val image = wallpaper?.image
    when {
        appearance.background == BackgroundKind.SOLID ->
            Box(Modifier.fillMaxSize().background(parseColor(appearance.solidColor, Color.Black)))

        wantsWallpaper && image != null -> {
            val dim = appearance.wallpaperDim.coerceIn(0, 90) / 100f
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val scrim = Color.Black.copy(alpha = dim)
                        onDrawWithContent {
                            drawContent()
                            drawRect(scrim)
                        }
                    },
                contentScale = ContentScale.Crop,
            )
        }

        // Also the fallback while a wallpaper loads, or when it cannot be read.
        else -> Gradient(appearance)
    }
}

@Composable
private fun Gradient(appearance: Appearance) {
    val preset = remember(appearance.gradient) {
        GradientPresets.firstOrNull { it.id == appearance.gradient } ?: GradientPresets.first()
    }
    val accent = LocalPalette.current.accent
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val base = Brush.linearGradient(preset.colors)
                // A faint wash of the accent colour in the top-left corner, for depth.
                val wash = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.12f, 0f),
                    radius = (size.width * 0.55f).coerceAtLeast(1f),
                )
                onDrawBehind {
                    drawRect(base)
                    drawRect(wash)
                }
            },
    )
}
