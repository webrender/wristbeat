package wristbeat.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

internal enum class NoteShape { DOT, DIAMOND }

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
                NoteShape.DIAMOND -> drawPath(
                    Path().apply {
                        moveTo(x, y - note.radius)
                        lineTo(x + note.radius, y)
                        lineTo(x, y + note.radius)
                        lineTo(x - note.radius, y)
                        close()
                    },
                    note.color,
                )
            }
        }
    }
    drawRow(cues, cueRowY)
    drawRow(targets, targetRowY)
}
