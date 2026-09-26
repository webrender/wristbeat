package wristbeat.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import wristbeat.core.Grade
import wristbeat.core.Rank
import wristbeat.core.SECONDS_PER_BEAT
import wristbeat.core.SNAP_CRABS_END_BEATS
import wristbeat.core.ScoreTally
import wristbeat.core.SnapCrabsStage
import wristbeat.core.SoundId

private const val CALL_BAR_BEATS = 4.0
private const val CYCLE_BEATS = 8.0

/**
 * Snap Crabs: the lead crab snaps a pattern, then it's the player's turn one bar later.
 * A note highway (like a rhythm game's approaching notes) shows upcoming call and response
 * beats sliding toward a fixed hit line, so exactly when a tap is expected is visible ahead
 * of time, not just reacted to after the fact.
 */
@Composable
fun SnapCrabsScreen() {
    val audioClock = remember { AudioClock() }
    val audioEngine = remember { AudioEngine() }
    val haptics = remember { HapticEngine() }
    val stage = remember { SnapCrabsStage() }

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var t0 by remember { mutableStateOf(0.0) }
    var scheduledIndex by remember { mutableStateOf(0) }
    var beatPosition by remember { mutableStateOf(-1.0) }
    var lastFiredBeat by remember { mutableStateOf(-1) }
    var beatFlashPhase by remember { mutableStateOf(0f) }
    var nextLeadCueIndex by remember { mutableStateOf(0) }
    var leadPulsePhase by remember { mutableStateOf(0f) }
    var nextTargetIndex by remember { mutableStateOf(0) }
    var targetPulsePhase by remember { mutableStateOf(0f) }
    var playerPulsePhase by remember { mutableStateOf(0f) }
    var tally by remember { mutableStateOf(ScoreTally()) }
    val runLengthSeconds = remember { (SNAP_CRABS_END_BEATS * SECONDS_PER_BEAT).roundToInt() }

    LaunchedEffect(started, finished) {
        var lastFrameMillis = 0L
        while (started && !finished) {
            val frameMillis = withFrameMillis { it }
            val dt = if (lastFrameMillis == 0L) 0f else (frameMillis - lastFrameMillis) / 1000f
            lastFrameMillis = frameMillis

            val now = audioClock.now()
            val horizon = now + 0.15
            while (scheduledIndex < stage.chart.size) {
                val ev = stage.chart[scheduledIndex]
                val t = t0 + ev.beat * SECONDS_PER_BEAT
                if (t > horizon) break
                if (t >= now - 0.01) audioEngine.play(ev.sound, t, ev.param)
                scheduledIndex++
            }

            val beat = (now - t0) / SECONDS_PER_BEAT
            beatPosition = beat

            val beatIndex = floor(beat).toInt()
            if (beatIndex > lastFiredBeat) {
                lastFiredBeat = beatIndex
                beatFlashPhase = 1f
            }
            while (nextLeadCueIndex < stage.leadCues.size && beat >= stage.leadCues[nextLeadCueIndex]) {
                nextLeadCueIndex++
                leadPulsePhase = 1f
            }
            while (nextTargetIndex < stage.targets.size && beat >= stage.targets[nextTargetIndex]) {
                nextTargetIndex++
                targetPulsePhase = 1f
            }
            beatFlashPhase = (beatFlashPhase - dt * 4f).coerceAtLeast(0f)
            leadPulsePhase = (leadPulsePhase - dt * 5f).coerceAtLeast(0f)
            targetPulsePhase = (targetPulsePhase - dt * 5f).coerceAtLeast(0f)
            playerPulsePhase = (playerPulsePhase - dt * 5f).coerceAtLeast(0f)

            stage.updateMisses(beat)
            tally = stage.tally()

            if (stage.isFinished(beat)) finished = true
        }
    }

    fun handleTap() {
        if (!started) {
            audioClock.start()
            t0 = audioClock.now() + 0.3
            scheduledIndex = 0
            lastFiredBeat = -1
            beatPosition = -1.0
            nextLeadCueIndex = 0
            nextTargetIndex = 0
            targetPulsePhase = 0f
            tally = ScoreTally()
            finished = false
            started = true
            return
        }
        if (finished) {
            started = false
            return
        }
        val now = audioClock.now()
        val beat = (now - t0) / SECONDS_PER_BEAT
        val outcome = stage.recordTap(beat)
        audioEngine.play(SoundId.PLAYER_SNAP, now)
        when (outcome?.grade) {
            Grade.PERFECT -> audioEngine.play(SoundId.PERFECT_DING, now + 0.01)
            Grade.OK -> Unit
            null -> audioEngine.play(SoundId.WHIFF, now + 0.02)
        }
        haptics.pulse()
        playerPulsePhase = 1f
        tally = stage.tally()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(rememberTapKeyModifier(::handleTap))
            .pointerInput(Unit) { detectTapGestures { handleTap() } },
        contentAlignment = Alignment.Center,
    ) {
        // Reserve room below the stage for the legend + status text (same fix as CalibrateScreen).
        val textReserve = 180.dp
        val availableHeight = (maxHeight - textReserve).coerceAtLeast(120.dp)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The beach bleeds to fill the whole available width; the crabs and note highway stay
            // centered in whatever square fits, same fullscreen-desktop treatment as CalibrateScreen.
            Canvas(modifier = Modifier.fillMaxWidth().height(availableHeight)) {
                val squareExtent = minOf(size.width, size.height)
                val squareLeft = (size.width - squareExtent) / 2f
                val squareTop = (size.height - squareExtent) / 2f
                fun sx(fraction: Float) = squareLeft + fraction * squareExtent
                fun sy(fraction: Float) = squareTop + fraction * squareExtent

                drawBeachBackground(size.width, size.height)
                // A translucent panel behind the stage keeps the highway and crabs legible over the scenery.
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.18f),
                    topLeft = Offset(squareLeft, squareTop),
                    size = Size(squareExtent, squareExtent),
                    cornerRadius = CornerRadius(squareExtent * 0.06f),
                )

                drawNoteHighway(
                    leadCues = stage.leadCues,
                    targets = stage.targets,
                    beatPosition = beatPosition,
                    lookaheadBeats = 2.5,
                    hitFlash = maxOf(beatFlashPhase * 0.5f, targetPulsePhase),
                    laneY = sy(0.32f),
                    laneLeftX = sx(0.20f),
                    laneRightX = sx(0.92f),
                )

                val bodyRadius = squareExtent * 0.13f
                drawCrab(Offset(sx(0.28f), sy(0.66f)), bodyRadius, Color(0xFFDE4636), leadPulsePhase)
                drawCrab(Offset(sx(0.72f), sy(0.66f)), bodyRadius, Color(0xFF3F82D8), playerPulsePhase)
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(Color(0xFFDE4636), "Call")
                LegendDot(Color(0xFF6FB6FF), "Your tap")
            }
            Spacer(Modifier.height(4.dp))

            when {
                finished -> {
                    val rank = when (tally.rank) {
                        Rank.SUPERB -> "Superb!"
                        Rank.OK -> "OK"
                        Rank.TRY_AGAIN -> "Try again"
                    }
                    Text(rank, color = Color.White)
                    Text(
                        "Perfect ${tally.perfect} · OK ${tally.ok} · Miss ${tally.miss}",
                        color = Color(0xFFAAB8B5),
                    )
                    Text("Tap to try again", color = Color(0xFFAAB8B5))
                }
                !started -> {
                    Text("Tap to start", color = Color.White)
                    Text(
                        "Watch the lead crab snap a pattern, then repeat it one bar later " +
                            "(~${runLengthSeconds}s)",
                        color = Color(0xFFAAB8B5),
                    )
                }
                else -> {
                    Text(sectionLabel(beatPosition), color = Color.White)
                    Text(
                        "Perfect ${tally.perfect} · OK ${tally.ok} · Miss ${tally.miss}",
                        color = Color(0xFFAAB8B5),
                    )
                }
            }
        }
    }
}

