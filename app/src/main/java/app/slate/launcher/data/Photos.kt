package app.slate.launcher.data

import android.content.Context
import android.net.Uri
import app.slate.launcher.SlateApp
import java.io.File

/**
 * The screensaver's own photos. They live in two places:
 *
 *  - pushed from a computer with `scripts/slate.sh photos add <folder>`, into `photos/` in the
 *    app's external folder (`/sdcard/Android/data/app.slate.launcher/files/photos/`);
 *  - picked on the TV with the system file picker, copied into `photos/` in private storage.
 *
 * Like the wallpaper, none of them is trusted: on older Android versions other apps can write to
 * the external folder. A photo is only ever read within the wallpaper's limits and cleaned by
 * [Untrusted] before the system decodes it (see [Wallpaper.loadFile]), and an import is checked
 * the same way before it is kept.
 */
object Photos {

    /** The most photos the screensaver goes through; beyond that, the first ones by name. */
    const val MAX_LISTED = 2_000

    /** The most photos that may be picked on the TV and kept in private storage. */
    const val MAX_PICKED = 500

    /** Free space an import leaves on the TV, so that it never fills the storage up. */
    private const val SPARE_BYTES = 300L * 1024 * 1024

    private val PUSHED_EXTENSIONS = setOf("jpg", "jpeg", "png")
    private val PICKED_NAME = Regex("^p[0-9]{13}-[0-9]{4,6}\\.img$")

    private fun pushedDir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, "photos") }
    private fun pickedDir(context: Context) = File(context.filesDir, "photos")

    private fun safeMode(context: Context) = (context.applicationContext as? SlateApp)?.safeMode?.value == true

    private fun pushed(context: Context): List<File> = pushedDir(context)?.let { dir ->
        // Made by the app, so that it belongs to the app: one the computer's adb made might not
        // be readable for it on some Android versions.
        if (!dir.exists()) dir.mkdirs()
        runCatching { dir.listFiles() }.getOrNull().orEmpty().filter { file ->
            file.extension.lowercase() in PUSHED_EXTENSIONS && file.isFile && file.length() in 1..Wallpaper.MAX_BYTES
        }.sortedBy { it.name }
    }.orEmpty()

    private fun picked(context: Context): List<File> =
        runCatching { pickedDir(context).listFiles() }.getOrNull().orEmpty().filter { file ->
            PICKED_NAME.matches(file.name) && file.isFile && file.length() in 1..Wallpaper.MAX_BYTES
        }.sortedBy { it.name }

    /**
     * Every photo the screensaver may show: those from a computer by name, then those picked on
     * the TV in the order they were added. None in safe mode. Call off the main thread.
     */
    fun list(context: Context): List<File> =
        if (safeMode(context)) emptyList() else (pushed(context) + picked(context)).take(MAX_LISTED)

    /** How many photos came from a computer, and how many were picked on the TV. Call off the main thread. */
    fun count(context: Context): Pair<Int, Int> = pushed(context).size to picked(context).size

    /** What became of an import: how many were added, and how many could not be used. */
    class Imported(val added: Int, val refused: Int, val full: Boolean)

    /**
     * Copies picked images into private storage, each checked like a wallpaper. Stops when
     * [MAX_PICKED] are kept or the TV is running out of space ([Imported.full]).
     * Call off the main thread.
     */
    suspend fun import(context: Context, uris: List<Uri>): Imported {
        val dir = pickedDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return Imported(0, uris.size, full = false)
        var added = 0
        var refused = 0
        var kept = picked(context).size
        for ((index, uri) in uris.withIndex()) {
            if (kept >= MAX_PICKED || dir.usableSpace < SPARE_BYTES + Wallpaper.MAX_BYTES) {
                return Imported(added, refused + uris.size - index, full = true)
            }
            val target = File(dir, "p${System.currentTimeMillis().toString().padStart(13, '0')}-${index.toString().padStart(4, '0')}.img")
            if (!target.exists() && Wallpaper.importTo(context, uri, target)) {
                added++
                kept++
            } else {
                refused++
            }
        }
        return Imported(added, refused, full = false)
    }

    /** Removes the photos picked on the TV. Returns whether none is left. Call off the main thread. */
    fun clearPicked(context: Context): Boolean {
        val dir = pickedDir(context)
        runCatching { dir.listFiles() }.getOrNull().orEmpty().forEach { it.delete() }
        return picked(context).isEmpty()
    }

    /** Removes every photo, picked or pushed. Returns whether none is left. Call off the main thread. */
    fun clearAll(context: Context): Boolean {
        val pickedGone = clearPicked(context)
        pushedDir(context)?.let { dir -> runCatching { dir.listFiles() }.getOrNull().orEmpty().forEach { it.delete() } }
        return pickedGone && pushed(context).isEmpty()
    }
}
