package wristbeat.app

import androidx.compose.runtime.Composable

/** The browser has no system back gesture to hook; Escape is handled directly in [App] instead. */
@Composable
internal actual fun StageBackHandler(enabled: Boolean, onBack: () -> Unit) {}
