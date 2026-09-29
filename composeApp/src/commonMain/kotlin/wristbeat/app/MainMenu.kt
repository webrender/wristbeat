package wristbeat.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import wristbeat.core.SECONDS_PER_BEAT

/**
 * The app's home screen on phone/web: picks a stage and toggles the note highway, so no stage
 * screen needs its own title, tabs or chart button while it's being played. `wearApp`'s watch menu
 * is built from the same pieces ([MenuBackdrop], [MenuTitle], [StageMenuEntry], [ChartToggle]) in a
 * round-screen scrolling list. Everything here scales off the screen's smaller dimension (see
 * [StageResults], which uses the same trick), so the menu fills most of a big desktop window
 * instead of sitting small in the middle of it.
 */
@Composable
fun MainMenu(calibration: Calibration, chart: ChartSetting, onSelect: (Stage) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minDim = minOf(maxWidth, maxHeight).value
        // Sized so all six entries and the chart toggle fit without scrolling.
        val titleSize = (minDim * 0.08f).sp
        val entryLabelSize = (minDim * 0.043f).sp
        val entrySubtitleSize = (minDim * 0.023f).sp
        val toggleTextSize = (minDim * 0.032f).sp
        val entryPadding = (minDim * 0.0085f).dp
        val emblemSize = (minDim * 0.086f).dp
        val menuWidth = (minOf(maxWidth, maxHeight) * 1.35f).coerceAtMost(maxWidth * 0.92f)
        val beat = rememberMenuBeat()

        MenuBackdrop()

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(menuWidth)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // A safety net, not the primary design: everything above is sized to fit without
                // scrolling, but a scroll keeps an unusually narrow/short window from clipping it.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(entryPadding),
        ) {
            MenuTitle(titleSize, beat)
            Spacer(Modifier.height(entryPadding))
            for (stage in Stage.entries) {
                StageMenuEntry(
                    stage = stage,
                    // Only Calibrate has a subtitle (its saved offset); the stages speak for
                    // themselves through their emblems.
                    subtitle = if (stage == Stage.CALIBRATE) calibration.statusLine() else null,
                    beat = beat,
                    labelSize = entryLabelSize,
                    subtitleSize = entrySubtitleSize,
                    padding = entryPadding,
                    emblemSize = emblemSize,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onSelect(stage) },
                )
            }
            Spacer(Modifier.height(entryPadding))
            ChartToggle(chart, modifier = Modifier.fillMaxWidth(), fontSize = toggleTextSize)
        }
    }
}

/**
 * A free-running beat count at the shared tempo, for the title's bounce and the emblems. It's a
 * lambda so callers read it only while drawing, and the menu redraws each frame without
 * recomposing.
 */
@Composable
fun rememberMenuBeat(): () -> Double {
    val infinite = rememberInfiniteTransition(label = "menu-beat")
    val beatCount = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 64f,
        animationSpec = infiniteRepeatable(tween((SECONDS_PER_BEAT * 64 * 1000).toInt(), easing = LinearEasing)),
        label = "menu-beat",
    )
    return remember(beatCount) { { beatCount.value.toDouble() } }
}

/** The menu's full-screen animated backdrop (see [drawMenuBackdrop]). */
@Composable
fun MenuBackdrop(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "menu-bg")
    // A full breath (swell then relax) every two beats of the shared tempo, so the backdrop
    // feels alive to the game's own rhythm rather than an arbitrary decorative pulse.
    val beatPulse = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween((SECONDS_PER_BEAT * 1000).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "beat-pulse",
    )
    val drift = infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "particle-drift",
    )
    Canvas(modifier = modifier.fillMaxSize().background(Color(0xFF0A1614))) {
        drawMenuBackdrop(beatPulse.value, drift.value)
    }
}

/**
 * One stage's menu button: its animated emblem on the left, balanced by an equal gap on the right
 * so the outlined label stays centred, with an optional [subtitle] underneath.
 */
