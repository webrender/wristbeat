package wristbeat.app

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

private val TAP_KEYS = setOf(Key.Spacebar, Key.J, Key.F, Key.Enter)

/**
 * Lets a stage be tapped from the keyboard (Space, J, F, Enter) as well as pointer taps, matching
 * HANDOFF's web input mapping. Guards against the browser repeating KeyDown while a key is held,
 * the same way the prototype ignored KeyboardEvent.repeat, so a held key can't spam taps.
 */
@Composable
fun rememberTapKeyModifier(onTap: () -> Unit): Modifier {
    val focusRequester = remember { FocusRequester() }
    var held by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { event ->
            if (event.key !in TAP_KEYS) return@onKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (!held) {
                        held = true
                        onTap()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    held = false
                    true
                }
                else -> false
            }
        }
}
