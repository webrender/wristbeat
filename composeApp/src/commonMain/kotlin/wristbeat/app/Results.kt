package wristbeat.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch
import wristbeat.core.SoundId

/**
 * One secondary counter on the results screen (e.g. "Perfect" -> 12), counting up alongside the
 * hero number, its label tinted [color].
 */
internal data class StatCounter(val label: String, val value: Int, val color: Color = Color(0xFFAAB8B5))

/** The results screen's usual Perfect/OK/Miss row, color-coded gold, green and coral. */
internal fun gradeStats(perfect: Int, ok: Int, miss: Int): List<StatCounter> = listOf(
    StatCounter("Perfect", perfect, Color(0xFFFFD166)),
    StatCounter("OK", ok, Color(0xFF7FF0CF)),
    StatCounter("Miss", miss, Color(0xFFFF8A7A)),
)

/**
 * The fullscreen, flashy transition a run makes into once it ends, replacing the old small
 * bottom-of-canvas rank/tally chrome entirely: an accent-colored panel wipes across covering the
 * frozen game completely, and behind it a stamped headline and a huge outlined hero number count
 * up in front of a slow-turning sunburst, with the stats in a panel and "Try again" and (if
 * [onMenu] is given) "Menu" underneath. [passed] sets the mood: confetti rains down on a pass, a
 * gloomy drizzle falls on a fail, and null (Calibrate, which isn't scored pass/fail) has neither.
 * Plays [sound] once as the wipe starts.
 */
@Composable
internal fun BoxScope.StageResults(
    visible: Boolean,
    accent: Color,
    sound: SoundId?,
    audioEngine: AudioEngine,
    audioClock: AudioClock,
    headline: String,
    heroValue: Int,
    heroFormat: (Int) -> String = { it.toString() },
    stats: List<StatCounter> = emptyList(),
    passed: Boolean? = null,
    onRestart: () -> Unit,
    onMenu: (() -> Unit)? = null,
) {
    if (!visible) return

    val watch = LocalHudLayout.current.watch
    val wipe = remember { Animatable(0f) }
    val bounce = remember { Animatable(0.6f) }
    val stamp = remember { Animatable(2.2f) }
    val count = remember { Animatable(0f) }
    var elapsed by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        if (sound != null) audioEngine.play(sound, audioClock.now())
        launch { wipe.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
        launch { bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 260f)) }
        // The headline slams down like a rubber stamp once the wipe has cleared.
        launch {
            kotlinx.coroutines.delay(380)
            stamp.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 900f))
        }
        launch {
            val start = withFrameMillis { it }
            while (true) elapsed = (withFrameMillis { it } - start) / 1000f
        }
        count.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }

    val infinite = rememberInfiniteTransition(label = "results-burst")
    val rayRotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
        label = "ray-rotation",
    )

    // A button row fades/slides up once the count-up is most of the way there, so it doesn't
    // compete for attention with the numbers ticking up.
    val buttonsIn = ((count.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
    val mood = when (passed) {
        false -> lerp(accent, Color(0xFF5A6A80), 0.7f)
        else -> accent
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Everything is sized off the screen's smaller dimension, so the whole scoreboard scales up
        // to fill most of a big desktop window instead of sitting tiny in the middle of it — a round
        // watch face is the one place that stays fixed-size, since its screen barely varies.
        val minDim = minOf(maxWidth, maxHeight).value
        val headlineSize = if (watch) 16.sp else (minDim * 0.09f).sp
        val heroSize = if (watch) 34.sp else (minDim * 0.2f).sp
        val statSize = if (watch) 14.sp else (minDim * 0.07f).sp
        val statLabelSize = if (watch) 9.sp else (minDim * 0.03f).sp
        val buttonTextSize = if (watch) 13.sp else (minDim * 0.045f).sp
        val buttonPadding = if (watch) 0.dp else (minDim * 0.02f).dp
        val gapTiny = if (watch) 2.dp else (minDim * 0.012f).dp
        val gapSmall = if (watch) 6.dp else (minDim * 0.025f).dp
        val gapMed = if (watch) 10.dp else (minDim * 0.04f).dp
        val statGap = if (watch) 10.dp else (minDim * 0.05f).dp
        val buttonGap = if (watch) 8.dp else (minDim * 0.03f).dp

        // Fully opaque backdrop: the results screen replaces the frozen game entirely, it doesn't
        // just dim it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(lerp(mood, Color.Black, 0.45f), Color(0xFF07060F)))),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawSunburst(rayRotation, mood)
                when (passed) {
                    true -> drawConfetti(elapsed, accent)
                    false -> drawDrizzle(elapsed)
                    null -> Unit
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }
                // A safety net, not the primary design: sized to fit without scrolling, but this
                // keeps an unusually narrow/short window from clipping it.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedHudText(
                headline,
                fontSize = headlineSize,
                color = if (passed == false) Color(0xFFC9D3E0) else Color(0xFFFFF4D6),
                modifier = Modifier.graphicsLayer {
                    scaleX = stamp.value
                    scaleY = stamp.value
                    alpha = ((2.2f - stamp.value) / 0.6f).coerceIn(0f, 1f)
                    rotationZ = -4f
                },
            )
            val hero = heroFormat((heroValue * count.value).roundToInt())
            if (hero.isNotEmpty()) {
                Spacer(Modifier.height(gapTiny))
                OutlinedHudText(hero, fontSize = heroSize, color = lerp(accent, Color.White, 0.15f))
            }
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(gapSmall))
                HudChip(accent = Color(0xFF1A1730)) {
                    Row(
                        modifier = Modifier.padding(horizontal = gapSmall, vertical = gapTiny),
                        horizontalArrangement = Arrangement.spacedBy(statGap),
                    ) {
                        for (stat in stats) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                HudText(
                                    (stat.value * count.value).roundToInt().toString(),
                                    loud = true,
                                    fontSize = statSize,
                                )
                                HudText(stat.label, color = stat.color, fontSize = statLabelSize)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(gapMed))
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = buttonsIn
                        translationY = (1f - buttonsIn) * 24f
                    },
                horizontalArrangement = Arrangement.spacedBy(buttonGap),
            ) {
                GameButton(accent = accent, onClick = onRestart) {
                    Box(Modifier.padding(horizontal = buttonPadding, vertical = buttonPadding / 2)) {
                        HudText("Try again", loud = true, fontSize = buttonTextSize)
                    }
                }
                if (onMenu != null) {
                    GameButton(accent = Color(0xFF2A2748), onClick = onMenu) {
                        Box(Modifier.padding(horizontal = buttonPadding, vertical = buttonPadding / 2)) {
                            HudText("Menu", loud = true, fontSize = buttonTextSize)
                        }
                    }
                }
            }
        }

        // The wipe itself: starts fully covering the screen and slides off to the right, revealing
        // everything above in its wake — the "song ends, screen wipes to results" transition. Its
        // leading edge is a band of diagonal stripes rather than a flat cut. Once it's fully off
        // screen it's dropped, or those stripes would stay parked on the right edge.
        if (wipe.value < 1f) Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = maxWidth * wipe.value)
                .background(Brush.horizontalGradient(listOf(lerp(accent, Color.White, 0.18f), accent))),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val band = size.minDimension * 0.08f
                var y = -band
                while (y < size.height + band) {
                    val stripe = Path().apply {
                        moveTo(0f, y)
                        lineTo(-band, y + band)
                        lineTo(-band, y + band * 1.5f)
                        lineTo(0f, y + band * 0.5f)
                        close()
                    }
                    drawPath(stripe, accent)
                    y += band
                }
            }
        }
    }
}