@Composable
fun StageMenuEntry(
    stage: Stage,
    subtitle: String?,
    beat: () -> Double,
    labelSize: TextUnit,
    subtitleSize: TextUnit,
    padding: Dp,
    emblemSize: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = stage.accent
    GameButton(modifier = modifier, accent = accent, enabled = stage.enabled, onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(emblemSize)) {
                when (stage) {
                    Stage.CALIBRATE -> drawCalibrateEmblem(beat())
                    Stage.SNAP_CRABS -> drawSnapCrabsEmblem(beat())
                    Stage.MANGO_CHOP -> drawMangoChopEmblem(beat())
                    Stage.BONGO_BLITZ -> drawBongoBlitzEmblem(beat())
                    Stage.REMIX_1 -> drawRemix1Emblem(beat())
                    Stage.NIGHT_DRIFT -> drawNightDriftEmblem(beat())
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(vertical = padding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Near-white with a faint tint of the button's accent, outlined in a deep shade of it,
                // like the title.
                OutlinedHudText(
                    stage.label,
                    fontSize = labelSize,
                    color = lerp(Color.White, accent, 0.18f),
                    outline = lerp(accent, Color.Black, 0.62f),
                )
                if (subtitle != null) HudText(subtitle, color = lerp(accent, Color.Black, 0.72f), fontSize = subtitleSize)
            }
            Spacer(Modifier.width(emblemSize))
        }
    }
}

/**
 * The game's title as chunky outlined letters, each in a stage's accent color, hopping one after
 * another in a wave that travels across the word once per beat.
 */
@Composable
fun MenuTitle(fontSize: TextUnit, beat: () -> Double, text: String = "Wristbeat") {
    val colors = Stage.entries.map { lerp(it.accent, Color.White, 0.25f) }
    val hop = with(LocalDensity.current) { fontSize.toPx() } * 0.12f
    Row {
        for ((i, letter) in text.withIndex()) {
            OutlinedHudText(
                letter.toString(),
                fontSize = fontSize,
                color = colors[i % colors.size],
                modifier = Modifier.graphicsLayer {
                    val phase = ((beat() - i * 0.09) % 1.0 + 1.0) % 1.0
                    val lift = if (phase < 0.35) sin(phase / 0.35 * PI).toFloat() else 0f
                    translationY = -lift * hop
                },
            )
        }
    }
}

/**
 * A dark gradient glow that breathes with [beatPulse], behind a field of drifting bubbles in each
 * stage's accent color — a bit of arcade-menu life behind the tab list, in the spirit of
 * [StageResults]'s sunburst but distinct from it, so the two screens don't feel identical.
 */
private fun DrawScope.drawMenuBackdrop(beatPulse: Float, drift: Float) {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF1C3B37).copy(alpha = 0.9f), Color(0xFF0A1614)),
            center = Offset(w / 2f, h * 0.32f),
            radius = h * (0.55f + 0.08f * beatPulse),
        ),
    )

    val colors = listOf(Color(0xFF2FBF9E), Color(0xFFF07A5E), Color(0xFFFFB320), Color.White)
    val count = 26
    for (i in 0 until count) {
        val seed = (i * 0.6180339887f) % 1f
        val v = (drift + seed) % 1f
        val baseX = ((i * 137) % 977) / 977f
        val wobbleFreq = 1.5f + (i % 3)
        val wobbleAmp = w * (0.015f + 0.01f * (i % 4))
        val x = baseX * w + sin(v * 2f * PI.toFloat() * wobbleFreq + i) * wobbleAmp
        val y = h * (1f - v)
        val radius = h * (0.006f + 0.006f * (i % 5))
        val edgeFade = when {
            v < 0.08f -> v / 0.08f
            v > 0.92f -> (1f - v) / 0.08f
            else -> 1f
        }
        val color = colors[i % colors.size]
        val center = Offset(x, y)
        drawCircle(color.copy(alpha = 0.22f * edgeFade), radius = radius, center = center)
        drawCircle(color.copy(alpha = 0.55f * edgeFade), radius = radius, center = center, style = Stroke(width = radius * 0.3f))
    }
}
