package wristbeat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Snap Crabs and Mango Chop are stubbed this iteration; only Calibrate is playable.
// Calibrate opens first by default per Jeremy's feedback on the prototype.
enum class Stage(val label: String, val enabled: Boolean) {
    CALIBRATE("Calibrate", enabled = true),
    SNAP_CRABS("Snap Crabs", enabled = false),
    MANGO_CHOP("Mango Chop", enabled = false),
}

@Composable
fun App() {
    var stage by remember { mutableStateOf(Stage.CALIBRATE) }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0F1B19)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))
            Text("Wristbeat", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            StagePicker(stage) { stage = it }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (stage) {
                    Stage.CALIBRATE -> CalibrateScreen()
                    Stage.SNAP_CRABS -> ComingSoon(Stage.SNAP_CRABS.label)
                    Stage.MANGO_CHOP -> ComingSoon(Stage.MANGO_CHOP.label)
                }
            }
        }
    }
}

@Composable
private fun StagePicker(current: Stage, onSelect: (Stage) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in Stage.entries) {
            OutlinedButton(onClick = { onSelect(s) }, enabled = s.enabled) {
                Text(if (s.enabled) s.label else "${s.label} (soon)")
            }
        }
    }
    Spacer(Modifier.height(if (current == Stage.CALIBRATE) 0.dp else 0.dp))
}

@Composable
private fun ComingSoon(name: String) {
    Text("$name — coming in a later iteration", color = Color(0xFFAAB8B5))
}
