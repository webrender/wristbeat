package wristbeat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Mango Chop is stubbed this iteration; Calibrate and Snap Crabs are playable.
// Calibrate opens first by default per Jeremy's feedback on the prototype.
enum class Stage(val label: String, val enabled: Boolean) {
    CALIBRATE("Calibrate", enabled = true),
    SNAP_CRABS("Snap Crabs", enabled = true),
    MANGO_CHOP("Mango Chop", enabled = false),
}

/**
 * No app chrome: each stage owns the whole screen and draws its own title/HUD. The only shared UI
 * is a small tab strip for switching stages, floated over the game in a corner like an in-game menu
 * rather than sitting in a page header above the content.
 */
@Composable
fun App() {
    var stage by remember { mutableStateOf(Stage.CALIBRATE) }

    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1B19))) {
            when (stage) {
                Stage.CALIBRATE -> CalibrateScreen()
                Stage.SNAP_CRABS -> SnapCrabsScreen()
                Stage.MANGO_CHOP -> ComingSoonScreen(Stage.MANGO_CHOP.label)
            }
            StageTabs(
                current = stage,
                onSelect = { stage = it },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun StageTabs(current: Stage, onSelect: (Stage) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in Stage.entries) {
            val selected = s == current
            GameButton(
                accent = if (selected) Color(0xFF2FBF9E) else Color(0xFF16302D),
                enabled = s.enabled,
                onClick = { onSelect(s) },
            ) {
                HudText(
                    text = if (s.enabled) s.label else "${s.label} · soon",
                    color = if (selected) Color(0xFF06211D) else Color.White.copy(alpha = if (s.enabled) 0.9f else 0.4f),
                )
            }
        }
    }
}

@Composable
private fun ComingSoonScreen(name: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        HudText("$name — coming in a later iteration", color = Color(0xFFAAB8B5))
    }
}
