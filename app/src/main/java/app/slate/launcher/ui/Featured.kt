package app.slate.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.slate.launcher.HomeActivity
import app.slate.launcher.SlateApp
import app.slate.launcher.data.Backdrop
import app.slate.launcher.data.WatchItem
import app.slate.launcher.system.DeviceStats
import app.slate.launcher.system.DeviceStatsReader
import app.slate.launcher.system.Link
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long focus has to rest on an entry before its picture takes over the screen. */
private const val BACKDROP_SETTLE_MS = 160L

/**
 * The picture of the "continue watching" entry in focus, filling the screen behind everything,
 * or nothing (the wallpaper shows) when [item] is null or [show] is false. Changes cross-fade.
 */
@Composable
fun FeaturedBackdrop(item: WatchItem?, show: Boolean) {
    val repo = (LocalContext.current.applicationContext as SlateApp).watchNext
    val animate = LocalAppearance.current.animations
    val url = item?.posterUrl
    // The picture shown stays until the next one is ready, so that one cross-fades into the
    // next instead of passing through the wallpaper.
    val backdrop by produceState<Backdrop?>(null, url) {
        // Moving along the row is not a reason to make, or drop, every picture on the way.
        delay(BACKDROP_SETTLE_MS)
        if (url == null) {
            value = null
            return@produceState
        }
        // The card's poster may still be on its way: look again for a few seconds. The previous
        // picture is held only for a moment, not behind another title's details meanwhile.
        repeat(12) { attempt ->
            repo.backdrop(url)?.let {
                value = it
                return@produceState
            }
            if (attempt == 1) value = null
            delay(500)
        }
        value = null
    }
    Crossfade(
        targetState = if (show) backdrop else null,
        animationSpec = tween(if (animate) 600 else 0),
        label = "backdrop",
    ) { shown ->
        if (shown != null) BackdropLayer(shown, animate)
    }
}

@Composable
private fun BackdropLayer(backdrop: Backdrop, animate: Boolean) {
    val tint = Color(backdrop.tint)
    val wide = backdrop.image.width >= backdrop.image.height * 1.3f
    // The share of the screen's width the picture takes, from the right.
    val share = if (wide) 0.8f else 0.46f
    // A slow drift towards the picture, once, as it arrives.
    val zoom = remember(backdrop) { Animatable(1f) }
    LaunchedEffect(backdrop, animate) {
        if (animate) zoom.animateTo(1.06f, tween(16_000, easing = LinearEasing))
    }
    Box(Modifier.fillMaxSize().background(tint)) {
        Image(
            bitmap = backdrop.image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(share)
                .align(Alignment.TopEnd)
                .graphicsLayer {
                    scaleX = zoom.value
                    scaleY = zoom.value
                },
            contentScale = ContentScale.Crop,
            // A film poster's top is mostly lettering; its middle is the picture.
            alignment = if (wide) Alignment.TopCenter else Alignment.Center,
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val start = 1f - share
                    // The picture's left side melts into the colour beside it...
                    val across = Brush.horizontalGradient(
                        0f to tint,
                        start to tint,
                        (start + share * 0.3f) to tint.copy(alpha = 0.45f),
                        (start + share * 0.6f) to tint.copy(alpha = 0f),
                        1f to tint.copy(alpha = 0f),
                    )
                    // ...and its lower half into the rows, which must stay easy to read.
                    val down = Brush.verticalGradient(
                        // The top bar sits on the picture: its own lettering must not show through.
                        0f to tint,
                        0.12f to tint.copy(alpha = 0.78f),
                        0.27f to tint.copy(alpha = 0f),
                        0.42f to tint.copy(alpha = 0f),
                        0.72f to tint.copy(alpha = 0.88f),
                        1f to tint,
                    )
                    // A touch of light from the top left, behind the title.
                    val glow = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.05f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.28f),
                        radius = (size.width * 0.45f).coerceAtLeast(1f),
                    )
                    onDrawBehind {
                        // Bright pictures are held down a little: the screen is for the rows.
                        drawRect(Color.Black.copy(alpha = 0.16f))
                        drawRect(across)
                        drawRect(down)
                        drawRect(glow)
                    }
                },
        )
    }
}

/**
 * The details of the featured entry, above the "continue watching" row: whose it is, its title,
 * the episode, and how far through it is.
 */
@Composable
fun FeaturedDetails(item: WatchItem, height: Dp, edge: Int, clock24h: Boolean) {
    val palette = LocalPalette.current
    val animate = LocalAppearance.current.animations
    Box(
        Modifier
            .fillMaxWidth()
            // At least this tall, so the row below stays put from one entry to the next; taller
            // only when a large text size needs it.
            .heightIn(min = height)
            .padding(start = edge.dp, end = edge.dp, bottom = 6.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        // By key, so that the entry's own updates (its progress) do not set off a cross-fade. The
        // entry being faded in is shown as it is now; the one fading out as it was when it came.
        val latest = rememberUpdatedState(item)
        Crossfade(targetState = item.key, animationSpec = tween(if (animate) 350 else 0), label = "featured") { key ->
            val held = remember { latest.value }
            val shown = if (key == latest.value.key) latest.value else held
            Column(Modifier.fillMaxWidth(0.56f), verticalArrangement = Arrangement.Bottom) {
                if (shown.appName.isNotEmpty()) {
                    Label(shown.appName.uppercase(), size = 11.5f, color = palette.accent, weight = FontWeight.Medium, spacing = 2.2f)
                    Spacer(Modifier.height(8.dp))
                }
                Label(shown.title, size = 34f, weight = FontWeight.SemiBold, maxLines = 2, spacing = -0.4f)
                if (shown.episode.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Label(shown.episode.replace(" ", "  ·  "), size = 14.5f, color = palette.textDim)
                }
                val progress = shown.progress
                if (progress != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Meter(progress, palette.accent, Modifier.width(150.dp).height(4.dp))
                        val left = shown.remainingMs
                        if (left != null) {
                            Spacer(Modifier.width(12.dp))
                            Label(remainingText(left, clock24h), size = 13f, color = palette.textDim)
                        }
                    }
                }
            }
        }
    }
}

