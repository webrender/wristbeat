package wristbeat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// Calibrate opens first by default per Jeremy's feedback on the prototype.
// shortLabel is what the tabs show on a compact (phone-width) screen, where the full labels don't fit in one row.
enum class Stage(val label: String, val shortLabel: String, val enabled: Boolean) {
    CALIBRATE("Calibrate", "Calibrate", enabled = true),
    SNAP_CRABS("Snap Crabs", "Crabs", enabled = true),
    MANGO_CHOP("Mango Chop", "Mango", enabled = true),
}

/**
 * No app chrome: each stage owns the whole screen and draws its own title/HUD. The only shared UI
 * is a small tab strip for switching stages, floated over the game in a corner like an in-game menu
 * rather than sitting in a page header above the content.
 */
@Composable
fun App() {
    var stage by remember { mutableStateOf(Stage.CALIBRATE) }
    // Calibrate's measured tap offset, applied when judging Snap Crabs and Mango Chop. In-memory only for now —
    // per-device storage isn't built yet, so it resets when the app restarts.
    var inputOffsetMs by remember { mutableStateOf(0.0) }

    // One note highway ("chart") setting for every stage that has one, so hiding it to play by ear
    // carries over when switching games.
    var showChart by remember { mutableStateOf(true) }
    val chart = ChartSetting(showChart) { showChart = !showChart }

    MaterialTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1B19))) {
            // On a phone-width screen the tabs span the top edge and each stage's HUD starts below
            // them; elsewhere the tabs sit in the top-right corner, clear of the stage HUD's top-left column.
            val compact = maxWidth < COMPACT_HUD_MAX_WIDTH
            val density = LocalDensity.current
            var tabsHeight by remember { mutableStateOf(0.dp) }
            val hudLayout = HudLayout(compact, topInset = if (compact) 16.dp + tabsHeight + 8.dp else 0.dp)

            CompositionLocalProvider(LocalHudLayout provides hudLayout) {
                when (stage) {
                    Stage.CALIBRATE -> CalibrateScreen(onCalibrated = { inputOffsetMs = it })
                    Stage.SNAP_CRABS -> SnapCrabsScreen(inputOffsetMs, chart)
                    Stage.MANGO_CHOP -> MangoChopScreen(inputOffsetMs, chart)
                }
            }
            StageTabs(
                current = stage,
                onSelect = { stage = it },
                compact = compact,
                modifier = Modifier
                    .align(if (compact) Alignment.TopCenter else Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .onSizeChanged { tabsHeight = with(density) { it.height.toDp() } },
            )
        }
    }
}

@Composable
private fun StageTabs(current: Stage, onSelect: (Stage) -> Unit, compact: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in Stage.entries) {
            val selected = s == current
            GameButton(
                accent = if (selected) Color(0xFF2FBF9E) else Color(0xFF16302D),
                enabled = s.enabled,
                onClick = { onSelect(s) },
            ) {
                HudText(
                    text = (if (compact) s.shortLabel else s.label).let { if (s.enabled) it else "$it · soon" },
                    color = if (selected) Color(0xFF06211D) else Color.White.copy(alpha = if (s.enabled) 0.9f else 0.4f),
                )
            }
        }
    }
}

/**
 * One stage on its own, laid out for a Wear OS watch: the watch's native menu (in `wearApp`)
 * handles stage switching, the chart toggle and the calibration readout, so there are no tabs or
 * top HUD here, just the game and a small status panel. [onRunningChanged] reports whether a run
 * is in progress, so the watch can hold off swipe-to-dismiss and keep the screen on while playing.
 */
@Composable
fun WatchStageScreen(
    stage: Stage,
    inputOffsetMs: Double,
    onCalibrated: (offsetMs: Double) -> Unit,
    chart: ChartSetting,
    onRunningChanged: (Boolean) -> Unit,
) {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1B19))) {
            CompositionLocalProvider(LocalHudLayout provides HudLayout(watch = true)) {
                when (stage) {
                    Stage.CALIBRATE -> CalibrateScreen(onCalibrated, onRunningChanged)
                    Stage.SNAP_CRABS -> SnapCrabsScreen(inputOffsetMs, chart, onRunningChanged)
                    Stage.MANGO_CHOP -> MangoChopScreen(inputOffsetMs, chart, onRunningChanged)
                }
            }
        }
    }
}