private fun sectionLabel(beat: Double): String = when {
    beat < CALL_BAR_BEATS -> "Get ready…"
    (beat - CALL_BAR_BEATS) % CYCLE_BEATS < CALL_BAR_BEATS -> "Watch the lead crab"
    else -> "Your turn — repeat it"
}

/**
 * A note highway: upcoming lead-crab calls (top row) and player targets (bottom row) slide in
 * from the right and arrive at the hit line exactly on their beat, giving the same anticipation
 * as Calibrate's sweep but tied to the actual, sparse call-and-response events instead of every beat.
 */
private fun DrawScope.drawNoteHighway(
    leadCues: List<Double>,
    targets: List<Double>,
    beatPosition: Double,
    lookaheadBeats: Double,
    hitFlash: Float,
    laneY: Float,
    laneLeftX: Float,
    laneRightX: Float,
) {
    val rowGap = size.height * 0.045f
    val leadRowY = laneY - rowGap
    val targetRowY = laneY + rowGap

    drawLine(Color.White.copy(alpha = 0.08f), Offset(laneLeftX, leadRowY), Offset(laneRightX, leadRowY), strokeWidth = 2f)
    drawLine(Color.White.copy(alpha = 0.12f), Offset(laneLeftX, targetRowY), Offset(laneRightX, targetRowY), strokeWidth = 2f)

    // Hit line: spans both rows, flashes on every beat and flares brighter exactly when a
    // target arrives, so it doubles as both a metronome and the "tap now" cue.
    drawLine(
        Color(0xFFFFE29A).copy(alpha = 0.35f + 0.65f * hitFlash),
        Offset(laneLeftX, leadRowY - 10f),
        Offset(laneLeftX, targetRowY + 10f),
        strokeWidth = 3f + 6f * hitFlash,
    )

    fun xFor(beat: Double): Float {
        val fraction = ((beat - beatPosition) / lookaheadBeats).toFloat()
        return laneLeftX + fraction * (laneRightX - laneLeftX)
    }

    for (cue in leadCues) {
        val untilCue = cue - beatPosition
        if (untilCue < -0.05 || untilCue > lookaheadBeats) continue
        drawCircle(Color(0xFFDE4636).copy(alpha = 0.75f), radius = 6f, center = Offset(xFor(cue), leadRowY))
    }
    for (target in targets) {
        val untilTarget = target - beatPosition
        if (untilTarget < -0.05 || untilTarget > lookaheadBeats) continue
        drawCircle(Color(0xFF6FB6FF), radius = 8f, center = Offset(xFor(target), targetRowY))
    }
}

