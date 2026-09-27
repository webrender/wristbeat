package wristbeat.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import wristbeat.core.BongoBlitzStage
import wristbeat.core.DrumAction
import wristbeat.core.Grade
import wristbeat.core.Rank
import wristbeat.core.ScoreTally
import wristbeat.core.SoundId

/** A press held longer than this without moving isn't a tap any more (see [MangoChopScreen]'s same constant). */
private const val DEFERRED_TAP_MAX_MS = 300L

private enum class BongoPressResult { CONSUMED, HIT, DEFERRED }

private val HI_NOTE_COLOR = Color(0xFFC77DFF)
private val LO_NOTE_COLOR = Color(0xFFFFC857)

/**
 * Bongo Blitz: the monkey claps a pattern on the high (tap) and low (swipe) drum, then it's the
 * player's turn one bar later — Snap Crabs' call-and-response memory task, but at a faster tempo,
 * with longer, more syncopated eighth-note runs, and Mango Chop's tap/swipe discrimination layered
 * on top. The hardest stage in the game.
 *
 * Touch input follows [MangoChopScreen]'s same rule for telling a tap from the start of a swipe:
 * when the nearest open target wants a tap, the press hits the high drum the moment the finger
 * lands; when it wants a swipe, the press waits to see whether it becomes one, and otherwise taps
 * on release, judged at the moment the finger landed.
 */
