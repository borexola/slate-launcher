package app.slate.launcher.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.provider.Settings
import app.slate.launcher.HomeActivity
import app.slate.launcher.data.Limits
import app.slate.launcher.data.cleanText

data class TvInput(val id: String, val label: String)

/** Read-only checks on the device and shortcuts into system screens. */
object SystemStatus {

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** The package Android currently sends the Home button to; "android" means it would ask. */
    fun defaultHomePackage(context: Context): String? = runCatching {
        context.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()

    fun isDefaultHome(context: Context): Boolean = defaultHomePackage(context) == context.packageName

    /** Other enabled home screens, i.e. what could still take over from Slate. */
    fun otherHomePackages(context: Context): List<String> = runCatching {
        context.packageManager.queryIntentActivities(homeIntent(), 0)
            .mapNotNull { it.activityInfo }
            // Not a home screen in any useful sense: the placeholder Android shows while booting.
            .filter { it.packageName != context.packageName && !it.name.endsWith("FallbackHome") }
            .map { it.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    fun isGuardEnabled(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val guard = ComponentName(context, HomeGuardService::class.java)
        enabled.split(':').any { ComponentName.unflattenFromString(it) == guard }
    }.getOrDefault(false)

    /**
     * Whether Android uses Slate's screensaver: switched on, and Slate's the one chosen. Null if
     * Android would not say (these settings are not meant for apps to read on every version).
     */
    fun isScreensaver(context: Context): Boolean? = try {
        val resolver = context.contentResolver
        val on = Settings.Secure.getInt(resolver, "screensaver_enabled", 1) != 0
        val chosen = Settings.Secure.getString(resolver, "screensaver_components").orEmpty()
        val mine = ComponentName(context, ScreensaverService::class.java)
        on && chosen.split(',').any { ComponentName.unflattenFromString(it.trim()) == mine }
    } catch (e: Exception) {
        null
    }

    /** HDMI and other physical inputs. Empty on streaming boxes and sticks. */
    fun tvInputs(context: Context): List<TvInput> = runCatching {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return emptyList()
        manager.tvInputList
            .filter { it.isPassthroughInput && !it.isHidden(context) }
            .map { input ->
                val label = input.loadCustomLabel(context)?.toString()
                    ?: input.loadLabel(context)?.toString()
                    ?: input.id
                TvInput(input.id, cleanText(label, Limits.MAX_LABEL).ifEmpty { "Input" })
            }
    }.getOrDefault(emptyList())

    fun openInput(context: Context, input: TvInput): Boolean = start(
        context,
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id)),
    )

    fun openSystemSettings(context: Context): Boolean = start(context, Intent(Settings.ACTION_SETTINGS))

    fun openAccessibilitySettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) || openSystemSettings(context)

    fun openAppInfo(context: Context, pkg: String): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)),
    )

    fun uninstall(context: Context, pkg: String): Boolean =
        start(context, Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))

    /**
     * Starts a system screen. These intents are implicit, so any app could register for them and
     * show a look-alike; where the system itself offers a handler, that one is addressed directly.
     */
    private fun start(context: Context, intent: Intent): Boolean = try {
        val flags = PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY
        val system = context.packageManager.queryIntentActivities(intent, flags)
            .mapNotNull { it.activityInfo }
            // Never a chooser: it would have to be started by name, without the list to choose from.
            .firstOrNull { it.packageName != "android" && !it.name.contains("Resolver") }
        if (system != null) intent.setClassName(system.packageName, system.name)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        HomeActivity.noteLeaving()
        true
    } catch (e: Exception) {
        false
    }
}
