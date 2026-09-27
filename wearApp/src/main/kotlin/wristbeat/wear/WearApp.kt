package wristbeat.wear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.Typography
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlin.math.abs
import kotlin.math.roundToInt
import wristbeat.app.ChartSetting
import wristbeat.app.Stage
import wristbeat.app.WatchStageScreen
import wristbeat.app.accent
import wristbeat.app.rememberCalibration
import wristbeat.app.wristbeatFontFamily

private const val MENU_ROUTE = "menu"

private val Teal = Color(0xFF2FBF9E)

/**
 * The watch's own shell around the shared stages: a native Wear menu (a scrolling list of chips)
 * picks a stage and toggles the chart, and each stage opens full screen. Swiping right goes back
 * to the menu, except mid-run, where a swipe is far more likely to be a sloppy tap or a Mango Chop
 * slice than a request to quit; the screen also stays on while a run is in progress.
 */
@Composable
fun WearApp() {
    // Same app-wide state the phone/web App() holds: the saved per-output calibration and chart toggle.
    val calibration = rememberCalibration()
    var showChart by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    val chart = ChartSetting(showChart) { showChart = !showChart }

    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    MaterialTheme(colors = WristbeatColors, typography = Typography(defaultFontFamily = wristbeatFontFamily())) {
        val navController = rememberSwipeDismissableNavController()
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = MENU_ROUTE,
            userSwipeEnabled = !running,
        ) {
            composable(MENU_ROUTE) {
                StageMenu(
                    offsetLabel = calibration.offsetMs?.let { "Offset ${formatOffset(it)}" } ?: "Start here",
                    chartOn = showChart,
                    onChartChange = { showChart = it },
                    onSelect = { navController.navigate(it.name) },
                )
            }
            for (stage in Stage.entries) {
                composable(stage.name) {
                    WatchStageScreen(
                        stage = stage,
                        calibration = calibration,
                        chart = chart,
                        onRunningChanged = { running = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun StageMenu(
    offsetLabel: String,
    chartOn: Boolean,
    onChartChange: (Boolean) -> Unit,
    onSelect: (Stage) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Wristbeat", color = Teal) } }
            for (stage in Stage.entries) {
                item {
                    Chip(
                        onClick = { onSelect(stage) },
                        enabled = stage.enabled,
                        label = { Text(stage.label) },
                        secondaryLabel = {
                            Text(
                                when (stage) {
                                    Stage.CALIBRATE -> offsetLabel
                                    Stage.SNAP_CRABS -> "Repeat the lead crab"
                                    Stage.MANGO_CHOP -> "Tap to chop, swipe to slice"
                                    Stage.BONGO_BLITZ -> "Copy the monkey's beat"
                                },
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = stage.accent,
                            contentColor = Color(0xFF1A1206),
                            secondaryContentColor = Color(0xFF1A1206).copy(alpha = 0.75f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                ToggleChip(
                    checked = chartOn,
                    onCheckedChange = onChartChange,
                    label = { Text("Chart") },
                    secondaryLabel = { Text(if (chartOn) "Notes on screen" else "Play by ear") },
                    toggleControl = { Switch(checked = chartOn) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val WristbeatColors = Colors(
    primary = Teal,
    primaryVariant = Color(0xFF1C9A6A),
    secondary = Color(0xFFFFB320),
    onPrimary = Color(0xFF06211D),
    surface = Color(0xFF16302D),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAAB8B5),
)

private fun formatOffset(ms: Double): String = (if (ms >= 0) "+" else "−") + "${abs(ms).roundToInt()}ms"
