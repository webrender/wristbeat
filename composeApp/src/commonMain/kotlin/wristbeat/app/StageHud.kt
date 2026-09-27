package wristbeat.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * [watch] is the Wear OS layout ([WatchStageScreen]): HUD text and chips shrink to fit a small
 * round face, and status panels drop to fewer lines. Phone/web stages carry no other layout state
 * of their own — stage switching and the chart toggle live in the main menu, not in per-stage chrome.
 */
internal data class HudLayout(val watch: Boolean = false)

internal val LocalHudLayout = compositionLocalOf { HudLayout() }

/** The app-wide note highway ("chart") setting, shown as a toggle in the main menu. */
data class ChartSetting(val on: Boolean, val onToggle: () -> Unit)

/** Distance of a stage's status panel from the screen edge; higher on a watch, where a round face narrows toward the bottom. */
internal val statusBottomPadding: Dp
    @Composable get() = if (LocalHudLayout.current.watch) 30.dp else 28.dp

/**
 * Tells [onRunningChanged] whether a run is in progress, and that it isn't once the stage leaves
 * the screen. The watch uses this to hold off swipe-to-dismiss (so a sloppy tap or a Mango Chop
 * slice can't quit mid-run) and to keep the screen awake while playing; the phone/web app uses it
 * to disable the Escape/back-gesture shortcut back to the menu while a run is in progress.
 */
@Composable
internal fun ReportRunning(running: Boolean, onRunningChanged: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onRunningChanged)
    LaunchedEffect(running) { callback(running) }
    DisposableEffect(Unit) { onDispose { callback(false) } }
}

/** Lets a player hide the note highway (the "visual beat indicator chart") and play by ear alone. */
@Composable
internal fun ChartToggle(chart: ChartSetting, modifier: Modifier = Modifier, fontSize: TextUnit = TextUnit.Unspecified) {
    GameButton(
        modifier = modifier,
        accent = if (chart.on) Color(0xFF2FBF9E) else Color(0xFF16302D),
        onClick = chart.onToggle,
    ) {
        HudText(
            if (chart.on) "Chart: on" else "Chart: off",
            color = if (chart.on) Color(0xFF06211D) else Color(0xFFAAB8B5),
            fontSize = fontSize,
        )
    }
}
