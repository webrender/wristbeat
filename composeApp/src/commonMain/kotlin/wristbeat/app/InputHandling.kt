package wristbeat.app

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

private val TAP_KEYS = setOf(Key.Spacebar, Key.J, Key.F, Key.Enter)

/** The desktop stand-in for a swipe: a second pair of home-row keys, plus the arrows. */
private val SWIPE_KEYS = setOf(Key.K, Key.D, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/**
 * Lets a stage be tapped from the keyboard (Space, J, F, Enter) as well as pointer taps, matching
 * HANDOFF's web input mapping. Guards against the browser repeating KeyDown while a key is held,
 * the same way the prototype ignored KeyboardEvent.repeat, so a held key can't spam taps.
 *
 * Stages with a secondary action pass [onSwipe]; K, D and the arrow keys then trigger it, so a
 * player can keep one hand on each action (F/J to tap, D/K to swipe).
 */
@Composable
fun rememberTapKeyModifier(onTap: () -> Unit, onSwipe: (() -> Unit)? = null): Modifier {
    val focusRequester = remember { FocusRequester() }
    val held = remember { mutableSetOf<Key>() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->
            val action = when (event.key) {
                in TAP_KEYS -> onTap
                in SWIPE_KEYS -> onSwipe ?: return@onKeyEvent false
                else -> return@onKeyEvent false
            }
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (held.add(event.key)) action()
                    true
                }
                KeyEventType.KeyUp -> {
                    held.remove(event.key)
                    true
                }
                else -> false
            }
        }
}
