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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/** One secondary counter on the results screen (e.g. "Perfect" -> 12), counting up alongside the hero number. */
internal data class StatCounter(val label: String, val value: Int)

/**
 * The fullscreen, flashy transition a run makes into once it ends, replacing the old small
 * bottom-of-canvas rank/tally chrome entirely: an accent-colored panel wipes across covering the
 * frozen game completely, and behind it a huge hero number and rank/headline count up in front of a
 * slow-turning sunburst, with "Try again" and (if [onMenu] is given) "Menu" underneath. Plays
 * [sound] once as the wipe starts — a cheer or a boo for the pass/fail stages, or null for
 * Calibrate, which isn't scored that way.
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
    onRestart: () -> Unit,
    onMenu: (() -> Unit)? = null,
) {
    if (!visible) return

    val watch = LocalHudLayout.current.watch
    val wipe = remember { Animatable(0f) }
    val bounce = remember { Animatable(0.6f) }
    val count = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        if (sound != null) audioEngine.play(sound, audioClock.now())
        launch { wipe.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
        launch { bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 260f)) }
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Everything is sized off the screen's smaller dimension, so the whole scoreboard scales up
        // to fill most of a big desktop window instead of sitting tiny in the middle of it — a round
        // watch face is the one place that stays fixed-size, since its screen barely varies.
        val minDim = minOf(maxWidth, maxHeight).value
        val headlineSize = if (watch) 15.sp else (minDim * 0.075f).sp
        val heroSize = if (watch) 34.sp else (minDim * 0.22f).sp
        val statSize = if (watch) 14.sp else (minDim * 0.075f).sp
        val statLabelSize = if (watch) 9.sp else (minDim * 0.03f).sp
        val buttonTextSize = if (watch) 13.sp else (minDim * 0.05f).sp
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
                .background(Brush.radialGradient(listOf(lerp(accent, Color.Black, 0.6f), Color(0xFF06110F)))),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) { drawSunburst(rayRotation, accent) }
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
            HudText(headline, loud = true, color = Color.White, fontSize = headlineSize)
            Spacer(Modifier.height(gapTiny))
            HudText(
                heroFormat((heroValue * count.value).roundToInt()),
                loud = true,
                color = accent,
                fontSize = heroSize,
            )
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(gapSmall))
                Row(horizontalArrangement = Arrangement.spacedBy(statGap)) {
                    for (stat in stats) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HudText(
                                (stat.value * count.value).roundToInt().toString(),
                                loud = true,
                                fontSize = statSize,
                            )
                            HudText(stat.label, color = Color(0xFFAAB8B5), fontSize = statLabelSize)
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
                        HudText("Try again", fontSize = buttonTextSize)
                    }
                }
                if (onMenu != null) {
                    GameButton(accent = Color(0xFF16302D), onClick = onMenu) {
                        Box(Modifier.padding(horizontal = buttonPadding, vertical = buttonPadding / 2)) {
                            HudText("Menu", fontSize = buttonTextSize)
                        }
                    }
                }
            }
        }

        // The wipe itself: starts fully covering the screen and slides off to the right, revealing
        // everything above in its wake — the "song ends, screen wipes to results" transition.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = maxWidth * wipe.value)
                .background(Brush.horizontalGradient(listOf(lerp(accent, Color.White, 0.18f), accent))),
        )
    }
}

/** A slow-turning ring of triangular rays behind the results text, for a bit of arcade-screen energy. */
private fun DrawScope.drawSunburst(rotationDegrees: Float, accent: Color) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = hypot(size.width, size.height) / 2f * 1.2f
    val rayCount = 16
    val sweep = 360f / rayCount
    for (i in 0 until rayCount) {
        val angle = rotationDegrees + i * sweep
        val color = if (i % 2 == 0) accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f)
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
}