@Composable
fun BongoBlitzScreen(
    calibration: Calibration,
    chart: ChartSetting,
    onRunningChanged: (Boolean) -> Unit = {},
    onMenu: (() -> Unit)? = null,
) {
    val audioClock = remember { AudioClock() }
    val audioEngine = remember { AudioEngine() }
    val haptics = remember { HapticEngine() }
    var stage by remember { mutableStateOf(BongoBlitzStage(calibration.inputOffsetMs)) }
    val secondsPerBeat = stage.secondsPerBeat

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var t0 by remember { mutableStateOf(0.0) }
    var scheduledIndex by remember { mutableStateOf(0) }
    var beatPosition by remember { mutableStateOf(-1.0) }
    var lastFiredBeat by remember { mutableStateOf(-1) }
    var beatFlashPhase by remember { mutableStateOf(0f) }
    var nextLeadCueIndex by remember { mutableStateOf(0) }
    var leadHiPulse by remember { mutableStateOf(0f) }
    var leadLoPulse by remember { mutableStateOf(0f) }
    var nextTargetIndex by remember { mutableStateOf(0) }
    var targetPulsePhase by remember { mutableStateOf(0f) }
    var playerHiPulse by remember { mutableStateOf(0f) }
    var playerLoPulse by remember { mutableStateOf(0f) }
    var playerLoSwipeFlash by remember { mutableStateOf(0f) }
    var tally by remember { mutableStateOf(ScoreTally()) }
    ReportRunning(started && !finished, onRunningChanged)
    // Highway: the player's upcoming response beats, dots for the high drum and swipe arrows for the low one.
    val highwayTargets = remember(stage) {
        stage.targets.map {
            if (it.action == DrumAction.TAP) HighwayNote(it.beat, HI_NOTE_COLOR, 8f) else HighwayNote(it.beat, LO_NOTE_COLOR, 9f, NoteShape.SWIPE)
        }
    }

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
                val t = t0 + ev.beat * secondsPerBeat
                if (t > horizon) break
                if (t >= now - 0.01) audioEngine.play(ev.sound, t, ev.param)
                scheduledIndex++
            }

            val rawBeat = (now - t0) / secondsPerBeat
            val beat = stage.perceivedBeat(rawBeat)
            beatPosition = beat

            val beatIndex = floor(beat).toInt()
            if (beatIndex > lastFiredBeat) {
                lastFiredBeat = beatIndex
                beatFlashPhase = 1f
            }
            while (nextLeadCueIndex < stage.leadCues.size && beat >= stage.leadCues[nextLeadCueIndex].beat) {
                if (stage.leadCues[nextLeadCueIndex].action == DrumAction.TAP) leadHiPulse = 1f else leadLoPulse = 1f
                nextLeadCueIndex++
            }
            while (nextTargetIndex < stage.targets.size && beat >= stage.targets[nextTargetIndex].beat) {
                nextTargetIndex++
                targetPulsePhase = 1f
            }
            beatFlashPhase = (beatFlashPhase - dt * 4f).coerceAtLeast(0f)
            leadHiPulse = (leadHiPulse - dt * 5f).coerceAtLeast(0f)
            leadLoPulse = (leadLoPulse - dt * 5f).coerceAtLeast(0f)
            targetPulsePhase = (targetPulsePhase - dt * 5f).coerceAtLeast(0f)
            playerHiPulse = (playerHiPulse - dt * 5f).coerceAtLeast(0f)
            playerLoPulse = (playerLoPulse - dt * 5f).coerceAtLeast(0f)
            // Decays slower than the skin tint so the swipe flash reads clearly on its own.
            playerLoSwipeFlash = (playerLoSwipeFlash - dt * 3f).coerceAtLeast(0f)

            stage.updateMisses(rawBeat)
            tally = stage.tally()

            if (stage.isFinished(beat)) finished = true
        }
    }

    fun beatNow(): Double = (audioClock.now() - t0) / secondsPerBeat

    fun restart() {
        audioClock.start()
        calibration.refreshOutput()
        // Fresh stage per run: judged targets and the tally don't carry over into a replay.
        stage = BongoBlitzStage(calibration.inputOffsetMs)
        t0 = audioClock.now() + 0.3
        scheduledIndex = 0
        beatPosition = -1.0
        lastFiredBeat = -1
        nextLeadCueIndex = 0
        nextTargetIndex = 0
        beatFlashPhase = 0f
        leadHiPulse = 0f
        leadLoPulse = 0f
        targetPulsePhase = 0f
        playerHiPulse = 0f
        playerLoPulse = 0f
        playerLoSwipeFlash = 0f
        tally = ScoreTally()
        finished = false
        started = true
    }

    /** Starts or restarts a run; returns false when a run is in progress and the input is gameplay. */
    fun handleMenuPress(): Boolean {
        if (!started || finished) {
            restart()
            return true
        }
        return false
    }

    fun act(action: DrumAction, beat: Double) {
        val outcome = stage.recordAction(action, beat)
        val now = audioClock.now()
        if (action == DrumAction.TAP) {
            audioEngine.play(SoundId.PLAYER_BONGO_HI, now)
            playerHiPulse = 1f
        } else {
            audioEngine.play(SoundId.PLAYER_BONGO_LO, now)
            playerLoPulse = 1f
            playerLoSwipeFlash = 1f
        }
        when (outcome.grade) {
            Grade.PERFECT -> audioEngine.play(SoundId.PERFECT_DING, now + 0.01)
            Grade.OK -> Unit
            null -> audioEngine.play(SoundId.WHIFF, now + 0.02)
        }
        haptics.pulse()
        tally = stage.tally()
    }

    fun press(beat: Double): BongoPressResult {
        if (handleMenuPress()) return BongoPressResult.CONSUMED
        if (stage.expectedAction(beat) == DrumAction.SWIPE) return BongoPressResult.DEFERRED
        act(DrumAction.TAP, beat)
        return BongoPressResult.HIT
    }

    fun keyTap() {
        if (!handleMenuPress()) act(DrumAction.TAP, beatNow())
    }

    fun keySwipe() {
        if (!handleMenuPress()) act(DrumAction.SWIPE, beatNow())
    }

    // The stage begins as soon as it's opened — no tap needed to start the song.
    LaunchedEffect(Unit) { restart() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(rememberTapKeyModifier(onTap = ::keyTap, onSwipe = ::keySwipe))
            .pointerInput(Unit) {
                val swipeDistance = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val beatAtDown = beatNow()
                    val result = press(beatAtDown)
                    if (result == BongoPressResult.CONSUMED) return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        val delta = change.position - down.position
                        if (delta.getDistance() >= swipeDistance) {
                            act(DrumAction.SWIPE, beatNow())
                            return@awaitEachGesture
                        }
                        if (!change.pressed) {
                            val heldMs = change.uptimeMillis - down.uptimeMillis
                            if (result == BongoPressResult.DEFERRED && heldMs <= DEFERRED_TAP_MAX_MS) {
                                act(DrumAction.TAP, beatAtDown)
                            }
                            return@awaitEachGesture
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squareExtent = minOf(size.width, size.height)
            val squareLeft = (size.width - squareExtent) / 2f
            val squareTop = (size.height - squareExtent) / 2f
            val k = squareExtent / 400f

            drawJungleBackground(size.width, size.height, beatPosition)

            if (chart.on) {
                drawNoteHighway(
                    targets = highwayTargets,
                    beatPosition = beatPosition,
                    // 3.7 beats at BONGO_BLITZ_BPM is about the same 1.29s look-ahead as Snap Crabs'
                    // 2.5 beats at 116 BPM and Mango Chop's 3.0 at 140 BPM, so the scroll speed matches.
                    lookaheadBeats = 3.7,
                    hitFlash = maxOf(beatFlashPhase * 0.5f, targetPulsePhase),
                    laneY = squareTop + 0.30f * squareExtent,
                    laneLeftX = squareLeft + 0.20f * squareExtent,
                    laneRightX = squareLeft + 0.92f * squareExtent,
                )
            }

            withTransform({
                translate(squareLeft, squareTop)
                scale(k, k, Offset.Zero)
            }) {
                val bob = beatBob(beatPosition) * 5f
                drawDrum(DrumKind.HI, Offset(140f, 300f), leadHiPulse, playerHiPulse)
                drawDrum(DrumKind.LO, Offset(262f, 300f), leadLoPulse, playerLoPulse, playerLoSwipeFlash)
                drawMonkey(Offset(200f, 214f), leadHiPulse, leadLoPulse, bob)
            }
        }

        StageResults(
            visible = finished,
            accent = Color(0xFFC77DFF),
            sound = if (tally.rank == Rank.TRY_AGAIN) SoundId.BOO else SoundId.CHEER,
            audioEngine = audioEngine,
            audioClock = audioClock,
            headline = when (tally.rank) {
                Rank.SUPERB -> "Superb!"
                Rank.OK -> "OK"
                Rank.TRY_AGAIN -> "Try again"
            },
            heroValue = (tally.score * 100).roundToInt(),
            heroFormat = { "$it%" },
            stats = listOf(
                StatCounter("Perfect", tally.perfect),
                StatCounter("OK", tally.ok),
                StatCounter("Miss", tally.miss),
            ),
            onRestart = ::restart,
            onMenu = onMenu,
        )
    }
}