/** A slow-turning ring of triangular rays behind the results text, for a bit of arcade-screen energy. */
private fun DrawScope.drawSunburst(rotationDegrees: Float, accent: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = hypot(size.width, size.height) / 2f * 1.2f
    val rayCount = 18
    val sweep = 360f / rayCount
    for (i in 0 until rayCount) {
        val angle = rotationDegrees + i * sweep
        val color = if (i % 2 == 0) accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f)
        val a0 = angle * PI.toFloat() / 180f
        val a1 = (angle + sweep * 0.5f) * PI.toFloat() / 180f
        val path = Path().apply {
            moveTo(center.x, center.y)
            lineTo(center.x + radius * cos(a0), center.y + radius * sin(a0))
            lineTo(center.x + radius * cos(a1), center.y + radius * sin(a1))
            close()
        }
        drawPath(path, color)
    }
    // A soft spotlight behind the score, fading out toward the edges.
    val spot = size.minDimension * 0.55f
    drawCircle(
        brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0f)), center = center, radius = spot),
        radius = spot,
        center = center,
    )
    drawRect(
        brush = Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
            center = center,
            radius = radius,
        ),
    )
}

private fun hash01(i: Int, mul: Int, mod: Int): Float = (((i * mul + 11) % mod + mod) % mod) / mod.toFloat()

/** Confetti tumbling down the screen: seeded strips in the accent, gold, pink, mint and white, each flipping as it falls. */
private fun DrawScope.drawConfetti(t: Float, accent: Color) {
    val colors = listOf(accent, Color(0xFFFFD166), Color(0xFFFF7AA8), Color(0xFF7FF0CF), Color.White)
    val piece = size.minDimension * 0.016f
    for (i in 0 until 70) {
        val speed = 0.16f + 0.14f * hash01(i, 37, 101)
        val fall = ((t * speed + hash01(i, 71, 97) * 1.2f) % 1.2f) - 0.1f
        val x = hash01(i, 53, 89) * size.width + sin(t * (1.2f + hash01(i, 13, 7)) + i) * size.width * 0.025f
        val y = fall * size.height
        val spin = t * (120f + 240f * hash01(i, 29, 61)) + i * 40f
        val flip = cos(t * (3f + 4f * hash01(i, 19, 43)) + i)
        withTransform({
            rotate(spin, Offset(x, y))
            scale(flip, 1f, Offset(x, y))
        }) {
            drawRect(colors[i % colors.size], topLeft = Offset(x - piece * 0.5f, y - piece), size = Size(piece, piece * 2f))
        }
    }
}

/** A thin, gloomy drizzle for a failed run. */
private fun DrawScope.drawDrizzle(t: Float) {
    val len = size.minDimension * 0.04f
    for (i in 0 until 45) {
        val speed = 0.9f + 0.5f * hash01(i, 37, 101)
        val fall = ((t * speed + hash01(i, 71, 97) * 1.2f) % 1.2f) - 0.1f
        val x = hash01(i, 53, 89) * size.width - fall * size.height * 0.12f
        val y = fall * size.height
        drawLine(
            Color(0xFF9FB4D8).copy(alpha = 0.28f),
            Offset(x, y),
            Offset(x - len * 0.12f, y + len),
            strokeWidth = size.minDimension * 0.004f,
            cap = StrokeCap.Round,
        )
    }
}
