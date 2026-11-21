package app.slate.launcher.system

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import app.slate.launcher.HomeActivity
import app.slate.launcher.SlateApp

/**
 * Fallback for devices where the stock launcher cannot be disabled over ADB.
 *
 * It does two things and nothing else:
 *  1. swallows the Home key and opens Slate, so the stock home screen never flashes up;
 *  2. if the system's own home screen still reaches the foreground (after boot, or when an app
 *     exits), brings Slate back.
 *
 * It never inspects window content. It also knows when to get out of the way: during device
 * setup, on a locked or pinned screen, and whenever Slate fails to appear or the two home
 * screens start fighting, it stands down and lets the system handle Home normally.
 */
class HomeGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** The system's own home screens, as package + activity class. */
    private var stockHomes: Set<ComponentName> = emptySet()
    private var stockHomesReadAt = 0L

    /** When Slate is the system's own choice for Home, there is nothing to catch. */
    private var slateIsDefaultHome = false

    private val bounces = ArrayDeque<Long>()
    private var standDownUntil = 0L
    private var lastRedirectAt = 0L
    private var swallowingHome = false

    /** If Slate has not come to the front shortly after a redirect, stop insisting. */
    private val verifyRedirect = Runnable {
        if (!HomeActivity.isShowing && HomeActivity.lastResumedAt < lastRedirectAt) {
            standDown()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /** A redirect that arrived too soon after the last one, carried out a moment later. */
    private var delayedWasHomePress = false
    private val delayedRedirect = Runnable {
        val homePressed = delayedWasHomePress
        delayedWasHomePress = false
        if (mayAct()) redirect(homePressed)
    }

    override fun onServiceConnected() {
        refreshStockHomes()
        // If this process is back because Slate has just died, do not march the user straight
        // back into it: leave the Home button to the system for a while.
        if (CrashGuard.diedJustNow()) standDown()
        // Right after boot the stock home screen may already be up before this service is bound,
        // so nothing would announce it. Done once per boot only: connecting at any other time
        // (the guard being switched on, an update) must not yank the user out of what they are doing.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val bootCount = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val firstConnectThisBoot = bootCount >= 0 && prefs.getInt(KEY_BOOT, -1) != bootCount
        if (firstConnectThisBoot) prefs.edit().putInt(KEY_BOOT, bootCount).apply()
        val justBooted = SystemClock.elapsedRealtime() < BOOT_WINDOW_MS
        if (firstConnectThisBoot && justBooted && !slateIsDefaultHome && mayAct()) redirect(homePressed = false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Before anything else: when no stock home screen is switched on, only Slate's own
        // windows are reported, and this is then the only chance to notice one coming back.
        refreshStockHomesIfStale()
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val className = event.className?.toString() ?: return
        if (ComponentName(pkg, className) !in stockHomes || !mayAct()) return

        // Two home screens fighting over the foreground would never end; give up after a few rounds.
        val now = SystemClock.elapsedRealtime()
        bounces.addLast(now)
        while (bounces.isNotEmpty() && bounces.first() < now - BOUNCE_WINDOW_MS) bounces.removeFirst()
        if (bounces.size > BOUNCE_LIMIT) {
            standDown()
            return
        }
        redirect(homePressed = false)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Decide once per press, so a press is either swallowed whole or not at all.
                if (event.repeatCount == 0) {
                    refreshStockHomesIfStale()
                    swallowingHome = interceptEnabled() && !slateIsDefaultHome && mayAct()
                }
                swallowingHome
            }
            KeyEvent.ACTION_UP -> {
                val swallowed = swallowingHome
                swallowingHome = false
                if (swallowed && !event.isCanceled) redirect(homePressed = true)
                swallowed
            }
            else -> false
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(verifyRedirect)
        handler.removeCallbacks(delayedRedirect)
        super.onDestroy()
    }

    private fun interceptEnabled(): Boolean =
        (application as? SlateApp)?.config?.config?.value?.interceptHomeKey == true

    /** False whenever Android itself would not let Home leave the current screen. */
    private fun mayAct(): Boolean {
        if (SystemClock.elapsedRealtime() < standDownUntil) return false
        return runCatching {
            val resolver = contentResolver
            if (Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 1) == 0) return false
            if (Settings.Secure.getInt(resolver, USER_SETUP_COMPLETE, 1) == 0) return false
            if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false
            val lockTask = getSystemService(ActivityManager::class.java)?.lockTaskModeState
            lockTask == null || lockTask == ActivityManager.LOCK_TASK_MODE_NONE
        }.getOrDefault(false)
    }

    /**
     * Brings Slate to the front. Only an actual press of Home should also send it back to the
     * top; a stock home screen merely showing up should leave Slate as the user left it.
     */
    private fun redirect(homePressed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        handler.removeCallbacks(delayedRedirect)
        val wait = lastRedirectAt + MIN_GAP_MS - now
        if (wait > 0) {
            // Too soon after the last one. Do it shortly instead of dropping it, or the stock
            // home screen would be left in front.
            delayedWasHomePress = delayedWasHomePress || homePressed
            handler.postDelayed(delayedRedirect, wait)
            return
        }
        lastRedirectAt = now
        val intent = Intent(this, HomeActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // This one replaces any that was waiting, and inherits whether that was a Home press.
        val pressed = homePressed || delayedWasHomePress
        delayedWasHomePress = false
        if (!pressed) intent.addCategory(HomeActivity.CATEGORY_QUIET)
        if (runCatching { startActivity(intent) }.isFailure) {
            standDown()
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        handler.removeCallbacks(verifyRedirect)
        // Before Android 12 the system may hold a start back for five seconds after Home.
        handler.postDelayed(verifyRedirect, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) VERIFY_MS else VERIFY_OLD_MS)
    }

    private fun standDown() {
        standDownUntil = SystemClock.elapsedRealtime() + STAND_DOWN_MS
        bounces.clear()
        swallowingHome = false
        delayedWasHomePress = false
        handler.removeCallbacks(verifyRedirect)
        handler.removeCallbacks(delayedRedirect)
    }

    private fun refreshStockHomesIfStale() {
        if (SystemClock.elapsedRealtime() - stockHomesReadAt > REFRESH_MS) refreshStockHomes()
    }

    /**
     * Finds the home screens that ship with the device. Launchers the user installed themselves
     * are left alone, as are boot and setup screens. Also narrows the events this service
     * receives to those packages, so no other app's windows are reported to it at all.
     */
    private fun refreshStockHomes() {
        stockHomesReadAt = SystemClock.elapsedRealtime()
        slateIsDefaultHome = SystemStatus.isDefaultHome(this)
        val found = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo }
                .filter { info ->
                    val isSystem = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    isSystem &&
                        info.packageName != packageName &&
                        !info.name.endsWith("FallbackHome") &&
                        SETUP_HINTS.none { info.packageName.contains(it, ignoreCase = true) }
                }
                .flatMap { info ->
                    listOfNotNull(info.name, info.targetActivity).map { ComponentName(info.packageName, it) }
                }
                .toSet()
        }.getOrDefault(emptySet())
        if (found == stockHomes && stockHomes.isNotEmpty()) return
        stockHomes = found
        runCatching {
            val info = serviceInfo ?: return@runCatching
            // An empty list would mean "every package", so fall back to our own (no events).
            info.packageNames = found.map { it.packageName }.distinct().ifEmpty { listOf(packageName) }.toTypedArray()
            serviceInfo = info
        }
    }

    private companion object {
        const val REFRESH_MS = 60_000L
        const val MIN_GAP_MS = 400L
        const val VERIFY_MS = 3_000L
        const val VERIFY_OLD_MS = 8_000L
        const val PREFS = "home-guard"
        const val KEY_BOOT = "handled-boot"
        const val BOUNCE_WINDOW_MS = 10_000L
        const val BOUNCE_LIMIT = 4
        const val STAND_DOWN_MS = 60_000L
        const val BOOT_WINDOW_MS = 120_000L
        const val USER_SETUP_COMPLETE = "user_setup_complete"
        val SETUP_HINTS = listOf("setupwizard", "setupwraith", "provision")
    }
}
