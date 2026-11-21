package app.slate.launcher

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.slate.launcher.data.AppEntry
import app.slate.launcher.data.AppOverride
import app.slate.launcher.data.Appearance
import app.slate.launcher.data.BackgroundKind
import app.slate.launcher.data.LauncherConfig
import app.slate.launcher.data.Photos
import app.slate.launcher.data.SaverSource
import app.slate.launcher.data.Screensaver
import app.slate.launcher.data.usesOnline
import app.slate.launcher.data.Limits
import app.slate.launcher.data.Section
import app.slate.launcher.data.SectionStyle
import app.slate.launcher.data.Wallpaper
import app.slate.launcher.data.WatchItem
import app.slate.launcher.system.SystemStatus
import app.slate.launcher.system.TvInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A section with its apps resolved against what is installed, in display order. */
data class ResolvedSection(val section: Section, val apps: List<AppEntry>)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val slate = application as SlateApp
    private val store = slate.config
    val repo = slate.apps
    val watch = slate.watchNext

    val config: StateFlow<LauncherConfig> = store.config
    val installed: StateFlow<List<AppEntry>> = repo.apps
    val loaded: StateFlow<Boolean> = repo.loaded
    val safeMode: StateFlow<Boolean> = slate.safeMode

    val sections: StateFlow<List<ResolvedSection>> =
        combine(config, installed, ::resolveSections)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The "continue watching" row: what the TV's list holds, for apps that are installed and
     * not hidden. Empty while the row is switched off.
     */
    val watchItems: StateFlow<List<WatchItem>> =
        combine(config, installed, watch.items) { config, installed, items ->
            if (!config.watchNext) {
                emptyList()
            } else {
                // Each entry says which app it is from, by the name that app has on this home screen.
                val names = installed.asSequence()
                    .filter { it.packageName !in config.hidden }
                    .associate { app ->
                        app.packageName to (config.overrides[app.packageName]?.label?.takeIf { it.isNotBlank() } ?: app.label)
                    }
                items.mapNotNull { item -> names[item.packageName]?.let { item.copy(appName = it) } }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Fires when Home is pressed while the launcher is already showing. */
    val homeSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Bumped whenever the wallpaper file may have changed, so the background re-checks it. */
    val wallpaperVersion = MutableStateFlow(0)

    /** Bumped whenever the screensaver's photos may have changed. */
    val photosVersion = MutableStateFlow(0)

    /** Goes up when a picture of the day has been saved or removed. */
    val onlineVersion: StateFlow<Int> = slate.pictureOfTheDay.version

    /** Bumped on resume so system status and the clock are re-read. */
    val statusTick = MutableStateFlow(0)

    val inputs = MutableStateFlow<List<TvInput>>(emptyList())

    /** Where exported settings go. Looked up each time: shared storage may mount after start. */
    val exportPath: String get() = store.exportFile.path

    /** True while settings cannot be saved, typically because storage is full. */
    val saveFailed: StateFlow<Boolean> = store.writeFailed

    /** Whether safe mode set earlier settings aside that could be brought back. */
    val canRestoreSettings = MutableStateFlow(false)

    private suspend fun refreshCanRestore() {
        canRestoreSettings.value = withContext(Dispatchers.IO) { store.hasSetAside() }
    }

    init {
        viewModelScope.launch { refreshCanRestore() }
        viewModelScope.launch {
            config.map { it.watchNext }.distinctUntilChanged().collect { watch.setEnabled(it) }
        }
        // Which apps may have entries in the row: the list is cut down to these before anything
        // is counted, so that a hidden app cannot use up the room.
        viewModelScope.launch {
            combine(config, installed) { config, installed ->
                installed.asSequence().map { it.packageName }.filter { it !in config.hidden }.toSet()
            }.distinctUntilChanged().collect { watch.setShown(it) }
        }
        // Straight after boot, shared storage may not be mounted yet; images kept there would
        // look missing. Check again until it turns up.
        viewModelScope.launch {
            suspend fun mounted() = withContext(Dispatchers.IO) { slate.getExternalFilesDir(null) != null }
            if (mounted()) return@launch
            repeat(20) {
                delay(3_000)
                if (mounted()) {
                    wallpaperVersion.update { it + 1 }
                    repo.refresh()
                    return@launch
                }
            }
        }
    }

    fun onResume() {
        statusTick.update { it + 1 }
        wallpaperVersion.update { it + 1 }
        repo.refresh()
        viewModelScope.launch {
            inputs.value = withContext(Dispatchers.IO) { SystemStatus.tvInputs(slate) }
        }
        viewModelScope.launch { refreshCanRestore() }
        watch.refresh()
    }

    fun launch(app: AppEntry): Boolean {
        val started = repo.launch(app)
        if (started) HomeActivity.noteLeaving()
        return started
    }

    // ---- continue watching ----

    fun openWatchItem(item: WatchItem): Boolean {
        val started = watch.open(item)
        if (started) HomeActivity.noteLeaving()
        return started
    }

    /** Whether Android lets Slate read the TV's list. */
    fun mayReadWatchNext(): Boolean = watch.hasPermission()

    fun setWatchNext(on: Boolean) {
        store.update { it.copy(watchNext = on) }
        // Also when it was on already: the permission may have just been given.
        watch.refresh()
    }

    // ---- appearance ----

    fun updateAppearance(transform: (Appearance) -> Appearance) =
        store.update { it.copy(appearance = transform(it.appearance)) }

    fun updateConfig(transform: (LauncherConfig) -> LauncherConfig) = store.update(transform)

    fun setWallpaper(uri: Uri, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Wallpaper.import(slate, uri) }
            if (ok) {
                updateAppearance { it.copy(background = BackgroundKind.WALLPAPER) }
                wallpaperVersion.update { it + 1 }
            }
            onDone(
                when {
                    !ok -> "That file could not be used. Use a JPEG or PNG up to 16 MB and about 25 megapixels."
                    safeMode.value -> "Wallpaper saved. It will show once you leave safe mode."
                    else -> "Wallpaper set"
                },
            )
        }
    }

    // ---- apps ----

    /** Moves an app by [delta] places within the order currently shown for that section. */
    fun moveApp(sectionId: String, pkg: String, delta: Int) {
        val shown = sections.value.firstOrNull { it.section.id == sectionId }
            ?.apps?.map { it.packageName } ?: return
        val from = shown.indexOf(pkg)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, shown.lastIndex)
        if (to == from) return
        val reordered = shown.toMutableList().apply { add(to, removeAt(from)) }
        updateSection(sectionId) { section ->
            // Keep entries that are not on screen right now (hidden or uninstalled) at the end.
            section.copy(apps = reordered + section.apps.filter { it !in reordered })
        }
    }

    fun togglePin(sectionId: String, pkg: String) = updateSection(sectionId) { section ->
        if (pkg in section.apps) {
            section.copy(apps = section.apps.filterNot { it == pkg })
        } else {
            section.copy(apps = section.apps + pkg)
        }
    }

    fun setHidden(pkg: String, hidden: Boolean) = store.update {
        it.copy(hidden = if (hidden) it.hidden + pkg else it.hidden - pkg)
    }

    fun rename(pkg: String, label: String) = store.update { config ->
        val clean = label.trim()
        val overrides = if (clean.isEmpty()) {
            config.overrides - pkg
        } else {
            config.overrides + (pkg to AppOverride(label = clean))
        }
        config.copy(overrides = overrides)
    }

    // ---- sections ----

    /** Returns false, changing nothing, when there is no room for another section. */
    fun addSection(title: String): Boolean {
        if (config.value.sections.size >= Limits.MAX_SECTIONS) return false
        addSectionUnchecked(title)
        return true
    }

    private fun addSectionUnchecked(title: String) = store.update { config ->
        // Checked again on the live value: two quick presses must not push a section off the end.
        if (config.sections.size >= Limits.MAX_SECTIONS) return@update config
        var id = "s" + System.currentTimeMillis().toString(36)
        while (config.sections.any { it.id == id }) id += "x"
        val section = Section(id = id, title = title.trim().ifEmpty { "New section" })
        // New sections go above the first "all apps" section, where pinned rows normally live.
        val at = config.sections.indexOfFirst { it.includeAll }.takeIf { it >= 0 } ?: config.sections.size
        config.copy(sections = config.sections.toMutableList().apply { add(at, section) })
    }

    fun renameSection(id: String, title: String) = updateSection(id) {
        it.copy(title = title.trim().ifEmpty { it.title })
    }

    fun setSectionStyle(id: String, style: SectionStyle) = updateSection(id) { it.copy(style = style) }

    fun toggleSectionTitle(id: String) = updateSection(id) { it.copy(showTitle = !it.showTitle) }

    fun toggleSectionIncludeAll(id: String) = updateSection(id) { it.copy(includeAll = !it.includeAll) }

    fun moveSection(id: String, delta: Int) = store.update { config ->
        val from = config.sections.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, config.sections.lastIndex)
        if (from < 0 || to == from) {
            config
        } else {
            config.copy(sections = config.sections.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    fun deleteSection(id: String) = store.update { config ->
        if (config.sections.size <= 1) config else config.copy(sections = config.sections.filter { it.id != id })
    }

    private fun updateSection(id: String, transform: (Section) -> Section) = store.update { config ->
        config.copy(sections = config.sections.map { if (it.id == id) transform(it) else it })
    }

    // ---- backup ----

    fun exportConfig(onDone: (String) -> Unit) {
        // One at a time: two exports at once would write through the same temporary file, and
        // the second would report a failure although the file is good.
        if (exporting) return
        exporting = true
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { store.exportToFile() }
                onDone(if (ok) "Settings exported" else "Export failed")
            } finally {
                exporting = false
            }
        }
    }

    /** Only touched on the main thread. */
    private var exporting = false

    fun importConfig(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { store.importFromFile() }
            onDone(error ?: "Settings imported")
        }
    }

    fun resetConfig() {
        Wallpaper.clearPicked(slate)
        store.reset()
        slate.pictureOfTheDay.stop()
        viewModelScope.launch(Dispatchers.IO) { slate.pictureOfTheDay.clear() }
        wallpaperVersion.update { it + 1 }
    }

    // ---- screensaver ----

    fun setSaverSource(source: SaverSource) {
        store.update { it.copy(screensaver = it.screensaver.copy(source = source)) }
        // The settings are in place first, so the fetch sees that it is wanted.
        if (source.usesOnline) slate.pictureOfTheDay.refresh(soon = true) else slate.pictureOfTheDay.stop()
    }

    fun updateSaver(transform: (Screensaver) -> Screensaver) =
        store.update { it.copy(screensaver = transform(it.screensaver)) }

    fun addPhotos(uris: List<Uri>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Photos.import(slate, uris) }
            photosVersion.update { it + 1 }
            val added = if (result.added == 1) "1 photo added" else "${result.added} photos added"
            onDone(
                when {
                    result.full -> "$added. No more fit: Slate keeps at most ${Photos.MAX_PICKED} photos added on the TV, " +
                        "and leaves room on its storage."
                    result.refused > 0 -> "$added. ${result.refused} could not be used: use JPEG or PNG up to 16 MB and about 25 megapixels."
                    else -> added
                },
            )
        }
    }

    fun removePickedPhotos(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val gone = withContext(Dispatchers.IO) { Photos.clearPicked(slate) }
            photosVersion.update { it + 1 }
            onDone(if (gone) "Photos added on this TV removed" else "Some photos could not be removed")
        }
    }

    /**
     * Deletes the wallpaper and all custom app artwork. Offered in safe mode: if one of those
     * files is what kept ending the launcher, this removes it without needing a computer.
     */
    fun removeCustomImages(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val allGone = withContext(Dispatchers.IO) {
                val wallpaperGone = runCatching { Wallpaper.clearAll(slate) }.getOrDefault(false)
                val artGone = runCatching { repo.removeCustomArt() }.getOrDefault(false)
                val photosGone = runCatching { Photos.clearAll(slate) }.getOrDefault(false)
                runCatching { slate.pictureOfTheDay.clear() }
                wallpaperGone && artGone && photosGone
            }
            wallpaperVersion.update { it + 1 }
            photosVersion.update { it + 1 }
            onDone(
                if (allGone) {
                    "Custom wallpaper, artwork and photos removed"
                } else {
                    "Some files could not be removed. From a computer, run: scripts/slate.sh rescue"
                },
            )
        }
    }

    // ---- safe mode ----

    /** Turns user-supplied images back on, optionally bringing back the earlier settings. */
    fun leaveSafeMode(restoreSettings: Boolean, onDone: (String) -> Unit) {
        // The rows that call this stay on screen for a frame after safe mode has ended. A press
        // landing on one then (a remote button that bounces) must do nothing, least of all
        // restore the copy behind the one just restored.
        if (!safeMode.value) return
        restoreOrLeave(restoreSettings, onDone)
    }

    /** For "Restore earlier settings" outside safe mode: brings back the most recent set-aside copy. */
    fun restoreEarlierSettings(onDone: (String) -> Unit) = restoreOrLeave(restoreSettings = true, onDone)

    private fun restoreOrLeave(restoreSettings: Boolean, onDone: (String) -> Unit) {
        // One at a time: a second press arriving while the first is still at work would
        // otherwise restore the copy behind the one just restored.
        if (leavingSafeMode) return
        leavingSafeMode = true
        viewModelScope.launch {
            try {
                val error = if (restoreSettings) withContext(Dispatchers.IO) { store.restoreQuarantined() } else null
                // Before safe mode is reported as over, so that a copy just used is never offered again.
                refreshCanRestore()
                if (error != null) {
                    onDone(error)
                    return@launch
                }
                slate.leaveSafeMode()
                wallpaperVersion.update { it + 1 }
                repo.refresh()
                watch.refresh()
                onDone(
                    when {
                        !restoreSettings -> "Safe mode is off"
                        canRestoreSettings.value -> "Settings restored. An earlier set is still kept: Restore earlier settings"
                        else -> "Earlier settings restored"
                    },
                )
            } finally {
                leavingSafeMode = false
            }
        }
    }

    /** Only touched on the main thread. */
    private var leavingSafeMode = false
}

private fun resolveSections(config: LauncherConfig, installed: List<AppEntry>): List<ResolvedSection> {
    val visible = installed.asSequence()
        .filter { it.packageName !in config.hidden }
        .map { app ->
            val label = config.overrides[app.packageName]?.label
            if (label.isNullOrBlank()) app else app.copy(label = label)
        }
        // Sorted by the name actually shown, so a renamed app sits where its new name belongs.
        .sortedBy { it.label.lowercase() }
        .toList()
    val byPackage = visible.associateBy { it.packageName }
    val pinnedElsewhere = if (config.allExcludesPinned) {
        config.sections.filter { !it.includeAll }.flatMap { it.apps }.toSet()
    } else {
        emptySet()
    }
    return config.sections.map { section ->
        val listed = section.apps.distinct().mapNotNull { byPackage[it] }
        val apps = if (section.includeAll) {
            val listedPackages = section.apps.toSet()
            (listed + visible.filter { it.packageName !in listedPackages })
                .filter { it.packageName !in pinnedElsewhere }
        } else {
            listed
        }
        ResolvedSection(section, apps)
    }
}
