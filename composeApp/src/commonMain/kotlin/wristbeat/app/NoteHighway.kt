package wristbeat.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp

/** [DOT] is a tap; [SWIPE] is a slanted arrow, so a swipe target can't be mistaken for a tap. */
internal enum class NoteShape { DOT, SWIPE }

/**
 * One marker on a [drawNoteHighway] row, arriving at the hit line on [beat]. [radius] is in the
 * stages' 400×400 logical units; the highway scales it to the on-screen stage size.
 */
internal data class HighwayNote(
    val beat: Double,
    val color: Color,
    val radius: Float,
    val shape: NoteShape = NoteShape.DOT,
)

private const val NOTE_SIZE_BOOST = 1.6f

/**
 * A note highway: player targets slide in from the right and arrive at the hit line exactly on
 * their beat, so exactly when an input is expected is visible ahead of time, not just reacted to
 * after the fact. Shared by every stage with a chart toggle.
 */
internal fun DrawScope.drawNoteHighway(
    targets: List<HighwayNote>,
    beatPosition: Double,
    lookaheadBeats: Double,
    hitFlash: Float,
    laneY: Float,
    laneLeftX: Float,
    laneRightX: Float,
) {
    // The lane spans 72% of the 400-unit logical stage (0.20–0.92), so this maps note radii from
    // logical units to pixels; [NOTE_SIZE_BOOST] enlarges them past the prototype's sizes, which
    // read as specks on a real screen.
    val noteScale = (laneRightX - laneLeftX) / (400f * 0.72f) * NOTE_SIZE_BOOST
    val halfHeight = 13f * noteScale
    val laneWidth = laneRightX - laneLeftX

    // An inked HUD track behind just the highway (not the whole stage), in the same outlined
    // cartoon style as the stage art, keeps the lane legible over any scenery.
    val trackLeft = laneLeftX - halfHeight * 1.3f
    val trackRight = laneRightX + halfHeight * 0.6f
    val trackTop = laneY - halfHeight
    val corner = CornerRadius(halfHeight * 0.55f)
    drawRoundRect(
        Color.Black.copy(alpha = 0.25f),
        topLeft = Offset(trackLeft, trackTop + halfHeight * 0.22f),
        size = Size(trackRight - trackLeft, halfHeight * 2f),
        cornerRadius = corner,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF3B3160).copy(alpha = 0.5f), Color(0xFF1A1430).copy(alpha = 0.62f)),
            startY = trackTop,
            endY = laneY + halfHeight,
        ),
        topLeft = Offset(trackLeft, trackTop),
        size = Size(trackRight - trackLeft, halfHeight * 2f),
        cornerRadius = corner,
    )
    drawLine(
        Color.White.copy(alpha = 0.14f),
        Offset(trackLeft + corner.x, trackTop + 2.5f),
        Offset(trackRight - corner.x, trackTop + 2.5f),
        strokeWidth = 2f,
        cap = StrokeCap.Round,
    )
    drawRoundRect(
        HIGHWAY_INK,
        topLeft = Offset(trackLeft, trackTop),
        size = Size(trackRight - trackLeft, halfHeight * 2f),
        cornerRadius = corner,
        style = Stroke(width = 2.5f),
    )

    // Beat ticks scroll along with the notes, so the lane's speed reads even between targets.
    val firstTick = kotlin.math.ceil(beatPosition).toInt()
    val lastTick = kotlin.math.floor(beatPosition + lookaheadBeats).toInt()
    for (tick in firstTick..lastTick) {
        val x = laneLeftX + ((tick - beatPosition) / lookaheadBeats).toFloat() * laneWidth
        if (x < laneLeftX + halfHeight) continue
        val alpha = if (tick % 4 == 0) 0.22f else 0.1f
        drawLine(
            Color.White.copy(alpha = alpha),
            Offset(x, laneY - halfHeight * 0.55f),
            Offset(x, laneY + halfHeight * 0.55f),
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
    }

    // Hit ring: pulses on every beat and flares brighter exactly when a target arrives, so it
    // doubles as both a metronome and the "act now" cue.
    val ringRadius = halfHeight * 0.78f
    val glow = Color(0xFFFFE29A)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(glow.copy(alpha = 0.55f * hitFlash), glow.copy(alpha = 0f)),
            center = Offset(laneLeftX, laneY),
            radius = ringRadius * 2.2f,
        ),
        radius = ringRadius * 2.2f,
        center = Offset(laneLeftX, laneY),
    )
    drawCircle(Color.White.copy(alpha = 0.1f), radius = ringRadius, center = Offset(laneLeftX, laneY))
    drawCircle(
        glow.copy(alpha = 0.55f + 0.45f * hitFlash),
        radius = ringRadius * (1f + 0.12f * hitFlash),
        center = Offset(laneLeftX, laneY),
        style = Stroke(width = 3f + 3f * hitFlash),
    )

    for (note in targets) {
        val until = note.beat - beatPosition
        if (until < -0.12 || until > lookaheadBeats) continue
        val x = laneLeftX + (until / lookaheadBeats).toFloat() * laneWidth
        // Notes fade in at the far end of the lane and pop out just past the hit ring.
        val fadeIn = ((lookaheadBeats - until) / 0.3).coerceIn(0.0, 1.0).toFloat()
        val fadeOut = if (until < 0) (1.0 + until / 0.12).toFloat() else 1f
        val alpha = fadeIn * fadeOut
        val color = note.color.copy(alpha = alpha)
        val r = note.radius * noteScale * (if (until < 0) 1f + 0.4f * (1f - fadeOut) else 1f)
        when (note.shape) {
            NoteShape.DOT -> drawNoteGem(Offset(x, laneY), r, color)
            NoteShape.SWIPE -> drawSwipeMarker(Offset(x, laneY), r, color)
        }
    }
}