/** Beat-synced bounce: peaks right after the beat and eases out (see [SnapCrabsScreen]'s same shape). */
private fun beatBob(beat: Double): Float {
    if (beat < 0) return 0f
    val f = beat - floor(beat)
    return (1f - f.toFloat()).pow(3)
}

/**
 * A moonlit jungle canopy bleeding to fill the whole canvas, with drifting fireflies and a wooden
 * drum-circle floor — night, for a change from Snap Crabs' beach and Mango Chop's market, and
 * darker than either so the bright drum skins and highway notes pop against it.
 */
private fun DrawScope.drawJungleBackground(w: Float, h: Float, beat: Double) {
    val horizon = h * 0.62f
    drawRect(
        brush = Brush.verticalGradient(colors = listOf(Color(0xFF120B29), Color(0xFF2B1F4F)), startY = 0f, endY = horizon),
        size = Size(w, horizon),
    )

    val moonCenter = Offset(w * 0.78f, h * 0.16f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFEDE7D9).copy(alpha = 0.35f), Color(0xFFEDE7D9).copy(alpha = 0f)),
            center = moonCenter,
            radius = h * 0.14f,
        ),
        radius = h * 0.14f,
        center = moonCenter,
    )
    drawCircle(Color(0xFFF4EFE2), radius = h * 0.06f, center = moonCenter)

    // Fireflies drift and twinkle with the beat rather than on a separate clock, so the whole scene
    // stays driven by one timebase like the beach's waves and the market's awning sway.
    val t = beat.coerceAtLeast(0.0)
    for (i in 0 until 16) {
        val seed = i * 0.6180339887
        val x = (((i * 173) % 977) / 977f) * w
        val y = horizon * (0.15f + 0.7f * ((i * 89 % 613) / 613f))
        val twinkle = (sin(t * 2.0 + seed * 20.0) * 0.5 + 0.5).toFloat()
        val drift = (sin(t * 0.6 + seed * 10.0) * 6.0).toFloat()
        drawCircle(Color(0xFFE7FF7A).copy(alpha = 0.2f + 0.55f * twinkle), radius = 5f + 2.5f * twinkle, center = Offset(x + drift, y))
    }

    val canopy = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, horizon * 0.32f)
        quadraticTo(w * 0.15f, horizon * 0.5f, w * 0.3f, horizon * 0.22f)
        quadraticTo(w * 0.48f, horizon * 0.02f, w * 0.62f, horizon * 0.3f)
        quadraticTo(w * 0.8f, horizon * 0.5f, w, horizon * 0.18f)
        lineTo(w, 0f)
        close()
    }
    drawPath(canopy, Color(0xFF0B1F14))

    // Two vines sway with the beat, echoing the market awning's sway.
    val sway = (sin(t * PI) * 10.0).toFloat()
    for (vx in listOf(w * 0.16f, w * 0.87f)) {
        drawLine(Color(0xFF163A22), Offset(vx, 0f), Offset(vx + sway, horizon * 0.34f), strokeWidth = 4f)
        drawCircle(Color(0xFF2E6B3F), radius = 10f, center = Offset(vx + sway, horizon * 0.34f))
    }

    drawRect(Color(0xFF4B2E1D), topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
    drawRect(Color(0xFF3A2314), topLeft = Offset(0f, horizon), size = Size(w, 8f))
    var plank = horizon + 40f
    while (plank < h) {
        drawLine(Color.Black.copy(alpha = 0.18f), Offset(0f, plank), Offset(w, plank), strokeWidth = 2f)
        plank += 40f
    }
}

