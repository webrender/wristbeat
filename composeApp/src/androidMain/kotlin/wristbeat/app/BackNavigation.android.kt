package wristbeat.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** The phone's system back gesture/button, which `androidApp`'s `MainActivity` wires up for free. */
@Composable
internal actual fun StageBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