/** "34 min left · ends 21:57", with the end time on the clock the user chose. */
@Composable
private fun remainingText(remainingMs: Long, clock24h: Boolean): String {
    // Kept current by the minute while it is on screen.
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
            value = LocalDateTime.now()
        }
    }
    val minutes = ((remainingMs + 59_999) / 60_000).coerceAtLeast(1)
    val left = if (minutes < 60) "$minutes min left" else "${minutes / 60} h ${minutes % 60} min left"
    val pattern = if (clock24h) "HH:mm" else "h:mm a"
    val ends = now.plusSeconds(remainingMs / 1000).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    return "$left  ·  ends $ends"
}

/** A thin bar filled to [fraction]. */
@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

// ---- status chips ----

/**
 * The TV's memory, processor, storage and connection, as small chips in the top bar. Read every
 * two seconds while the home screen is showing, and not at all otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusChips(memory: Boolean, processor: Boolean, storage: Boolean, network: Boolean) {
    if (!memory && !processor && !storage && !network) return
    val context = LocalContext.current
    val stats by produceState<DeviceStats?>(null) {
        val reader = DeviceStatsReader(context)
        var away = false
        while (true) {
            if (HomeActivity.isShowing) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        // Back home: the load is measured over a short moment, not the time away.
                        if (away) {
                            reader.restart()
                            delay(400)
                        }
                        reader.read()
                    }.getOrNull()
                }
                away = false
            } else {
                away = true
            }
            delay(2_000)
        }
    }
    val now = stats ?: return
    val accent = LocalPalette.current.accent
    // One line, from the right: a chip there is no room for is left out whole, not squeezed.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.Center,
        maxLines = 1,
    ) {
        if (memory && now.memoryFree != null && now.memoryTotal != null && now.memoryTotal > 0) {
            Chip {
                ChipLabel("RAM")
                Meter(1f - now.memoryFree.toFloat() / now.memoryTotal, loadColour(1f - now.memoryFree.toFloat() / now.memoryTotal, accent), Modifier.size(26.dp, 3.dp))
                ChipValue("${bytes(now.memoryFree)} free")
            }
        }
        if (processor && (now.cpuLoad != null || now.cpuMhz != null)) {
            Chip {
                ChipLabel("CPU")
                if (now.cpuLoad != null) {
                    Meter(now.cpuLoad / 100f, loadColour(now.cpuLoad / 100f, accent), Modifier.size(26.dp, 3.dp))
                    ChipValue("${now.cpuLoad}%")
                } else if (now.cpuMhz != null) {
                    ChipValue(if (now.cpuMhz >= 1000) String.format(Locale.getDefault(), "%.1f GHz", now.cpuMhz / 1000f) else "${now.cpuMhz} MHz")
                }
            }
        }
        if (network && now.link != null) {
            Chip {
                when (now.link) {
                    Link.WIFI -> {
                        WifiBars(now.bars)
                        ChipValue("Wi-Fi")
                    }
                    Link.ETHERNET -> ChipValue("Ethernet")
                    Link.OTHER -> ChipValue("Online")
                    Link.OFFLINE -> ChipValue("Offline", Color(0xFFFF8A80))
                }
            }
        }
        if (storage && now.storageFree != null && now.storageTotal != null && now.storageTotal > 0) {
            Chip {
                ChipLabel("STORAGE")
                Meter(1f - now.storageFree.toFloat() / now.storageTotal, loadColour(1f - now.storageFree.toFloat() / now.storageTotal, accent), Modifier.size(26.dp, 3.dp))
                ChipValue("${bytes(now.storageFree)} free")
            }
        }
    }
}

@Composable
private fun Chip(content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(30.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), shape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun ChipLabel(text: String) = Label(text, size = 9.5f, color = LocalPalette.current.textDim, weight = FontWeight.Medium, spacing = 1.2f)

@Composable
private fun ChipValue(text: String, color: Color = LocalPalette.current.text) = Label(text, size = 12f, color = color)

/** Four rising bars, as many lit as the signal is strong; all dim when Android does not say. */
@Composable
private fun WifiBars(bars: Int?) {
    val lit = LocalPalette.current.text
    val dim = Color.White.copy(alpha = 0.22f)
    Canvas(Modifier.size(14.dp, 11.dp)) {
        val gap = size.width * 0.08f
        val barWidth = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val barHeight = size.height * (i + 1) / 4f
            drawRect(
                color = if (bars != null && i < bars.coerceAtLeast(1)) lit else dim,
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

/** The accent colour until something is nearly full, then amber, then red. */
private fun loadColour(fraction: Float, accent: Color): Color = when {
    fraction >= 0.92f -> Color(0xFFFF8A80)
    fraction >= 0.8f -> Color(0xFFFFC66D)
    else -> accent
}

private fun bytes(count: Long): String {
    val gb = count / 1_073_741_824.0
    return when {
        gb >= 10 -> String.format(Locale.getDefault(), "%.0f GB", gb)
        gb >= 1 -> String.format(Locale.getDefault(), "%.1f GB", gb)
        else -> "${count / 1_048_576} MB"
    }
}