/** A flat-shape beach scene (sky, sun, water, sand) that bleeds to fill the whole canvas, per HANDOFF's "extended background" note. */
private fun DrawScope.drawBeachBackground(w: Float, h: Float) {
    val horizon = h * 0.42f
    drawRect(
        brush = Brush.verticalGradient(colors = listOf(Color(0xFFFF8E64), Color(0xFFFFD49A)), startY = 0f, endY = horizon),
        size = Size(w, horizon),
    )
    drawCircle(Color(0xFFFFF1C4), radius = h * 0.09f, center = Offset(w * 0.84f, h * 0.15f))
    val waterHeight = h * 0.07f
    drawRect(color = Color(0xFF2E9C9A), topLeft = Offset(0f, horizon), size = Size(w, waterHeight))
    drawRect(
        color = Color(0xFF3AB0AC),
        topLeft = Offset(0f, horizon),
        size = Size(w, waterHeight * 0.35f),
    )
    val sandTop = horizon + waterHeight
    drawRect(color = Color(0xFFF3D29C), topLeft = Offset(0f, sandTop), size = Size(w, h - sandTop))
    val sandHeight = h - sandTop
    for (i in 0 until 70) {
        val fx = (i * 53 % 977) / 977f
        val fy = (i * 131 % 613) / 613f
        drawRect(Color(0xFFE3BC80), topLeft = Offset(fx * w, sandTop + fy * sandHeight), size = Size(3f, 2f))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color(0xFFAAB8B5))
    }
}

/** A simplified crab: a body circle with two claw lines that swing open on [pulsePhase] (0 = closed, 1 = snapped). */
private fun DrawScope.drawCrab(pos: Offset, bodyRadius: Float, color: Color, pulsePhase: Float) {
    val spread = (18f + pulsePhase * 55f) * (PI.toFloat() / 180f)
    val clawLen = bodyRadius * 1.4f
    for (side in listOf(-1f, 1f)) {
        val angle = -PI.toFloat() / 2f + side * (0.35f + spread)
        val clawEnd = Offset(pos.x + clawLen * cos(angle), pos.y + clawLen * sin(angle))
        drawLine(color, pos, clawEnd, strokeWidth = bodyRadius * 0.28f)
        drawCircle(color, radius = bodyRadius * 0.22f, center = clawEnd)
    }
    drawCircle(color, radius = bodyRadius, center = pos)
}
