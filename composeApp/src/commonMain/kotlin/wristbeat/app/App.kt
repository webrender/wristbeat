package wristbeat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

// Calibrate opens first in the menu per Jeremy's feedback on the prototype.
enum class Stage(val label: String, val enabled: Boolean) {
    CALIBRATE("Calibrate", enabled = true),
    SNAP_CRABS("Snap Crabs", enabled = true),
    MANGO_CHOP("Mango Chop", enabled = true),
    BONGO_BLITZ("Bongo Blitz", enabled = true),
    REMIX_1("Remix 1", enabled = true),
}

/** Each stage's accent color, shared by the main menu here and the Wear menu in `wearApp`. */
val Stage.accent: Color
    get() = when (this) {
        Stage.CALIBRATE -> Color(0xFF2FBF9E)
        Stage.SNAP_CRABS -> Color(0xFFF07A5E)
        Stage.MANGO_CHOP -> Color(0xFFFFB320)
        Stage.BONGO_BLITZ -> Color(0xFFC77DFF)
        Stage.REMIX_1 -> Color(0xFFFF6FB5)
    }

/**
 * The phone/watch system back gesture/button (Android) or nothing (web, where [App] handles
 * Escape directly instead — there's no system back gesture to hook in a browser).
 */
@Composable
internal expect fun StageBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * No app chrome while a stage is being played: picking a stage, toggling the chart and reading the
 * calibration offset all happen in [MainMenu] instead, so each stage screen is just the game itself.
 * Getting back to the menu is a gesture, not a button — the system back gesture/button on Android,
 * swipe-to-dismiss on the watch — and, for those, only works while nothing is running, so a sloppy
 * tap or swipe mid-run can't be mistaken for one. Escape on the browser is a deliberate keypress
 * rather than a gesture that can be thrown accidentally, so it quits a song in progress too.
 */
@Composable
fun App() {
    var stage by remember { mutableStateOf<Stage?>(null) }
    var running by remember { mutableStateOf(false) }
    // Calibrate's measured tap offset, saved per audio output and applied to every scored stage.
    val calibration = rememberCalibration()

    // One note highway ("chart") setting for every stage that has one, so hiding it to play by ear
    // carries over when switching games.
    var showChart by remember { mutableStateOf(true) }
    val chart = ChartSetting(showChart) { showChart = !showChart }

    val backEnabled = stage != null && !running
    StageBackHandler(enabled = backEnabled) { stage = null }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F1B19))
                .onKeyEvent { event ->
                    if (stage != null && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        stage = null
                        true
                    } else {
                        false
                    }
                },
        ) {
            CompositionLocalProvider(LocalHudLayout provides HudLayout()) {
                val current = stage
                if (current == null) {
                    MainMenu(
                        calibration = calibration,
                        chart = chart,
                        // Unlocks the browser's AudioContext synchronously inside this click, since
                        // the stage's own auto-start (see e.g. SnapCrabsScreen) happens a frame later
                        // in a LaunchedEffect, too late for autoplay policies to allow it.
                        onSelect = {
                            AudioClock().start()
                            stage = it
                        },
                    )
                } else {
                    val onMenu: () -> Unit = { stage = null }
                    when (current) {
                        Stage.CALIBRATE -> CalibrateScreen(calibration, onRunningChanged = { running = it }, onMenu = onMenu)
                        Stage.SNAP_CRABS -> SnapCrabsScreen(calibration, chart, onRunningChanged = { running = it }, onMenu = onMenu)
                        Stage.MANGO_CHOP -> MangoChopScreen(calibration, chart, onRunningChanged = { running = it }, onMenu = onMenu)
                        Stage.BONGO_BLITZ -> BongoBlitzScreen(calibration, chart, onRunningChanged = { running = it }, onMenu = onMenu)
                        Stage.REMIX_1 -> Remix1Screen(calibration, chart, onRunningChanged = { running = it }, onMenu = onMenu)
                    }
                }
            }
        }
    }
}

/**
 * One stage on its own, laid out for a Wear OS watch: the watch's native menu (in `wearApp`)
 * handles stage switching, the chart toggle and the calibration readout, so there's no menu button
 * here, just the game. [onRunningChanged] reports whether a run is in progress, so the watch can
 * hold off swipe-to-dismiss and keep the screen on while playing.
 */
@Composable
fun WatchStageScreen(
    stage: Stage,
    calibration: Calibration,
    chart: ChartSetting,
    onRunningChanged: (Boolean) -> Unit,
) {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1B19))) {
            CompositionLocalProvider(LocalHudLayout provides HudLayout(watch = true)) {
                when (stage) {
                    Stage.CALIBRATE -> CalibrateScreen(calibration, onRunningChanged)
                    Stage.SNAP_CRABS -> SnapCrabsScreen(calibration, chart, onRunningChanged)
                    Stage.MANGO_CHOP -> MangoChopScreen(calibration, chart, onRunningChanged)
                    Stage.BONGO_BLITZ -> BongoBlitzScreen(calibration, chart, onRunningChanged)
                    Stage.REMIX_1 -> Remix1Screen(calibration, chart, onRunningChanged)
                }
            }
        }
    }
}
