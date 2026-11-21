package app.slate.launcher.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.slate.launcher.LauncherViewModel
import app.slate.launcher.data.AppEntry
import app.slate.launcher.data.LauncherConfig
import app.slate.launcher.data.Limits
import app.slate.launcher.system.SystemStatus

/** Options for one app. Opened by holding OK, or pressing Menu, on its card. */
@Composable
fun AppMenu(
    vm: LauncherViewModel,
    app: AppEntry,
    config: LauncherConfig,
    onMove: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val pkg = app.packageName
    val first = remember { FocusRequester() }
    var renaming by remember { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    SideSheet(title = app.label, onDismiss = onClose) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "open") {
                SheetRow("Open", Modifier.focusRequester(first)) {
                    onClose()
                    if (!vm.launch(app)) toast("Could not open ${app.label}")
                }
            }
            item(key = "move") { SheetRow("Move", onClick = onMove) }
            items(config.sections.filter { !it.includeAll }, key = { "pin:${it.id}" }) { section ->
                val pinned = pkg in section.apps
                SheetRow(if (pinned) "Remove from ${section.title}" else "Add to ${section.title}") {
                    vm.togglePin(section.id, pkg)
                }
            }
            item(key = "rename") { SheetRow("Rename") { renaming = true } }
            item(key = "hide") {
                SheetRow("Hide", subtitle = "Bring it back under Customise › Apps") {
                    vm.setHidden(pkg, true)
                    onClose()
                }
            }
            item(key = "info") {
                SheetRow("App info") {
                    onClose()
                    if (!SystemStatus.openAppInfo(context, pkg)) toast("Could not open app info")
                }
            }
            item(key = "uninstall") {
                SheetRow("Uninstall", danger = true) {
                    onClose()
                    if (!SystemStatus.uninstall(context, pkg)) toast("Could not start the uninstaller")
                }
            }
        }
        LaunchedEffect(Unit) {
            repeat(2) { withFrameNanos { } }
            runCatching { first.requestFocus() }
        }
    }

    if (renaming) {
        TextPrompt(
            title = "Rename ${app.label}",
            initial = app.label,
            maxLength = Limits.MAX_LABEL,
            onDismiss = { renaming = false },
            onConfirm = { text ->
                renaming = false
                vm.rename(pkg, text)
            },
        )
    }
}
