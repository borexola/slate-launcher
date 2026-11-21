package app.slate.launcher.data

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    /** Declares a TV (leanback) entry point, as opposed to a sideloaded phone-style app. */
    val isTvApp: Boolean,
)

/** Artwork for one card: either a wide banner that fills the card, or a square icon. */
class AppArt(val image: ImageBitmap, val isBanner: Boolean, val bytes: Int)

/**
 * Installed apps, their artwork, and launching them.
 *
 * [artAllowed] is false in safe mode. Then no images are decoded at all, neither files the user
 * supplied nor the icons of installed apps, because a bad image may be what keeps killing the
 * launcher. Cards show names only, which is enough to find and remove the offender.
 */
class AppRepository(
    private val context: Context,
    private val artAllowed: () -> Boolean,
) {

    private val pm = context.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    /** False until the first query has finished, so "no apps" is not shown while loading. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Bumped whenever artwork may have changed; cards reload when it does. */
    private val _artVersion = MutableStateFlow(0)
    val artVersion: StateFlow<Int> = _artVersion.asStateFlow()

    private val art = object : LruCache<String, AppArt>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: AppArt): Int = max(1, value.bytes / 1024)
    }

    /** Decoding is memory-hungry; a screenful of cards must not all decode at the same moment. */
    private val decodeGate = Semaphore(2)

    @Volatile
    private var customArtStamp = ""

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            if (pkg != null) invalidateArt(pkg) else invalidateArt()
            refresh()
        }
    }

    init {
        val packageChanges = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // Apps on removable storage come and go without per-package broadcasts.
        val storageChanges = IntentFilter().apply {
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        }
        runCatching {
            ContextCompat.registerReceiver(context, packageReceiver, packageChanges, ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(context, packageReceiver, storageChanges, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        // One worker handles refreshes in order; requests that pile up collapse into one.
        scope.launch {
            for (request in refreshRequests) {
                val found = runCatching { query() }.getOrNull()
                if (found != null) {
                    _apps.value = found
                    _loaded.value = true
                }
                runCatching { checkCustomArt(_apps.value) }
            }
        }
        refresh()
    }

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    private fun query(): List<AppEntry> {
        val found = LinkedHashMap<String, AppEntry>()
        fun collect(category: String, tv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == context.packageName || pkg in found) continue
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("")
                found[pkg] = AppEntry(
                    packageName = pkg,
                    // An app chooses its own label; keep it short and printable.
                    label = cleanText(label, Limits.MAX_LABEL).ifEmpty { pkg.take(Limits.MAX_LABEL) },
                    component = ComponentName(pkg, activity.name),
                    isTvApp = tv,
                )
            }
        }
        // TV entry points win; phone-style launchers fill in apps the stock home screen hides.
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, tv = true)
        collect(Intent.CATEGORY_LAUNCHER, tv = false)
        return found.values.sortedBy { it.label.lowercase() }
    }

    fun launch(app: AppEntry): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_MAIN).setComponent(app.component)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- artwork ----

    fun cachedArt(app: AppEntry, style: CardStyle): AppArt? = art.get(artKey(app.packageName, style))

    suspend fun loadArt(app: AppEntry, style: CardStyle): AppArt? {
        cachedArt(app, style)?.let { return it }
        val version = _artVersion.value
        return withContext(Dispatchers.IO) {
            decodeGate.withPermit {
                // runCatching also covers OutOfMemoryError: one bad image must not end the launcher.
                val loaded = runCatching { render(app, style) }.getOrNull()
                // Skip the cache if artwork was invalidated while this was loading.
                if (loaded != null && version == _artVersion.value) art.put(artKey(app.packageName, style), loaded)
                loaded
            }
        }
    }

    /** Forgets cached artwork, for one package or for all, and tells cards to reload. */
    fun invalidateArt(pkg: String? = null) {
        // Version first, so a load already under way sees the change and does not cache its result.
        _artVersion.update { it + 1 }
        if (pkg == null) {
            art.evictAll()
        } else {
            CardStyle.entries.forEach { art.remove(artKey(pkg, it)) }
        }
    }

    /** Frees cached artwork under memory pressure. Visible cards keep what they show. */
    fun dropArt() {
        art.evictAll()
    }

    /**
     * Deletes the custom artwork of every installed app. Returns whether none is left.
     * Call off the main thread.
     */
    fun removeCustomArt(): Boolean {
        val dir = customArtDir() ?: return true
        val files = _apps.value.map { File(dir, "${it.packageName}.png") }
        files.forEach { it.delete() }
        refresh()
        return files.none { it.exists() }
    }

    private fun artKey(pkg: String, style: CardStyle) = "$pkg|${style.name}"

    private fun render(app: AppEntry, style: CardStyle): AppArt? {
        if (!artAllowed()) return null
        customArt(app.packageName)?.let { return it }
        if (style == CardStyle.BANNER) {
            val banner = runCatching { pm.getActivityBanner(app.component) }.getOrNull()
                ?: runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
            if (banner != null) return banner.toArt(BANNER_W, BANNER_H, isBanner = true)
        }
        val icon = runCatching { pm.getActivityIcon(app.component) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
            ?: return null
        return icon.toArt(ICON_SIZE, ICON_SIZE, isBanner = false)
    }

    private fun Drawable.toArt(width: Int, height: Int, isBanner: Boolean): AppArt {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, width, height)
        draw(Canvas(bitmap))
        return AppArt(bitmap.asImageBitmap(), isBanner, bitmap.byteCount)
    }

    private fun customArtDir(): File? = context.getExternalFilesDir(null)?.let { File(it, "icons") }

    /**
     * Notices added, replaced or removed custom artwork and reloads artwork if so. Only the
     * files that could matter are looked at (one per installed app), never the whole folder,
     * which someone else may have filled with anything.
     */
    private fun checkCustomArt(installed: List<AppEntry>) {
        val dir = customArtDir()
        val stamp = if (!artAllowed()) {
            "off"
        } else if (dir == null) {
            ""
        } else {
            installed.mapNotNull { app ->
                val file = File(dir, "${app.packageName}.png")
                if (file.isFile) "${file.name}:${file.lastModified()}:${file.length()}" else null
            }.joinToString("|")
        }
        if (stamp != customArtStamp) {
            customArtStamp = stamp
            invalidateArt()
        }
    }

    /**
     * A PNG named after the package in `files/icons/` replaces that app's artwork.
     *
     * The file is user-supplied and, on older Android versions, writable by other apps, so it is
     * never trusted. It is read once with a size cap, reduced to its picture data alone (see
     * [Untrusted]), decoded at reduced size, and redrawn into a bitmap of exactly the size a
     * card needs.
     */
    private fun customArt(pkg: String): AppArt? {
        val file = File(customArtDir() ?: return null, "$pkg.png")
        if (!file.isFile) return null
        val raw = Untrusted.read(file, MAX_CUSTOM_BYTES) ?: return null
        val image = Untrusted.cleanImage(raw, MAX_CUSTOM_SIDE, MAX_CUSTOM_PIXELS, MAX_CUSTOM_PIXELS) ?: return null
        val bytes = image.bytes
        // The size as it will be shown: orientations 5 to 8 turn the picture on its side.
        val turned = image.orientation in 5..8
        val width = if (turned) image.height else image.width
        val height = if (turned) image.width else image.height

        val isBanner = width >= height * 1.3f
        val targetW = if (isBanner) BANNER_W else ICON_SIZE
        val targetH = if (isBanner) BANNER_H else ICON_SIZE
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
        // A very long, thin image keeps one side large after sampling; bound the total too.
        while ((width / sample).toLong() * (height / sample) > MAX_DECODED_PIXELS) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = Untrusted.upright(
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null,
            image.orientation,
        )
        try {
            val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val source: Rect
            val destination: Rect
            if (isBanner) {
                // Centre-crop to the card's shape.
                val scale = max(targetW.toFloat() / decoded.width, targetH.toFloat() / decoded.height)
                val cropW = min(decoded.width, (targetW / scale).roundToInt().coerceAtLeast(1))
                val cropH = min(decoded.height, (targetH / scale).roundToInt().coerceAtLeast(1))
                val left = (decoded.width - cropW) / 2
                val top = (decoded.height - cropH) / 2
                source = Rect(left, top, left + cropW, top + cropH)
                destination = Rect(0, 0, targetW, targetH)
            } else {
                // Fit the whole icon inside the square.
                val scale = min(targetW.toFloat() / decoded.width, targetH.toFloat() / decoded.height)
                val fitW = (decoded.width * scale).roundToInt().coerceIn(1, targetW)
                val fitH = (decoded.height * scale).roundToInt().coerceIn(1, targetH)
                val left = (targetW - fitW) / 2
                val top = (targetH - fitH) / 2
                source = Rect(0, 0, decoded.width, decoded.height)
                destination = Rect(left, top, left + fitW, top + fitH)
            }
            Canvas(out).drawBitmap(decoded, source, destination, Paint(Paint.FILTER_BITMAP_FLAG))
            return AppArt(out.asImageBitmap(), isBanner, out.byteCount)
        } finally {
            decoded.recycle()
        }
    }

    /** A sixth of the app's heap budget, between 8 and 24 MB. */
    private fun cacheSizeKb(): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val heapMb = manager?.memoryClass ?: 64
        return (heapMb * 1024 / 6).coerceIn(8 * 1024, 24 * 1024)
    }

    private companion object {
        const val BANNER_W = 400
        const val BANNER_H = 225
        const val ICON_SIZE = 144
        const val MAX_CUSTOM_BYTES = 2L * 1024 * 1024
        const val MAX_CUSTOM_SIDE = 4096
        const val MAX_CUSTOM_PIXELS = 4_000_000L
        const val MAX_DECODED_PIXELS = 1_000_000L
    }
}
