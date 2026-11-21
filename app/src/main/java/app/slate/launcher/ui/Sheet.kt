package app.slate.launcher.ui

import android.view.KeyEvent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.slate.launcher.data.takeWhole

private val DangerColor = Color(0xFFFF8A80)

/**
 * Slate's own panels, each in a window of its own, while they are on screen. The home screen's
 * focus check (see HomeActivity) asks whether one of them holds the remote's keys: then nothing
 * is wrong, and there is nothing to work out. Main thread only.
 */
object PanelWindows {
    private val views = ArrayList<View>()

    fun anyFocused(): Boolean = views.any { it.hasWindowFocus() }

    @Composable
    fun Track() {
        val view = LocalView.current
        DisposableEffect(view) {
            views += view
            onDispose { views -= view }
        }
    }
}

/**
 * A panel that slides in from the right, in its own window. Being a separate window means focus
 * cannot leak onto the home screen behind it and returns to the same card when it closes, while
 * the home screen stays visible so appearance changes preview live.
 */
@Composable
fun SideSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val animate = LocalAppearance.current.animations
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PanelWindows.Track()
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }

        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            AnimatedVisibility(
                visible = shown,
                enter = if (animate) slideInHorizontally { it / 3 } + fadeIn() else EnterTransition.None,
            ) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .width(372.dp)
                        .background(palette.panel)
                        .padding(start = 18.dp, end = 18.dp, top = 30.dp, bottom = 18.dp),
                ) {
                    Label(
                        text = title,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
                        size = 20f,
                        weight = FontWeight.Medium,
                    )
                    content()
                }
            }
        }
    }
}

/**
 * One line in a panel. With [onAdjust], left and right step the value down and up; OK always
 * runs [onClick]. [canLower] and [canRaise] say whether the value has room left in that
 * direction, which decides the arrows shown beside it.
 */
@Composable
fun SheetRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    danger: Boolean = false,
    onAdjust: ((Int) -> Unit)? = null,
    adjustOnRepeat: Boolean = true,
    canLower: Boolean = true,
    canRaise: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        if (focused) palette.surfaceFocused else Color.Transparent,
        label = "rowBackground",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val key = event.nativeKeyEvent
                val step = when (key.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> 0
                }
                if (step == 0 || onAdjust == null) {
                    false
                } else {
                    // A held button steps a number repeatedly, but must not flicker a switch.
                    if (key.action == KeyEvent.ACTION_DOWN && (adjustOnRepeat || key.repeatCount == 0)) onAdjust(step)
                    true
                }
            }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(title, size = 15f, color = if (danger) DangerColor else palette.text)
            if (subtitle != null) {
                Label(subtitle, modifier = Modifier.padding(top = 3.dp), size = 11.5f, color = palette.textDim, maxLines = 4)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Label(
                // The arrows say which way the value can still go: none at the end of its range.
                text = if (onAdjust != null && focused) {
                    (if (canLower) "‹  " else "") + value + (if (canRaise) "  ›" else "")
                } else {
                    value
                },
                size = 14f,
                color = if (focused) palette.accent else palette.textDim,
            )
        }
    }
}

/** Small rounded text button, used in the top bar and in dialogs. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        if (focused) palette.surfaceFocused else Color.Transparent,
        label = "pillBackground",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
            .tvClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Label(text, size = 13f, color = if (focused) palette.text else palette.textDim, spacing = 0.2f)
    }
}

/** Asks for one line of text with the TV's on-screen keyboard. */
@Composable
fun TextPrompt(
    title: String,
    initial: String,
    maxLength: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val textScale = LocalAppearance.current.fontScale / 100f
    Dialog(onDismissRequest = onDismiss) {
        PanelWindows.Track()
        val start = initial.takeWhole(maxLength)
        var field by remember { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
        val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(palette.panel)
                .padding(24.dp),
        ) {
            Label(title, size = 17f, weight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = field,
                onValueChange = { change ->
                    // Too long (a paste, or voice input): keep what fits rather than dropping it all.
                    field = if (change.text.length <= maxLength) {
                        change
                    } else {
                        val kept = change.text.takeWhole(maxLength)
                        TextFieldValue(kept, TextRange(kept.length))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    // Some remotes reach the app as a plain keyboard, and the text field then
                    // keeps Down for itself. Always let Down leave the field for the buttons.
                    .onPreviewKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        if (key.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            if (key.action == KeyEvent.ACTION_DOWN) focusManager.moveFocus(FocusDirection.Down)
                            true
                        } else {
                            false
                        }
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surfaceFocused)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = (16f * textScale).sp),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(field.text) }),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill("Cancel", onClick = onDismiss)
                Spacer(Modifier.width(4.dp))
                Pill("Save") { onConfirm(field.text) }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
}
