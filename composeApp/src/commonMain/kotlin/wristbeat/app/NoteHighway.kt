package wristbeat.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope

/** [DOT] is a tap; [SWIPE] is a slanted arrow, so a swipe target can't be mistaken for a tap. */
internal enum class NoteShape { DOT, SWIPE }

/** One marker on a [drawNoteHighway] row, arriving at the hit line on [beat]. */
internal data class HighwayNote(
    val beat: Double,
    val color: Color,
    val radius: Float,
    val shape: NoteShape = NoteShape.DOT,
)

/**
 * A note highway: cues (top row) and player targets (bottom row) slide in from the right and
 * arrive at the hit line exactly on their beat, so exactly when an input is expected is visible
 * ahead of time, not just reacted to after the fact. Shared by every stage with a chart toggle.
 */
internal fun DrawScope.drawNoteHighway(
    cues: List<HighwayNote>,
    targets: List<HighwayNote>,
    beatPosition: Double,
    lookaheadBeats: Double,
    hitFlash: Float,
    laneY: Float,
    laneLeftX: Float,
    laneRightX: Float,
) {
    val rowGap = size.height * 0.045f
    val cueRowY = laneY - rowGap
    val targetRowY = laneY + rowGap

    // A HUD ribbon behind just the highway (not the whole stage) keeps the lanes legible over the
    // scenery without covering it in a web-card rectangle.
    val padding = (laneRightX - laneLeftX) * 0.05f
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.32f),
                Color.Black.copy(alpha = 0.32f),
                Color.Transparent,
            ),
            startX = laneLeftX - padding,
            endX = laneRightX + padding,
        ),
        topLeft = Offset(laneLeftX - padding, cueRowY - rowGap * 1.3f),
        size = Size((laneRightX - laneLeftX) + padding * 2f, rowGap * 2.6f),
    )

    drawLine(Color.White.copy(alpha = 0.08f), Offset(laneLeftX, cueRowY), Offset(laneRightX, cueRowY), strokeWidth = 2f)
    drawLine(Color.White.copy(alpha = 0.12f), Offset(laneLeftX, targetRowY), Offset(laneRightX, targetRowY), strokeWidth = 2f)

    // Hit line: spans both rows, flashes on every beat and flares brighter exactly when a
    // target arrives, so it doubles as both a metronome and the "act now" cue.
    drawLine(
        Color(0xFFFFE29A).copy(alpha = 0.35f + 0.65f * hitFlash),
        Offset(laneLeftX, cueRowY - 10f),
        Offset(laneLeftX, targetRowY + 10f),
        strokeWidth = 3f + 6f * hitFlash,
    )

    fun drawRow(notes: List<HighwayNote>, y: Float) {
        for (note in notes) {
            val until = note.beat - beatPosition
            if (until < -0.05 || until > lookaheadBeats) continue
            val x = laneLeftX + (until / lookaheadBeats).toFloat() * (laneRightX - laneLeftX)
            when (note.shape) {
                NoteShape.DOT -> drawCircle(note.color, radius = note.radius, center = Offset(x, y))
                NoteShape.SWIPE -> drawSwipeMarker(Offset(x, y), note.radius, note.color)
            }
        }
    }
    drawRow(cues, cueRowY)
    drawRow(targets, targetRowY)
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
