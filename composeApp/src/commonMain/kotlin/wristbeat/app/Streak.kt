package wristbeat.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The streak counter only shows once the player has more than this many Perfects in a row. */
private const val STREAK_SHOWN_ABOVE = 5

private val STREAK_INK = Color(0xFF3A1A08)

/**
 * The on-screen Perfect streak: a gold, inked starburst holding the count, over a red "Streak"
 * ribbon, in the same cartoon style as the stage art. It pops in once the player has more than
 * five Perfects in a row, throbs with every beat of [beat] (read at draw time, so the screen doesn't
 * recompose each frame), gives a bigger pop with a spark ring on each further Perfect, and
 * disappears the moment the streak breaks — the next streak starts it over from scratch.
 *
 * Sits above the note highway at the top of the centered stage square, like the stage art does.
 */
@Composable
internal fun BoxScope.StreakBadge(streak: Int, beat: () -> Double, visible: Boolean = true) {
    val showing = visible && streak > STREAK_SHOWN_ABOVE
    // Keep the last count on the badge while it shrinks away, rather than flashing to 0.
    var shown by remember { mutableStateOf(streak) }
    if (showing) shown = streak
    val appear by animateFloatAsState(
        targetValue = if (showing) 1f else 0f,
        animationSpec = if (showing) spring(dampingRatio = 0.5f, stiffness = 600f) else tween(140),
        label = "streak-appear",
    )
    val pop = remember { Animatable(0f) }
    LaunchedEffect(streak) {
        if (streak > STREAK_SHOWN_ABOVE) {
            pop.snapTo(1f)
            pop.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
        }
    }
    if (appear <= 0.001f) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val extent = minOf(maxWidth, maxHeight)
        val badge = extent * 0.18f
        val top = (maxHeight - extent) / 2f + extent * 0.02f
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = top)
                .size(badge * 1.3f, badge * 1.05f)
                .graphicsLayer {
                    val b = beat()
                    val throb = if (b < 0) 0f else (1f - (b - floor(b)).toFloat()).let { it * it * it }
                    val scale = appear * (1f + 0.05f * throb + 0.22f * pop.value)
                    scaleX = scale
                    scaleY = scale
                    rotationZ = -5f * pop.value
                    alpha = appear.coerceIn(0f, 1f)
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) { drawStreakBadge(beat(), pop.value) }
            val numberSize = with(density) { (badge * (if (shown >= 100) 0.3f else 0.38f)).toSp() }
            Box(modifier = Modifier.align(Alignment.TopCenter).size(badge * 1.3f, badge * 0.78f), contentAlignment = Alignment.Center) {
                OutlinedHudText(shown.toString(), fontSize = numberSize, color = Color(0xFFFFFBEA), outline = STREAK_INK)
            }
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).size(badge * 1.3f, badge * 0.3f).offset(y = (-2).dp),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedHudText("Streak", fontSize = with(density) { (badge * 0.13f).toSp() }, color = Color.White, outline = Color(0xFF5A1010))
            }
        }
    }
}

/**
 * The badge art: a slowly turning gold starburst with an inked outline and glossy highlight, a
 * spark ring that bursts out on [pop], and a red ribbon with folded tails across the bottom.
 */
private fun DrawScope.drawStreakBadge(beat: Double, pop: Float) {
    val w = size.width
    val h = size.height
    val center = Offset(w / 2f, h * 0.39f)
    val r = h * 0.39f
    val ink = STREAK_INK

    // Sparks flying off on each new Perfect.
    if (pop > 0f) {
        val spread = 1f - pop
        for (i in 0 until 10) {
            val a = (i * 36f + 18f) * PI.toFloat() / 180f
            val d = Offset(cos(a), sin(a))
            val inner = r * (1.02f + 0.3f * spread)
            drawLine(Color(0xFFFFF1A8).copy(alpha = pop), center + d * inner, center + d * (inner + r * 0.25f * pop), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
        }
    }

    // The starburst, turning a little with the song.
    val spin = (beat.coerceAtLeast(0.0) * 6.0).toFloat() * PI.toFloat() / 180f
    val points = 14
    val star = Path()
    for (i in 0 until points * 2) {
        val a = spin + i * PI.toFloat() / points - PI.toFloat() / 2f
        val rr = if (i % 2 == 0) r else r * 0.8f
        val p = center + Offset(cos(a), sin(a)) * rr
        if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
    }
    star.close()
    drawPath(star, Color.Black.copy(alpha = 0.25f), style = Stroke(width = r * 0.14f, join = StrokeJoin.Round))
    drawPath(
        star,
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFF4B8), Color(0xFFFFC93D), lerp(Color(0xFFF08A1C), Color(0xFFFF5E2A), 0.3f * pop)),
            center = center - Offset(r * 0.25f, r * 0.3f),
            radius = r * 1.3f,
        ),
    )
    drawPath(star, ink, style = Stroke(width = r * 0.075f, join = StrokeJoin.Round))
    // An inner ring and a glossy highlight, like the note gems.
    drawCircle(Color(0xFFFFE27A).copy(alpha = 0.6f), radius = r * 0.64f, center = center, style = Stroke(width = r * 0.05f))
    drawArc(
        Color.White.copy(alpha = 0.7f),
        startAngle = 200f,
        sweepAngle = 60f,
        useCenter = false,
        topLeft = center - Offset(r * 0.5f, r * 0.5f),
        size = androidx.compose.ui.geometry.Size(r, r),
        style = Stroke(width = r * 0.08f, cap = StrokeCap.Round),
    )

    // The ribbon: a red band with folded tails tucked behind at each end.
    val bandTop = h * 0.72f
    val bandBottom = h * 0.98f
    val bandLeft = w * 0.14f
    val bandRight = w * 0.86f
    val tail = (bandBottom - bandTop) * 0.8f
    for (side in listOf(-1f, 1f)) {
        val x = if (side < 0) bandLeft else bandRight
        val tailPath = Path().apply {
            moveTo(x, bandTop + tail * 0.25f)
            lineTo(x + side * tail * 1.3f, bandTop + tail * 0.25f)
            lineTo(x + side * tail * 0.9f, (bandTop + bandBottom) / 2f + tail * 0.25f)
            lineTo(x + side * tail * 1.3f, bandBottom + tail * 0.25f)
            lineTo(x, bandBottom + tail * 0.25f)
            close()
        }
        drawPath(tailPath, Color(0xFFA82A22))
        drawPath(tailPath, ink, style = Stroke(width = r * 0.06f, join = StrokeJoin.Round))
    }
    val band = Path().apply { addRect(Rect(bandLeft, bandTop, bandRight, bandBottom)) }
    drawPath(band, brush = Brush.verticalGradient(listOf(Color(0xFFFF6A55), Color(0xFFE4513A), Color(0xFFB8322A)), startY = bandTop, endY = bandBottom))
    drawLine(Color.White.copy(alpha = 0.35f), Offset(bandLeft + r * 0.1f, bandTop + r * 0.07f), Offset(bandRight - r * 0.1f, bandTop + r * 0.07f), strokeWidth = r * 0.05f, cap = StrokeCap.Round)
    drawPath(band, ink, style = Stroke(width = r * 0.07f, join = StrokeJoin.Round))
}
