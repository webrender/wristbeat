package wristbeat.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import org.jetbrains.compose.resources.Font
import wristbeat.app.generated.resources.Res
import wristbeat.app.generated.resources.sniglet_extrabold
import wristbeat.app.generated.resources.sniglet_regular

/**
 * Sniglet (OFL-licensed, see THIRD_PARTY_LICENSES/sniglet-OFL.txt) — a bubbly, rounded display
 * face standing in for a custom game font. [loud] lines (titles, ranks, button labels) use the
 * ExtraBold cut; regular HUD readouts use the lighter cut. Two real weights, not synthetic bold,
 * so we never set `fontWeight` on the [Text] below and let Skia render the actual glyphs.
 */
@Composable
private fun hudFontFamily(loud: Boolean): FontFamily =
    FontFamily(Font(if (loud) Res.font.sniglet_extrabold else Res.font.sniglet_regular))

/**
 * Both Sniglet cuts as one family (Regular at normal weight, ExtraBold at bold and up), for UI
 * outside the game HUD that sets its own weights, like the watch's native menu.
 */
@Composable
fun wristbeatFontFamily(): FontFamily = FontFamily(
    Font(Res.font.sniglet_regular, FontWeight.Normal),
    Font(Res.font.sniglet_extrabold, FontWeight.ExtraBold),
)

/**
 * Bold, shadowed, letter-spaced, all-caps HUD text reading as game overlay copy rather than
 * default Material body text. [loud] is for headline-weight lines (titles, ranks); the default
 * weight is for secondary status/stat readouts. [fontSize] overrides the default HUD sizing for a
 * one-off need (e.g. the results screen's huge hero number); leave it unspecified everywhere else.
 */
@Composable
fun HudText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    loud: Boolean = false,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = hudFontFamily(loud),
        fontSize = fontSize.takeOrElse {
            // Watch faces are ~200dp across, so HUD copy shrinks there to fit a line or two.
            if (LocalHudLayout.current.watch) (if (loud) 13.sp else 9.sp) else (if (loud) 24.sp else 14.sp)
        },
        letterSpacing = if (loud) 0.3.sp else 0.4.sp,
        textAlign = TextAlign.Center,
        style = TextStyle(
            shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 2f), blurRadius = 6f),
        ),
    )
}

private val PanelShape = RoundedCornerShape(10.dp)

/**
 * A chunky "console button" panel: small-radius rounded corners (not a pill/stadium), a
 * top-to-bottom color bevel, a glassy top sheen, a bright rim, and a drop shadow — the shared
 * look for every clickable control and HUD backdrop in the app. Deliberately replaces flat
 * RoundedCornerShape(50) pills, which read as web chips rather than game UI (think Wii Channel
 * tiles, not Material chips). Pass [onClick] to make it interactive (tabs, toggles); omit it for
 * a static HUD readout backdrop.
 */
@Composable
fun GameButton(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF123331),
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val rim = lerp(accent, Color.Black, 0.45f)
    var panelModifier = modifier
        .shadow(elevation = 5.dp, shape = PanelShape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(PanelShape)
        .background(Brush.verticalGradient(listOf(lerp(accent, Color.White, 0.22f), accent, lerp(accent, Color.Black, 0.24f))))
        .drawBehind {
            // Glassy top sheen, like a Wii Channel tile catching a light source from above.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.20f),
                topLeft = Offset.Zero,
                size = Size(size.width, size.height * 0.46f),
            )
        }
        .border(width = 2.dp, color = rim, shape = PanelShape)
    if (onClick != null) {
        panelModifier = panelModifier.clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    }
    val watch = LocalHudLayout.current.watch
    Box(
        modifier = panelModifier.padding(horizontal = if (watch) 10.dp else 14.dp, vertical = if (watch) 5.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The dark ink that [OutlinedHudText] and the stage art outline their shapes in. */
internal val HUD_INK = Color(0xFF140A1E)

/**
 * Chunky title text: the ExtraBold cut with a thick [HUD_INK] outline and a drop shadow under a
 * solid [color] fill, the way game logos and rank stamps are lettered — for the big, few-word
 * lines (the menu title, results headline and score), not running HUD copy.
 */
@Composable
fun OutlinedHudText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    outline: Color = HUD_INK,
) {
    val strokePx = with(LocalDensity.current) { fontSize.toPx() } * 0.16f
    val family = hudFontFamily(loud = true)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text.uppercase(),
            color = outline,
            fontFamily = family,
            fontSize = fontSize,
            letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(
                drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round),
                shadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, strokePx * 0.9f), blurRadius = strokePx),
            ),
        )
        Text(
            text = text.uppercase(),
            color = color,
            fontFamily = family,
            fontSize = fontSize,
            letterSpacing = 0.3.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** A HUD readout backdrop — a [GameButton] with no click handler. */
@Composable
fun HudChip(modifier: Modifier = Modifier, accent: Color = Color(0xFF123331), content: @Composable () -> Unit) {
    GameButton(modifier = modifier, accent = accent, content = content)
}
