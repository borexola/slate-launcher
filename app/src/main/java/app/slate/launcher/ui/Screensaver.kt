package app.slate.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.slate.launcher.SlateApp
import app.slate.launcher.data.Photos
import app.slate.launcher.data.SaverSource
import app.slate.launcher.data.Wallpaper
import app.slate.launcher.data.usesOnline
import app.slate.launcher.data.usesPhotos
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One picture to show, and the line that credits it (pictures of the day only). */
private data class Slide(val file: File, val credit: String?)

/** A picture on screen. Not a data class: each one shown is a change, even of the same file. */
private class Shown(val file: File, val image: ImageBitmap, val credit: String?, val crop: Boolean)

/** Photos pushed or picked while the screensaver runs show up after this long. */
private const val RELIST_MS = 10 * 60_000L

/** How often the screensaver asks for new pictures of the day (fetched at most hourly anyway). */
private const val FETCH_MS = 30 * 60_000L

/** After a whole round of pictures that could not be shown, wait this long before trying again. */
private const val RETRY_MS = 60_000L

private const val SCREEN_ASPECT = 16f / 9f

/**
 * The screensaver's slideshow. Pictures fill the screen when their shape is close to the
 * screen's, and are shown whole on black otherwise. The clock and the credit line move a little
 * every minute, so that nothing stays in one place on the screen for hours.
 */
@Composable
fun Screensaver(app: SlateApp) {
    val config by app.config.config.collectAsState()
    val saver = config.screensaver
    val appearance = config.appearance
    val safeMode by app.safeMode.collectAsState()
    val onlineVersion by app.pictureOfTheDay.version.collectAsState()

    LaunchedEffect(saver.source, safeMode) {
        if (safeMode || !saver.source.usesOnline) return@LaunchedEffect
        while (true) {
            app.pictureOfTheDay.refresh()
            delay(FETCH_MS)
        }
    }

    val slides by produceState<List<Slide>?>(null, saver.source, safeMode, onlineVersion) {
        while (true) {
            value = withContext(Dispatchers.IO) { slidesFor(app, saver.source, safeMode) }
            delay(RELIST_MS)
        }
    }

    var shown by remember { mutableStateOf<Shown?>(null) }
    var unusable by remember { mutableStateOf(false) }
    LaunchedEffect(slides, saver.shuffle, saver.seconds) {
        val all = slides ?: return@LaunchedEffect
        unusable = false
        if (all.isEmpty()) {
            shown = null
            unusable = false
            return@LaunchedEffect
        }
        val hold = saver.seconds * 1_000L
        // A picture already on screen (the list changed under it) keeps its time.
        val current = shown
        if (current != null && all.any { it.file == current.file }) delay(hold)
        var order = if (saver.shuffle) all.shuffled() else all
        var next = shown?.let { now -> order.indexOfFirst { it.file == now.file } + 1 } ?: 0
        var failed = 0
        while (true) {
            if (next >= order.size) {
                next = 0
                if (saver.shuffle) {
                    order = all.shuffled()
                    // Never the same picture twice in a row across the reshuffle.
                    if (order.size > 1 && order[0].file == shown?.file) order = order.drop(1) + order[0]
                }
            }
            val slide = order[next++]
            val image = withContext(Dispatchers.IO) { Wallpaper.loadFile(slide.file, contain = true) }
            if (image == null) {
                if (++failed >= order.size) {
                    shown = null
                    unusable = true
                    failed = 0
                    delay(RETRY_MS)
                }
                continue
            }
            failed = 0
            unusable = false
            val aspect = image.width.toFloat() / image.height
            shown = Shown(slide.file, image, slide.credit, crop = abs(aspect / SCREEN_ASPECT - 1f) < 0.2f)
            delay(hold)
        }
    }

    // Moves the text by a few dp each minute, round a small square.
    val drift by produceState(0) {
        while (true) {
            delay(60_000)
            value = (value + 1) % 9
        }
    }
    val shiftX = ((drift % 3) * 8).dp
    val shiftY = ((drift / 3) * -8).dp

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(
            targetState = shown,
            animationSpec = tween(if (appearance.animations) 1_200 else 0),
            label = "slide",
        ) { picture ->
            if (picture != null) {
                Image(
                    bitmap = picture.image,
                    contentDescription = null,
                    contentScale = if (picture.crop) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val note = when {
            // Still loading, or a picture on its way: nothing to say yet.
            slides == null || shown != null || (slides!!.isNotEmpty() && !unusable) -> null
            safeMode -> "Slate is in safe mode, so no pictures are shown."
            unusable -> "None of the pictures could be shown. Use JPEG or PNG images within the limits in Slate's README."
            saver.source == SaverSource.PHOTOS -> "No photos yet. Add some under Customise > Screensaver on the home screen, " +
                "or from a computer with scripts/slate.sh photos add <folder>."
            saver.source == SaverSource.ONLINE -> "No picture of the day saved yet. They are fetched from Wikimedia Commons " +
                "while the TV is online."
            else -> "No pictures yet. Add photos under Customise > Screensaver; pictures of the day are fetched from " +
                "Wikimedia Commons while the TV is online."
        }
        if (note != null) {
            BasicText(
                text = note,
                style = textStyle(18f, Color(0xB3FFFFFF), TextAlign.Center),
                modifier = Modifier.align(Alignment.Center).offset(shiftX, shiftY).widthIn(max = 640.dp).padding(24.dp),
            )
        }

        val credit = shown?.credit
        val clock = saver.clock
        if (credit != null || clock) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
            )
        }
        if (clock) {
            val locale = Locale.getDefault()
            val format = remember(appearance.clock24h, locale) {
                DateTimeFormatter.ofPattern(if (appearance.clock24h) "HH:mm" else "h:mm", locale)
            }
            val time by produceState(LocalTime.now()) {
                while (true) {
                    value = LocalTime.now()
                    delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
                }
            }
            BasicText(
                text = time.format(format),
                style = textStyle(56f, Color.White, TextAlign.End, FontWeight.Light),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 64.dp, bottom = 40.dp).offset(-shiftX, shiftY),
            )
        }
        if (credit != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 56.dp, bottom = 44.dp, end = if (clock) 260.dp else 56.dp)
                    .offset(shiftX, shiftY),
            ) {
                BasicText(
                    text = "Picture of the Day",
                    style = textStyle(12f, Color(0xB3FFFFFF)),
                    maxLines = 1,
                )
                BasicText(
                    text = credit,
                    style = textStyle(15f, Color(0xE6FFFFFF)),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun textStyle(size: Float, color: Color, align: TextAlign = TextAlign.Start, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        textAlign = align,
        shadow = Shadow(Color.Black, blurRadius = 8f),
    )

/** The pictures the chosen source has right now. Call off the main thread. */
private fun slidesFor(app: SlateApp, source: SaverSource, safeMode: Boolean): List<Slide> {
    if (safeMode) return emptyList()
    val photos = if (source.usesPhotos) Photos.list(app).map { Slide(it, null) } else emptyList()
    val online = if (source.usesOnline) app.pictureOfTheDay.saved().map { Slide(it.file, it.credit) } else emptyList()
    return photos + online
}