private val HIGHWAY_INK = Color(0xFF1A1030)

/** A tap note: an inked ball with a darker underside and a glossy highlight, like the stage art. */
private fun DrawScope.drawNoteGem(center: Offset, radius: Float, color: Color) {
    val alpha = color.alpha
    drawCircle(HIGHWAY_INK.copy(alpha = alpha), radius = radius + 2.5f, center = center)
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(lerp(color, Color.White, 0.25f), color, lerp(color, Color.Black, 0.3f)),
            startY = center.y - radius,
            endY = center.y + radius,
        ),
        radius = radius,
        center = center,
    )
    drawOval(
        Color.White.copy(alpha = 0.7f * alpha),
        topLeft = Offset(center.x - radius * 0.55f, center.y - radius * 0.7f),
        size = Size(radius * 0.7f, radius * 0.45f),
    )
}

/**
 * A swipe marker: a thick arrow slashing up and to the right, with a dark outline so it reads
 * against both the highway ribbon and the dots around it, and doesn't look like a tap at a glance.
 * [radius] is the half-size of its bounding box, matching a dot of the same radius.
 */
internal fun DrawScope.drawSwipeMarker(center: Offset, radius: Float, color: Color) {
    val r = radius * 1.25f
    val tail = Offset(center.x - r * 0.8f, center.y + r * 0.8f)
    val tip = Offset(center.x + r * 0.8f, center.y - r * 0.8f)
    // Arrowhead arms run back from the tip: one straight left, one straight down.
    val headLeft = Offset(tip.x - r * 0.9f, tip.y)
    val headDown = Offset(tip.x, tip.y + r * 0.9f)
    val stroke = r * 0.42f
    val outline = Color(0xFF2A1A08).copy(alpha = color.alpha)
    for ((c, w) in listOf(outline to stroke + r * 0.3f, color to stroke)) {
        drawLine(c, tail, tip, strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, tip, headLeft, strokeWidth = w, cap = StrokeCap.Round)
        drawLine(c, tip, headDown, strokeWidth = w, cap = StrokeCap.Round)
    }
}
