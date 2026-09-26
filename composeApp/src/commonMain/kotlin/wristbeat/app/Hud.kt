package wristbeat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bold, shadowed, letter-spaced, all-caps HUD text standing in for a real display font: reads as
 * game overlay copy rather than default Material body text. [loud] is for headline-weight lines
 * (titles, ranks); the default weight is for secondary status/stat readouts.
 */
@Composable
fun HudText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    loud: Boolean = false,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontWeight = FontWeight.Black,
        fontSize = if (loud) 22.sp else 13.sp,
        letterSpacing = if (loud) 0.6.sp else 0.8.sp,
        textAlign = TextAlign.Center,
        style = TextStyle(
            shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 2f), blurRadius = 6f),
        ),
    )
}

/** A small translucent pill behind a HUD readout — a compact game-HUD tag, not a page-wide card. */
@Composable
fun HudChip(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.34f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        content()
    }
}