private enum class DrumKind { HI, LO }

/**
 * One drum: a rounded wooden barrel with two hoops and a tensioned skin on top, shaded and lit from
 * the upper left so it reads as a solid object rather than a flat trapezoid. [leadPulse] glows the
 * skin gold for the monkey's call; [playerPulse] tints it toward the stage accent for the player's
 * own hit, so it's clear at a glance which side made it light up. [swipeFlash] (the low drum only)
 * draws a claw-swipe streak across the skin, since a swipe otherwise has no on-screen feedback of
 * its own beyond the same tint a tap gets.
 */
private fun DrawScope.drawDrum(kind: DrumKind, pos: Offset, leadPulse: Float, playerPulse: Float, swipeFlash: Float = 0f) {
    val skinR = if (kind == DrumKind.HI) 34f else 46f
    val bodyH = if (kind == DrumKind.HI) 74f else 54f
    val bodyLit = if (kind == DrumKind.HI) Color(0xFFA9764C) else Color(0xFF8A5A34)
    val bodyShadowed = if (kind == DrumKind.HI) Color(0xFF6B4023) else Color(0xFF4E2E18)
    val skinRectTopLeft = Offset(-skinR, -skinR * 0.62f)
    val skinRectSize = Size(skinR * 2f, skinR * 1.24f)

    withTransform({ translate(pos.x, pos.y) }) {
        // A soft contact shadow reads better than a hard-edged ellipse under a rounded body.
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0f)),
                center = Offset(0f, bodyH - 2f),
                radius = skinR * 1.05f,
            ),
            topLeft = Offset(-skinR * 1.1f, bodyH - 12f),
            size = Size(skinR * 2.2f, 24f),
        )

        val body = Path().apply {
            moveTo(-skinR, 0f)
            lineTo(-skinR * 0.7f, bodyH)
            lineTo(skinR * 0.7f, bodyH)
            lineTo(skinR, 0f)
            close()
        }
        // Lit-from-the-left gradient in place of a flat fill gives the barrel some roundness.
        drawPath(
            body,
            brush = Brush.horizontalGradient(colors = listOf(bodyShadowed, bodyLit, bodyShadowed), startX = -skinR, endX = skinR),
        )
        drawPath(body, Color.Black.copy(alpha = 0.35f), style = Stroke(width = 2f))
        // Two rope hoops wrapping the barrel, each a dark band with a thin highlight above it.
        for (i in 1..2) {
            val fy = bodyH * i / 3f
            val fx = skinR * (1f - 0.28f * (i / 3f))
            drawLine(Color(0xFF2E1B0E).copy(alpha = 0.55f), Offset(-fx, fy), Offset(fx, fy), strokeWidth = 4f)
            drawLine(Color(0xFFE9C79A).copy(alpha = 0.3f), Offset(-fx, fy - 2.5f), Offset(fx, fy - 2.5f), strokeWidth = 1.5f)
        }

        val glowR = skinR * (1f + 0.45f * leadPulse)
        if (leadPulse > 0f) {
            drawOval(
                Color(0xFFFFC857).copy(alpha = 0.55f * leadPulse),
                topLeft = Offset(-glowR, -glowR * 0.62f),
                size = Size(glowR * 2f, glowR * 1.24f),
            )
        }

        // The skin: a radial-shaded head (bright upper-left, darkening toward the rim) instead of a
        // flat oval, with a tuning hoop and a ring of lug marks standing in for the laced tension lines.
        val skinBase = lerp(Color(0xFFEFD3AA), Color(0xFFC77DFF), playerPulse)
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(lerp(Color.White, skinBase, 0.4f), skinBase, lerp(skinBase, Color(0xFF8A5A34), 0.45f)),
                center = Offset(-skinR * 0.3f, -skinR * 0.62f - skinR * 0.15f),
                radius = skinR * 1.5f,
            ),
            topLeft = skinRectTopLeft,
            size = skinRectSize,
        )
        drawOval(Color(0xFF4E2E18), topLeft = skinRectTopLeft, size = skinRectSize, style = Stroke(width = 4f))
        for (i in 0 until 8) {
            val angle = i * PI.toFloat() / 4f
            val ex = cos(angle) * skinR * 0.98f
            val ey = sin(angle) * skinR * 0.62f * 0.98f
            drawCircle(Color(0xFF3A2314), radius = 2.2f, center = Offset(ex, ey))
        }
        drawOval(
            Color.White.copy(alpha = 0.3f),
            topLeft = Offset(-skinR * 0.55f, -skinR * 0.62f - skinR * 0.1f),
            size = Size(skinR * 0.6f, skinR * 0.32f),
        )

        if (kind == DrumKind.LO && swipeFlash > 0f) drawSwipeHit(skinR, swipeFlash)
    }
}

