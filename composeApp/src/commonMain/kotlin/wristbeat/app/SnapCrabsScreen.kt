package wristbeat.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Reuses the same anticipatory-sweep idea from Calibrate (a marker that arrives on the beat,
 * not just a flash after it) so the "when do I click" question has one consistent answer
 * across stages.
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
            }
            beatFlashPhase = (beatFlashPhase - dt * 4f).coerceAtLeast(0f)
            leadPulsePhase = (leadPulsePhase - dt * 5f).coerceAtLeast(0f)
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
            .pointerInput(Unit) { detectTapGestures { handleTap() } },
        contentAlignment = Alignment.Center,
    ) {
        val diameter = if (maxWidth < maxHeight) maxWidth else maxHeight

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(diameter)) {
                drawCircle(color = Color(0xFF123B3D), radius = size.minDimension / 2f)

                val beatMarkerCenter = Offset(center.x, size.height * 0.22f)
                drawBeatIndicator(beatMarkerCenter, size.minDimension * 0.14f, beatPosition, beatFlashPhase, started && !finished)

                val bodyRadius = size.minDimension * 0.13f
                drawCrab(
                    Offset(size.width * 0.28f, size.height * 0.62f),
                    bodyRadius,
                    Color(0xFFDE4636),
                    leadPulsePhase,
                )
                drawCrab(
                    Offset(size.width * 0.72f, size.height * 0.62f),
                    bodyRadius,
                    Color(0xFF3F82D8),
                    playerPulsePhase,
                )
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

/** Same anticipatory-sweep + on-beat flash as Calibrate, just smaller and off to the side. */
private fun DrawScope.drawBeatIndicator(pos: Offset, radius: Float, beatPosition: Double, flashPhase: Float, active: Boolean) {
    drawCircle(Color.White.copy(alpha = 0.10f), radius = radius, center = pos, style = Stroke(width = 2f))
    val phase = if (beatPosition < 0) 0.0 else beatPosition - floor(beatPosition)
    val angle = (-90f + phase.toFloat() * 360f) * (PI.toFloat() / 180f)
    val marker = Offset(pos.x + radius * cos(angle), pos.y + radius * sin(angle))
    if (active) {
        drawLine(Color(0xFFFFB320), pos, marker, strokeWidth = 3f)
        drawCircle(Color(0xFFFFB320), radius = 5f, center = marker)
    }
    drawCircle(Color.White, radius = 6f + 10f * flashPhase, center = pos, alpha = 0.15f + 0.5f * flashPhase)
    drawCircle(Color.White, radius = 4f, center = pos)
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
