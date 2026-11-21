package app.slate.launcher.ui

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/** The keys a remote or gamepad uses for "OK". */
fun isConfirmKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    -> true
    else -> false
}

private class PressState {
    var down = false
    var long = false
}

/**
 * Makes an element focusable and clickable with a TV remote.
 *
 * OK fires [onClick] on release. Holding OK, or pressing Menu, fires [onLongClick]. A release is
 * only honoured if the press started on this element, so a long press that opens a panel never
 * "clicks through" into whatever receives focus next.
 */
fun Modifier.tvClickable(
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val state = remember { PressState() }
    val click = rememberUpdatedState(onClick)
    val longClick = rememberUpdatedState(onLongClick)
    this
        .onKeyEvent { event ->
            val key = event.nativeKeyEvent
            when {
                isConfirmKey(key.keyCode) -> when (key.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (key.repeatCount == 0) {
                            state.down = true
                            state.long = false
                        } else if (state.down && !state.long && longClick.value != null) {
                            state.long = true
                            longClick.value?.invoke()
                        }
                        true
                    }
                    KeyEvent.ACTION_UP -> {
                        // A cancelled release (the system took the key away) is not a click.
                        val fire = state.down && !state.long && !key.isCanceled
                        state.down = false
                        state.long = false
                        if (fire) click.value()
                        true
                    }
                    else -> false
                }
                key.keyCode == KeyEvent.KEYCODE_MENU -> {
                    val handler = longClick.value
                    if (handler != null && key.action == KeyEvent.ACTION_UP && !key.isCanceled) handler()
                    handler != null
                }
                else -> false
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { longClick.value?.invoke() },
                onTap = { click.value() },
            )
        }
        .focusable()
}

/**
 * Keeps the focused row at a fixed height on screen, so the list scrolls under a steady focus
 * instead of the focus wandering to the screen edge.
 */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val fraction: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val child = abs(size)
        val target = fraction * containerSize
        val lead = if (child <= containerSize && containerSize - target < child) containerSize - child else target
        return offset - lead
    }
}

/** Scrolls only when needed, but keeps [marginPx] between the focused item and the edge. */
@OptIn(ExperimentalFoundationApi::class)
class MarginSpec(private val marginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val lead = offset - marginPx
        val trail = offset + size + marginPx
        return when {
            lead >= 0 && trail <= containerSize -> 0f
            lead < 0 && trail > containerSize -> 0f
            abs(lead) < abs(trail - containerSize) -> lead
            else -> trail - containerSize
        }
    }
}