/**
 * A quick claw-swipe flash across the low drum's skin: three fading speed-lines on the diagonal of
 * the swipe gesture itself, plus a soft expanding ring, so a successful swipe reads clearly on
 * screen the instant it lands rather than only via the shared skin tint every hit gets.
 */
private fun DrawScope.drawSwipeHit(skinR: Float, flash: Float) {
    val center = Offset(0f, -skinR * 0.2f)
    val angle = -32f * PI.toFloat() / 180f
    val dir = Offset(cos(angle), sin(angle))
    val perp = Offset(-dir.y, dir.x)
    val length = skinR * 2.1f
    for ((i, width) in listOf(3.5f, 5.5f, 3.5f).withIndex()) {
        val lineOffset = perp * ((i - 1) * skinR * 0.4f)
        val start = center + lineOffset - dir * (length / 2f)
        val end = center + lineOffset + dir * (length / 2f)
        drawLine(Color.White.copy(alpha = 0.8f * flash), start, end, strokeWidth = width * flash, cap = StrokeCap.Round)
    }
    drawCircle(
        LO_NOTE_COLOR.copy(alpha = 0.45f * flash),
        radius = skinR * (0.85f + 0.5f * (1f - flash)),
        center = center,
        style = Stroke(width = 1f + 3f * flash),
    )
}

/**
 * The lead monkey: a round-eared drummer whose arms swing down onto whichever drum the monkey just
 * called, on [armHiPulse] (left, high drum) or [armLoPulse] (right, low drum).
 */
private fun DrawScope.drawMonkey(pos: Offset, armHiPulse: Float, armLoPulse: Float, bob: Float) {
    val fur = Color(0xFF6B4423)
    val furDark = Color(0xFF4A2F18)
    val skin = Color(0xFFE0AE76)

    withTransform({ translate(pos.x, pos.y + bob) }) {
        val tail = Path().apply {
            moveTo(32f, 34f)
            quadraticTo(74f, 12f, 62f, -32f)
        }
        drawPath(tail, fur, style = Stroke(width = 10f, cap = StrokeCap.Round))

        drawOval(fur, topLeft = Offset(-30f, -8f), size = Size(60f, 58f))

        for (side in listOf(-1f, 1f)) {
            val pulse = if (side < 0f) armHiPulse else armLoPulse
            val swing = pulse * 58f
            val handX = side * (48f + pulse * 12f)
            val handY = 18f + swing
            drawLine(fur, Offset(side * 24f, -2f), Offset(handX, handY), strokeWidth = 11f, cap = StrokeCap.Round)
            drawCircle(skin, radius = 9f, center = Offset(handX, handY))
        }

        for (side in listOf(-1f, 1f)) drawCircle(fur, radius = 16f, center = Offset(side * 38f, -46f))
        for (side in listOf(-1f, 1f)) drawCircle(skin, radius = 9f, center = Offset(side * 38f, -46f))

        drawOval(fur, topLeft = Offset(-34f, -60f), size = Size(68f, 56f))
        drawOval(skin, topLeft = Offset(-20f, -32f), size = Size(40f, 28f))
        drawCircle(furDark, radius = 3f, center = Offset(-8f, -20f))
        drawCircle(furDark, radius = 3f, center = Offset(8f, -20f))

        for (side in listOf(-1f, 1f)) {
            drawCircle(Color.White, radius = 7f, center = Offset(side * 14f, -44f))
            drawCircle(Color(0xFF1A1206), radius = 3.5f, center = Offset(side * 14f, -44f))
        }
    }
}
