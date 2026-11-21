package app.slate.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import app.slate.launcher.data.AppRepository
import app.slate.launcher.data.ConfigStore
import app.slate.launcher.data.PictureOfTheDay
import app.slate.launcher.data.WatchNextRepository
import app.slate.launcher.data.usesOnline
import app.slate.launcher.system.CrashGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SlateApp : Application() {

    lateinit var config: ConfigStore
        private set

    /** Created on first use, so a process started only for the Home button guard stays light. */
    private val lazyApps = lazy { AppRepository(this) { !_safeMode.value } }
    val apps: AppRepository by lazyApps

    /** The "continue watching" row's data. Does nothing until that row is switched on. */
    private val lazyWatchNext = lazy {
        WatchNextRepository(this, allowed = { !_safeMode.value }, visible = { HomeActivity.isShowing })
    }
    val watchNext: WatchNextRepository by lazyWatchNext

    /** The screensaver's online pictures. Fetches nothing unless that source is chosen. */
    val pictureOfTheDay: PictureOfTheDay by lazy {
        PictureOfTheDay(this) { !_safeMode.value && config.config.value.screensaver.source.usesOnline }
    }

    private val _safeMode = MutableStateFlow(false)

    /** While true, saved settings were set aside and user-supplied images are ignored. */
    val safeMode: StateFlow<Boolean> = _safeMode.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        _safeMode.value = CrashGuard.install(this)
        config = ConfigStore(this)
    }

    fun leaveSafeMode() {
        CrashGuard.leaveSafeMode()
        _safeMode.value = false
    }

    @Suppress("DEPRECATION") // The "running low" levels are no longer sent on Android 14+, but are on older TVs.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Either the launcher is off screen and the system wants memory back, or the device is
        // running low while it is on screen. Visible cards keep what they are showing.
        val pressing = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (pressing && lazyApps.isInitialized()) apps.dropArt()
        if (pressing && lazyWatchNext.isInitialized()) watchNext.dropPosters()
    }
}
