package wristbeat.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.scrollAway
import androidx.wear.compose.material.Typography
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlin.math.abs
import kotlin.math.roundToInt
import wristbeat.app.Calibration
import wristbeat.app.ChartSetting
import wristbeat.app.ChartToggle
import wristbeat.app.MenuBackdrop
import wristbeat.app.MenuTitle
import wristbeat.app.ProvideWatchHud
import wristbeat.app.Stage
import wristbeat.app.StageMenuEntry
import wristbeat.app.WatchStageScreen
import wristbeat.app.rememberCalibration
import wristbeat.app.rememberMenuBeat
import wristbeat.app.wristbeatFontFamily

private const val MENU_ROUTE = "menu"

/**
 * The watch's own shell around the shared stages: a menu picks a stage and toggles the chart, and
 * each stage opens full screen. The menu is the phone/web `MainMenu`'s look (its backdrop, bouncing
 * title, emblem buttons and chart toggle) in a Wear scrolling list, so it curves with the round
 * face and scrolls with the crown. Swiping right goes back to the menu, except mid-run, where a
 * swipe is far more likely to be a sloppy tap or a Mango Chop slice than a request to quit; the
 * results screen also has a "Menu" button. The screen stays on while a run is in progress.
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
                    calibration = calibration,
                    chart = chart,
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
                        onMenu = { navController.popBackStack(MENU_ROUTE, inclusive = false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StageMenu(calibration: Calibration, chart: ChartSetting, onSelect: (Stage) -> Unit) {
    val listState = rememberScalingLazyListState()
    val beat = rememberMenuBeat()
    Scaffold(
        // The clock slides away as the list scrolls, so it doesn't sit on top of the buttons.
        timeText = { TimeText(modifier = Modifier.scrollAway(listState)) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        Box(Modifier.fillMaxSize()) {
            MenuBackdrop()
            ProvideWatchHud {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
                ) {
                    item { MenuTitle(fontSize = 22.sp, beat = beat) }
                    for (stage in Stage.entries) {
                        item {
                            StageMenuEntry(
                                stage = stage,
                                // Like the phone/web menu, only Calibrate has a subtitle: its saved
                                // offset, shortened to fit the watch.
                                subtitle = if (stage == Stage.CALIBRATE) {
                                    calibration.offsetMs?.let { "Offset ${formatOffset(it)}" } ?: "Not calibrated"
                                } else {
                                    null
                                },
                                beat = beat,
                                labelSize = 12.sp,
                                subtitleSize = 9.sp,
                                padding = 3.dp,
                                emblemSize = 26.dp,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onSelect(stage) },
                            )
                        }
                    }
                    item {
                        ChartToggle(chart, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// Only TimeText and the scroll indicator still use Wear Material; everything else is the app's own UI.
private val WristbeatColors = Colors(
    primary = Color(0xFF2FBF9E),
    primaryVariant = Color(0xFF1C9A6A),
    secondary = Color(0xFFFFB320),
    onPrimary = Color(0xFF06211D),
    surface = Color(0xFF16302D),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAAB8B5),
)

private fun formatOffset(ms: Double): String = (if (ms >= 0) "+" else "−") + "${abs(ms).roundToInt()}ms"
