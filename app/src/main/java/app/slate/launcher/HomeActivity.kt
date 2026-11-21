package app.slate.launcher

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.slate.launcher.system.CrashGuard
import app.slate.launcher.ui.HomeScreen
import app.slate.launcher.ui.PanelWindows

class HomeActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    /** True from onResume until onStop, i.e. while the home screen is what the user sees. */
    private var onScreen = false

    /** True from onResume until onPause. */
    private var resumed = false

    private val main = Handler(Looper.getMainLooper())
    private var focusTries = 0
    private val focusCheck = Runnable { checkFocus() }
    private val focusableAgain = Runnable {
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything that could fail, so that a start which dies here is still noticed.
        CrashGuard.onScreen()
        super.onCreate(savedInstanceState)
        setContent { HomeScreen(viewModel) }
    }

    override fun onStart() {
        startedInstances++
        CrashGuard.onScreen()
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.removeCallbacks(focusableAgain)
        focusableAgain.run()
        // See checkFocus: a layout pass for this window right after coming back, and a look
        // shortly afterwards at whether that was enough.
        window.attributes = window.attributes
        focusTries = 0
        main.removeCallbacks(focusCheck)
        main.postDelayed(focusCheck, FOCUS_WAITS_MS[0])
        onScreen = true
        leavingSince = 0L
        isShowing = true
        lastResumedAt = SystemClock.elapsedRealtime()
        viewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(focusCheck)
        // Never left pending: the window must not stay unable to take the remote's keys.
        main.removeCallbacks(focusableAgain)
        focusableAgain.run()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) main.removeCallbacks(focusCheck)
    }

    /**
     * Android 14 can bring the home screen back without giving any window the remote's keys:
     * an app is opened, closes itself before it has drawn anything, and the home screen is
     * shown again, visible and resumed, but with no window in focus. (Google's own launcher
     * is left the same way.) Every key press then waits for a window that never comes, until
     * Android declares the launcher unresponsive and kills it.
     *
     * Nothing is wrong on this side, so all that can be done is to have Android work the
     * focus out again. A layout pass for this window is asked for on every return to the
     * screen (in onResume), which is often enough. Should the window still be without focus
     * a moment later, its ability to take keys is switched off and back on, which always has
     * been; that is tried a few times, and only while no window of Slate's has focus: one of
     * Slate's own panels holding it is as it should be, and ends the checks.
     */
    private fun checkFocus() {
        if (!resumed || isFinishing || isDestroyed || hasWindowFocus() || PanelWindows.anyFocused()) return
        Log.i("Slate", "on screen without window focus; asking Android to work it out again (try ${focusTries + 1})")
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        main.removeCallbacks(focusableAgain)
        main.postDelayed(focusableAgain, FOCUS_NUDGE_MS)
        focusTries++
        if (focusTries < FOCUS_WAITS_MS.size) main.postDelayed(focusCheck, FOCUS_WAITS_MS[focusTries])
    }

    override fun onStop() {
        super.onStop()
        onScreen = false
        leavingSince = 0L
        // Counted per instance: should Android ever run two of this activity, the older one
        // stopping must not announce that the home screen has gone.
        startedInstances = (startedInstances - 1).coerceAtLeast(0)
        if (startedInstances == 0) {
            isShowing = false
            CrashGuard.offScreen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(focusCheck)
        main.removeCallbacks(focusableAgain)
        // Created but never started: the marker written in onCreate would otherwise linger.
        if (startedInstances == 0) CrashGuard.offScreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on the home screen: jump back to the top. Coming back from
        // another app leaves the screen exactly where it was. The activity's lifecycle state
        // cannot tell the two apart (newer Android versions restart the activity before
        // delivering the intent), hence the flag: it is still false when returning from an app.
        //
        // One more case: an app has just been opened from here but has not covered the screen
        // yet (a slow cold start). Home pressed in that gap is still "coming back from an app".
        //
        // And the Home button guard bringing Slate back to the front is not a Home press at all.
        val leaving = leavingSince != 0L && SystemClock.elapsedRealtime() - leavingSince < LEAVING_MS
        val quiet = intent.hasCategory(CATEGORY_QUIET)
        if (onScreen && !leaving && !quiet && intent.hasCategory(Intent.CATEGORY_HOME)) {
            viewModel.homeSignal.tryEmit(Unit)
        }
    }

    companion object {
        /** Read by the Home button guard, which runs in the same process. */
        @Volatile
        var isShowing = false
            private set

        @Volatile
        var lastResumedAt = 0L
            private set

        private const val LEAVING_MS = 8_000L

        /** How long after the previous look to check again that a window has the keys. */
        private val FOCUS_WAITS_MS = longArrayOf(300, 500, 900, 1_300, 2_000, 3_000)

        /** Long enough for the change to reach Android as one of its own, before it is undone. */
        private const val FOCUS_NUDGE_MS = 100L

        /** Touched on the main thread only. */
        private var startedInstances = 0

        /** Marks an intent that should bring the home screen forward without resetting it. */
        const val CATEGORY_QUIET = "app.slate.launcher.category.QUIET"

        @Volatile
        private var leavingSince = 0L

        /** Call when Slate has just opened another app or a system screen. */
        fun noteLeaving() {
            leavingSince = SystemClock.elapsedRealtime()
        }
    }
}
