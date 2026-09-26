package wristbeat.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
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

    // A single full-bleed canvas with the legend/status floated on top as a HUD overlay — no
    // header/footer flow layout, so the beach and crabs always fill the whole screen edge to edge.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(rememberTapKeyModifier(::handleTap))
            .pointerInput(Unit) { detectTapGestures { handleTap() } },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squareExtent = minOf(size.width, size.height)
            val squareLeft = (size.width - squareExtent) / 2f
            val squareTop = (size.height - squareExtent) / 2f
            fun sx(fraction: Float) = squareLeft + fraction * squareExtent
            fun sy(fraction: Float) = squareTop + fraction * squareExtent

            drawBeachBackground(size.width, size.height, beatPosition)

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
            val crabScale = bodyRadius / 46f
            val bob = beatBob(beatPosition) * bodyRadius * 0.16f
            drawCrab(
                pos = Offset(sx(0.28f), sy(0.66f)),
                scale = crabScale,
                bodyColor = Color(0xFFDE4636),
                darkColor = Color(0xFF7A1F18),
                pulsePhase = leadPulsePhase,
                bob = bob,
                hasHat = true,
            )
            drawCrab(
                pos = Offset(sx(0.72f), sy(0.66f)),
                scale = crabScale,
                bodyColor = Color(0xFF3F82D8),
                darkColor = Color(0xFF173E73),
                pulsePhase = playerPulsePhase,
                bob = bob,
                hasHat = false,
            )
        }

        HudChip(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            HudText("Snap Crabs", color = Color(0xFF2FBF9E))
        }

        HudChip(modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(Color(0xFFDE4636), "Call")
                LegendDot(Color(0xFF6FB6FF), "Your tap")
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                finished -> {
                    val rank = when (tally.rank) {
                        Rank.SUPERB -> "Superb!"
                        Rank.OK -> "OK"
                        Rank.TRY_AGAIN -> "Try again"
                    }
                    HudChip {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HudText(rank, loud = true)
                            Spacer(Modifier.height(2.dp))
                            HudText(
                                "Perfect ${tally.perfect} · OK ${tally.ok} · Miss ${tally.miss}",
                                color = Color(0xFFAAB8B5),
                            )
                            HudText("Tap to try again", color = Color(0xFFAAB8B5))
                        }
                    }
                }
                !started -> {
                    HudChip {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HudText("Tap to start", loud = true)
                            Spacer(Modifier.height(2.dp))
                            HudText(
                                "Watch the lead crab snap a pattern, then repeat it one bar later " +
                                    "(~${runLengthSeconds}s)",
                                color = Color(0xFFAAB8B5),
                            )
                        }
                    }
                }
                else -> {
                    HudChip {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HudText(sectionLabel(beatPosition), loud = true)
                            Spacer(Modifier.height(2.dp))
                            HudText(
                                "Perfect ${tally.perfect} · OK ${tally.ok} · Miss ${tally.miss}",
                                color = Color(0xFFAAB8B5),
                            )
                        }
                    }
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

    // A HUD ribbon behind just the highway (not the whole stage) keeps the lanes legible over the
    // beach without covering it in a web-card rectangle.
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
        topLeft = Offset(laneLeftX - padding, leadRowY - rowGap * 1.3f),
        size = Size((laneRightX - laneLeftX) + padding * 2f, rowGap * 2.6f),
    )

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

/**
 * A beach scene (sky, sun, animated water, foam line, sand) that bleeds to fill the whole canvas,
 * per HANDOFF's "extended background" note. Ported from the prototype's drawBeach, including the
 * scrolling wave strokes, so the scenery reads as game art instead of flat CSS-gradient rectangles.
 */
private fun DrawScope.drawBeachBackground(w: Float, h: Float, beatPosition: Double) {
    val horizon = h * 0.42f
    drawRect(
        brush = Brush.verticalGradient(colors = listOf(Color(0xFFFF8E64), Color(0xFFFFD49A)), startY = 0f, endY = horizon),
        size = Size(w, horizon),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFF6DE), Color(0xFFFFF1C4).copy(alpha = 0f)),
            center = Offset(w * 0.84f, h * 0.15f),
            radius = h * 0.16f,
        ),
        radius = h * 0.16f,
        center = Offset(w * 0.84f, h * 0.15f),
    )
    drawCircle(Color(0xFFFFF1C4), radius = h * 0.08f, center = Offset(w * 0.84f, h * 0.15f))

    val waterHeight = h * 0.07f
    drawRect(color = Color(0xFF2E9C9A), topLeft = Offset(0f, horizon), size = Size(w, waterHeight))
    drawRect(color = Color(0xFF3AB0AC), topLeft = Offset(0f, horizon), size = Size(w, waterHeight * 0.35f))

    // Wave strokes scroll with beat position, matching the prototype's shift = (beat % 2) * 20.
    val beat = beatPosition.coerceAtLeast(0.0)
    val shift = ((beat % 2.0) / 2.0 * (w * 0.09)).toFloat()
    val waveStroke = Stroke(width = 3f, cap = StrokeCap.Round)
    for (row in 0 until 2) {
        val y = horizon + waterHeight * (0.42f + row * 0.34f)
        var x = -w * 0.12f + shift + row * w * 0.045f
        while (x < w + w * 0.12f) {
            drawLine(
                Color.White.copy(alpha = 0.5f),
                Offset(x, y),
                Offset(x + w * 0.05f, y),
                strokeWidth = waveStroke.width,
                cap = waveStroke.cap,
            )
            x += w * 0.14f
        }
    }

    val sandTop = horizon + waterHeight
    drawRect(color = Color(0xFFF3D29C), topLeft = Offset(0f, sandTop), size = Size(w, h - sandTop))
    // Foam line where the water meets the sand.
    drawOval(
        color = Color(0xFFFBE2B6).copy(alpha = 0.85f),
        topLeft = Offset(-w * 0.1f, sandTop - h * 0.01f),
        size = Size(w * 1.2f, h * 0.018f),
    )
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
        HudText(label, color = Color(0xFFAAB8B5))
    }
}

