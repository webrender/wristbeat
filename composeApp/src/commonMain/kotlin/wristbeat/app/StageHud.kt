package wristbeat.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import wristbeat.core.ScoreTally

/** Below this width the stage tabs and a stage's top HUD can't share the top edge side by side. */
internal val COMPACT_HUD_MAX_WIDTH = 720.dp

/**
 * How [App] has laid out the shared chrome, so each stage's top HUD can stay clear of it. On a wide
 * screen the tabs sit in the top-right corner and the stage HUD stacks in the top-left. On a
 * compact (phone) screen the tabs span the top edge, so the stage HUD starts [topInset] below it.
 *
 * [watch] is the Wear OS layout ([WatchStageScreen]): the watch's native menu owns stage switching,
 * titles, the chart toggle and legends, so a stage shows no top HUD at all, and its status panel
 * uses smaller text and only the lines that fit on a round face.
 */
internal data class HudLayout(val compact: Boolean = false, val topInset: Dp = 0.dp, val watch: Boolean = false)

internal val LocalHudLayout = compositionLocalOf { HudLayout() }

/** The app-wide note highway ("chart") setting, shown as a toggle on stages that have a highway. */
data class ChartSetting(val on: Boolean, val onToggle: () -> Unit)

/**
 * A stage's top-of-screen HUD: its title, the chart toggle (if the stage has a note highway), and
 * its input legend. Wide screens stack them in the top-left corner, clear of the tabs in the
 * top-right. Compact screens drop the title (the selected tab already names the stage) and flow
 * the rest in centered rows under the tabs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.StageHeader(
    title: String,
    titleColor: Color,
    chart: ChartSetting? = null,
    legend: (@Composable () -> Unit)? = null,
) {
    val layout = LocalHudLayout.current
    if (layout.watch) return
    if (layout.compact) {
        FlowRow(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = layout.topInset),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chart != null) ChartToggle(chart)
            if (legend != null) HudChip { legend() }
        }
    } else {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudChip { HudText(title, color = titleColor) }
            if (chart != null) ChartToggle(chart)
            if (legend != null) HudChip { legend() }
        }
    }
}

/** Distance of a stage's bottom status panel from the screen edge; higher on a watch, where a round face narrows toward the bottom. */
internal val statusBottomPadding: Dp
    @Composable get() = if (LocalHudLayout.current.watch) 30.dp else 28.dp

/** The Perfect/OK/Miss readout under a stage's status line, abbreviated to fit a watch. */
@Composable
internal fun tallyLine(tally: ScoreTally): String =
    if (LocalHudLayout.current.watch) "P ${tally.perfect} · OK ${tally.ok} · Miss ${tally.miss}"
    else "Perfect ${tally.perfect} · OK ${tally.ok} · Miss ${tally.miss}"

/**
 * Tells [onRunningChanged] whether a run is in progress, and that it isn't once the stage leaves
 * the screen. The watch uses this to hold off swipe-to-dismiss (so a sloppy swipe can't quit
 * mid-run) and to keep the screen awake while playing.
 */
@Composable
internal fun ReportRunning(running: Boolean, onRunningChanged: (Boolean) -> Unit) {
    val callback by rememberUpdatedState(onRunningChanged)
    LaunchedEffect(running) { callback(running) }
    DisposableEffect(Unit) { onDispose { callback(false) } }
}

/** Lets a player hide the note highway (the "visual beat indicator chart") and play by ear alone. */
@Composable
private fun ChartToggle(chart: ChartSetting) {
    GameButton(
        accent = if (chart.on) Color(0xFF2FBF9E) else Color(0xFF16302D),
        onClick = chart.onToggle,
    ) {
        HudText(if (chart.on) "Chart: on" else "Chart: off", color = if (chart.on) Color(0xFF06211D) else Color(0xFFAAB8B5))
    }
}