/** Beat-synced bounce: peaks right after the beat and eases out, ported from the prototype's beatBob. */
private fun beatBob(beat: Double): Float {
    if (beat < 0) return 0f
    val f = beat - floor(beat)
    return (1f - f.toFloat()).pow(3)
}

/**
 * A fully-articulated crab (legs, claws that snap shut on [pulsePhase], eye stalks, optional hat)
 * drawn in local coordinates matching the prototype's drawCrab, then scaled/positioned to fit the
 * stage. This replaces the earlier placeholder body-circle-with-two-lines shape.
 */
private fun DrawScope.drawCrab(
    pos: Offset,
    scale: Float,
    bodyColor: Color,
    darkColor: Color,
    pulsePhase: Float,
    bob: Float,
    hasHat: Boolean,
) {
    withTransform({
        translate(pos.x, pos.y + bob)
        scale(scale, scale, Offset.Zero)
    }) {
        drawOval(
            color = Color.Black.copy(alpha = 0.16f),
            topLeft = Offset(-42f, 36f),
            size = Size(84f, 16f),
        )

        val legStroke = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (side in listOf(-1f, 1f)) {
            for (i in 0 until 3) {
                val leg = Path().apply {
                    moveTo(side * 28f, 6f + i * 6f)
                    lineTo(side * (48f + i * 3f), 12f + i * 8f)
                    lineTo(side * (54f + i * 4f), 30f + i * 6f)
                }
                drawPath(leg, darkColor, style = legStroke)
            }
        }

        for (side in listOf(-1f, 1f)) {
            val ax = side * (58f + pulsePhase * 4f)
            val ay = -30f - pulsePhase * 26f
            val arm = Path().apply {
                moveTo(side * 34f, -6f)
                quadraticTo(side * 56f, -6f, ax, ay + 12f)
            }
            drawPath(arm, darkColor, style = Stroke(width = 7f, cap = StrokeCap.Round))

            val open = 0.6f * (1f - pulsePhase) + 0.05f
            val dir = -PI.toFloat() / 2f + side * 0.45f
            val startAngle = dir + open
            val sweep = 2f * PI.toFloat() - 2f * open
            val clawRadius = 18f
            val claw = Path().apply {
                moveTo(ax, ay)
                lineTo(ax + clawRadius * cos(startAngle), ay + clawRadius * sin(startAngle))
                arcTo(
                    rect = Rect(ax - clawRadius, ay - clawRadius, ax + clawRadius, ay + clawRadius),
                    startAngleDegrees = startAngle * 180f / PI.toFloat(),
                    sweepAngleDegrees = sweep * 180f / PI.toFloat(),
                    forceMoveTo = false,
                )
                close()
            }
            drawPath(claw, bodyColor)
            drawPath(claw, darkColor, style = Stroke(width = 3f))
        }

        for (side in listOf(-1f, 1f)) {
            drawLine(darkColor, Offset(side * 12f, -22f), Offset(side * 16f, -46f), strokeWidth = 5f, cap = StrokeCap.Round)
        }

        drawOval(bodyColor, topLeft = Offset(-46f, -30f), size = Size(92f, 60f))
        drawOval(darkColor, topLeft = Offset(-46f, -30f), size = Size(92f, 60f), style = Stroke(width = 3f))
        drawOval(Color.White.copy(alpha = 0.28f), topLeft = Offset(-29f, -24f), size = Size(30f, 14f))

        for (side in listOf(-1f, 1f)) {
            drawCircle(Color.White, radius = 10f, center = Offset(side * 16f, -50f))
            drawCircle(darkColor, radius = 10f, center = Offset(side * 16f, -50f), style = Stroke(width = 2.5f))
            drawCircle(Color(0xFF1A2927), radius = 4.5f, center = Offset(side * 16f, -49f))
        }

        drawArc(
            color = darkColor,
            startAngle = 36f,
            sweepAngle = 108f,
            useCenter = false,
            topLeft = Offset(-7f, -15f),
            size = Size(14f, 14f),
            style = Stroke(width = 3f, cap = StrokeCap.Round),
        )

        if (hasHat) {
            drawArc(
                color = Color(0xFFF0C565),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(-22f, -81f),
                size = Size(44f, 34f),
            )
            drawRect(Color(0xFF1F7A7A), topLeft = Offset(-22f, -70f), size = Size(44f, 6f))
            drawOval(Color(0xFFF0C565), topLeft = Offset(-40f, -67f), size = Size(80f, 8f))
            drawOval(Color(0xFFC99A3E), topLeft = Offset(-40f, -67f), size = Size(80f, 8f), style = Stroke(width = 2f))
        }
    }
}
