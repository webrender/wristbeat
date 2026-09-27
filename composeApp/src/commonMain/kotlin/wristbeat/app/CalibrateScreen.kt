package wristbeat.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import wristbeat.core.CALIBRATE_TOTAL_BEATS
import wristbeat.core.CalibrateResult
import wristbeat.core.CalibrateStage
import wristbeat.core.PERFECT_WINDOW_MS
import wristbeat.core.SECONDS_PER_BEAT

/**
 * Calibrate is the app's opening stage (per Jeremy's feedback: it should run first, not Snap Crabs).
 * Two other fixes live here too: no sound plays for the player's own tap (it was masking the click
 * track and making calibration harder to judge), and the beat indicator sweeps continuously across
 * each beat so the player can anticipate a click instead of only reacting to a flash after it lands.
 *
 * The dial runs on the raw audio clock, so on a laggy output (Bluetooth) it leads the click; the
 * copy asks the player to tap to the sound, since that lag is exactly what the offset measures.
 * The result is saved for the current audio output (see [Calibration]).
 */
@Composable
fun CalibrateScreen(calibration: Calibration, onRunningChanged: (Boolean) -> Unit = {}, onMenu: (() -> Unit)? = null) {
    val audioClock = remember { AudioClock() }
    val audioEngine = remember { AudioEngine() }
    val haptics = remember { HapticEngine() }
    val stage = remember { CalibrateStage() }

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var t0 by remember { mutableStateOf(0.0) }
    var scheduledIndex by remember { mutableStateOf(0) }
    var beatPosition by remember { mutableStateOf(-1.0) }
    var lastFiredBeat by remember { mutableStateOf(-1) }
    var flashPhase by remember { mutableStateOf(0f) }
    var recentErrors by remember { mutableStateOf(listOf<Double>()) }
    var result by remember { mutableStateOf<CalibrateResult?>(null) }
    ReportRunning(started && !finished, onRunningChanged)

    // Look-ahead scheduler + per-frame beat position, mirroring the prototype's 25ms-interval
    // scheduler but driven by the frame clock instead of setInterval (which browsers throttle
    // in background tabs).
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
                if (t >= now - 0.01) audioEngine.play(ev.sound, t)
                scheduledIndex++
            }

            val beat = (now - t0) / SECONDS_PER_BEAT
            beatPosition = beat
            val beatIndex = floor(beat).toInt()
            if (beatIndex > lastFiredBeat && beatIndex in 0 until CALIBRATE_TOTAL_BEATS) {
                lastFiredBeat = beatIndex
                flashPhase = 1f
            }
            flashPhase = (flashPhase - dt * 4f).coerceAtLeast(0f)

            if (beat >= CALIBRATE_TOTAL_BEATS && result == null) {
                val r = stage.finish()
                result = r
                if (r.ok) calibration.record(r.offsetMs)
                finished = true
            }
        }
    }

    fun restart() {
        audioClock.start()
        t0 = audioClock.now() + 0.3
        scheduledIndex = 0
        lastFiredBeat = -1
        beatPosition = -1.0
        recentErrors = emptyList()
        result = null
        finished = false
        started = true
    }

    fun handleTap() {
        if (!started || finished) {
            restart()
            return
        }
        val beat = (audioClock.now() - t0) / SECONDS_PER_BEAT
        val errorMs = stage.recordTap(beat)
        haptics.pulse() // confirms the tap registered; never plays a sound here (see class doc)
        if (errorMs != null) recentErrors = (recentErrors + errorMs).takeLast(16)
    }

    // The stage begins as soon as it's opened — no tap needed to start the click track.
    LaunchedEffect(Unit) { restart() }

    // A single full-bleed canvas with the status readout floated on top as a HUD overlay — no
    // header/footer flow layout, so the dial always fills the whole screen edge to edge.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(rememberTapKeyModifier(::handleTap))
            // onPress, not onTap: judge when the finger lands, not when it lifts, so the offset
            // Calibrate measures doesn't include how long each tap is held.
            .pointerInput(Unit) { detectTapGestures(onPress = { handleTap() }) },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squareExtent = minOf(size.width, size.height)
            val stageCenter = Offset(size.width / 2f, size.height / 2f)
            val radius = squareExtent / 2f * 0.85f
            val safeRadius = radius * 0.85f

            val u = radius / 200f

            drawCalibrateBackground(size.width, size.height, stageCenter, radius * 1.35f, flashPhase)

            // Bezel: an inked, shaded rim around the dial face.
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = radius + 6f * u, center = stageCenter + Offset(0f, 8f * u))
            drawCircle(
                brush = Brush.verticalGradient(listOf(Color(0xFF3A6E68), Color(0xFF123331)), startY = stageCenter.y - radius, endY = stageCenter.y + radius),
                radius = radius,
                center = stageCenter,
            )
            drawCircle(CAL_INK, radius = radius, center = stageCenter, style = Stroke(width = 4f * u))
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF184A48), Color(0xFF0C2826)),
                    center = stageCenter - Offset(0f, radius * 0.3f),
                    radius = radius,
                ),
                radius = radius * 0.9f,
                center = stageCenter,
            )
            drawCircle(CAL_INK, radius = radius * 0.9f, center = stageCenter, style = Stroke(width = 3f * u))

            // Progress ring: how far through the fixed-length run we are, so "how long is this"
            // has a visible answer instead of just a number.
            val ringRadius = radius * 0.95f
            val ringTopLeft = Offset(stageCenter.x - ringRadius, stageCenter.y - ringRadius)
            val ringSize = Size(ringRadius * 2f, ringRadius * 2f)
            drawCircle(Color.Black.copy(alpha = 0.3f), radius = ringRadius, center = stageCenter, style = Stroke(width = 7f * u))
            if (started) {
                val progress = (beatPosition / CALIBRATE_TOTAL_BEATS).coerceIn(0.0, 1.0).toFloat()
                if (progress > 0f) {
                    drawArc(Color(0xFF2FBF9E).copy(alpha = 0.35f), -90f, progress * 360f, false, ringTopLeft, ringSize, style = Stroke(width = 13f * u, cap = StrokeCap.Round))
                    drawArc(Color(0xFF7FF0CF), -90f, progress * 360f, false, ringTopLeft, ringSize, style = Stroke(width = 6f * u, cap = StrokeCap.Round))
                }
            }

            // Ticks for the quarter-beats around the face, like a stopwatch.
            for (i in 0 until 16) {
                val a = (-90f + i * 22.5f) * PI.toFloat() / 180f
                val d = Offset(cos(a), sin(a))
                val major = i % 4 == 0
                drawLine(
                    Color.White.copy(alpha = if (major) 0.35f else 0.14f),
                    stageCenter + d * (radius * (if (major) 0.74f else 0.79f)),
                    stageCenter + d * (radius * 0.84f),
                    strokeWidth = (if (major) 4f else 2.5f) * u,
                    cap = StrokeCap.Round,
                )
            }

            // Anticipatory sweep: one full revolution per beat, arriving at the target
            // mark (12 o'clock) exactly when the click plays, with a fading trail behind it.
            val phase = if (beatPosition < 0) 0.0 else beatPosition - floor(beatPosition)
            val sweepDeg = -90f + phase.toFloat() * 360f
            val angle = sweepDeg * (PI.toFloat() / 180f)
            val marker = Offset(stageCenter.x + safeRadius * cos(angle), stageCenter.y + safeRadius * sin(angle))
            if (started && !finished) {
                // A sweep gradient always starts at 3 o'clock, so the trail is drawn from 0° and
                // rotated into place behind the hand, fading in from its tail.
                val trail = 110f
                withTransform({ rotate(sweepDeg - trail, stageCenter) }) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color(0xFFFFB320).copy(alpha = 0f),
                            trail / 360f to Color(0xFFFFB320).copy(alpha = 0.32f),
                            1f to Color(0xFFFFB320).copy(alpha = 0f),
                            center = stageCenter,
                        ),
                        startAngle = 0f,
                        sweepAngle = trail,
                        useCenter = true,
                        topLeft = Offset(stageCenter.x - safeRadius, stageCenter.y - safeRadius),
                        size = Size(safeRadius * 2f, safeRadius * 2f),
                    )
                }
                val dir = Offset(cos(angle), sin(angle))
                val n = Offset(-dir.y, dir.x)
                val hand = Path().apply {
                    moveTo(stageCenter.x + n.x * 7f * u, stageCenter.y + n.y * 7f * u)
                    lineTo(marker.x, marker.y)
                    lineTo(stageCenter.x - n.x * 7f * u, stageCenter.y - n.y * 7f * u)
                    close()
                }
                drawPath(hand, CAL_INK, style = Stroke(width = 5f * u, join = StrokeJoin.Round))
                drawPath(hand, Color(0xFFFFB320))
                drawCircle(CAL_INK, radius = 11f * u, center = marker)
                drawCircle(Color(0xFFFFD36A), radius = 8.5f * u, center = marker)
            }
            drawCircle(CAL_INK, radius = 16f * u, center = stageCenter)
            drawCircle(brush = Brush.radialGradient(listOf(Color(0xFFFFE8A8), Color(0xFFE0A020)), center = stageCenter - Offset(4f * u, 4f * u), radius = 16f * u), radius = 12.5f * u, center = stageCenter)

            // Target mark: a gem at 12 o'clock that flares exactly on the beat, giving a second,
            // reactive confirmation.
            val target = Offset(stageCenter.x, stageCenter.y - safeRadius)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.2f + 0.6f * flashPhase), Color.White.copy(alpha = 0f)),
                    center = target,
                    radius = (26f + 30f * flashPhase) * u,
                ),
                radius = (26f + 30f * flashPhase) * u,
                center = target,
            )
            val gemR = (13f + 5f * flashPhase) * u
            val gem = Path().apply {
                moveTo(target.x, target.y - gemR)
                lineTo(target.x + gemR, target.y)
                lineTo(target.x, target.y + gemR)
                lineTo(target.x - gemR, target.y)
                close()
            }
            drawPath(gem, lerp(Color(0xFFBDF7E6), Color.White, flashPhase))
            drawPath(gem, CAL_INK, style = Stroke(width = 3f * u, join = StrokeJoin.Round))
            drawLine(Color.White, Offset(target.x - gemR * 0.4f, target.y - gemR * 0.15f), Offset(target.x - gemR * 0.1f, target.y - gemR * 0.5f), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
        }

        // The timing strip is live feedback on the run itself (like the other stages' note
        // highway), not instructional chrome, so it stays up while a run is in progress.
        if (started && !finished) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = statusBottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimingStrip(recentErrors)
            }
        }

        val r = result
        StageResults(
            visible = r != null,
            accent = Color(0xFF2FBF9E),
            sound = null, // Calibrate isn't scored pass/fail, so no cheer/boo here.
            audioEngine = audioEngine,
            audioClock = audioClock,
            headline = if (r?.ok == true) "Offset set" else "Not enough taps",
            heroValue = r?.let { abs(it.offsetMs).roundToInt() } ?: 0,
            heroFormat = { n -> if (r?.ok == true) "${if (r.offsetMs >= 0) "+" else "−"}${n}ms" else "" },
            stats = if (r?.ok == true) listOf(StatCounter("Taps", r.tapCount)) else emptyList(),
            onRestart = ::restart,
            onMenu = onMenu,
        )
    }
}

private val CAL_INK = Color(0xFF051412)

/**
 * The last taps' timing on an inked track: a shaded "perfect" zone in the middle, a centre line,
 * and one dot per tap (green inside the perfect window, amber outside), the newest drawn biggest.
 */
@Composable
private fun TimingStrip(errors: List<Double>) {
    val width = if (LocalHudLayout.current.watch) 110.dp else 220.dp
    Canvas(modifier = Modifier.width(width).height(if (LocalHudLayout.current.watch) 16.dp else 24.dp)) {
        val h = size.height
        val corner = CornerRadius(h * 0.35f)
        drawRoundRect(Color(0xFF0C2826).copy(alpha = 0.9f), cornerRadius = corner)
        val zone = (PERFECT_WINDOW_MS / 120.0).toFloat() * size.width / 2f
        drawRect(Color(0xFF2FBF9E).copy(alpha = 0.22f), topLeft = Offset(size.width / 2f - zone, 0f), size = Size(zone * 2f, h))
        drawLine(Color.White.copy(alpha = 0.5f), Offset(size.width / 2f, h * 0.15f), Offset(size.width / 2f, h * 0.85f), strokeWidth = 2f)
        drawRoundRect(CAL_INK, cornerRadius = corner, style = Stroke(width = 2.5f))
        for ((i, err) in errors.withIndex()) {
            val clamped = err.coerceIn(-120.0, 120.0)
            val x = ((clamped + 120.0) / 240.0).toFloat() * size.width
            val newest = i == errors.lastIndex
            val r = h * (if (newest) 0.3f else 0.2f)
            val color = if (abs(err) <= PERFECT_WINDOW_MS) Color(0xFF3FE0A0) else Color(0xFFFFB320)
            drawCircle(CAL_INK, radius = r + 1.5f, center = Offset(x, h / 2f))
            drawCircle(color.copy(alpha = 0.4f + 0.6f * (i + 1f) / errors.size), radius = r, center = Offset(x, h / 2f))
        }
    }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.width(width)) {
        HudText("early", color = Color(0xFF9FB0AC))
        HudText("late", color = Color(0xFF9FB0AC))
    }
}

/**
 * A dark gradient field with a soft glow behind the dial, a faint dot grid, and a sonar ring that
 * ripples out from the dial on every click ([flash]), bleeding into the side margins.
 */
private fun DrawScope.drawCalibrateBackground(w: Float, h: Float, glowCenter: Offset, glowRadius: Float, flash: Float) {
    drawRect(
        brush = Brush.verticalGradient(colors = listOf(Color(0xFF17403C), Color(0xFF081514)), startY = 0f, endY = h),
        size = Size(w, h),
    )
    val step = glowRadius * 0.16f
    var y = (h / 2f) % step
    while (y < h) {
        var x = (w / 2f) % step
        while (x < w) {
            drawCircle(Color.White.copy(alpha = 0.05f), radius = step * 0.06f, center = Offset(x, y))
            x += step
        }
        y += step
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF2FBF9E).copy(alpha = 0.2f + 0.1f * flash), Color.Transparent),
            center = glowCenter,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = glowCenter,
    )
    if (flash > 0f) {
        drawCircle(
            color = Color(0xFF7FF0CF).copy(alpha = 0.3f * flash),
            radius = glowRadius * (0.75f + 0.45f * (1f - flash)),
            center = glowCenter,
            style = Stroke(width = 3f + 5f * flash),
        )
    }
    for (i in 1..3) {
        drawCircle(
            color = Color.White.copy(alpha = 0.035f),
            radius = glowRadius * (1f + i * 0.4f),
            center = glowCenter,
            style = Stroke(width = 1.5f),
        )
    }
}

internal fun formatMs(v: Double): String {
    val sign = if (v >= 0) "+" else "−"
    return "$sign${round(abs(v)).toInt()}ms"
}

/** The main menu's Calibrate emblem: a little dial whose hand sweeps round once per beat of [beat]. */
internal fun DrawScope.drawCalibrateEmblem(beat: Double) {
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = size.minDimension * 0.42f
    drawCircle(CAL_INK, radius = r, center = c)
    drawCircle(Color(0xFF184A48), radius = r * 0.86f, center = c)
    val a = ((-90.0 + (beat - floor(beat)) * 360.0) * PI / 180.0).toFloat()
    val tip = c + Offset(cos(a), sin(a)) * (r * 0.7f)
    drawLine(CAL_INK, c, tip, strokeWidth = r * 0.2f, cap = StrokeCap.Round)
    drawLine(Color(0xFFFFB320), c, tip, strokeWidth = r * 0.1f, cap = StrokeCap.Round)
    val flash = (1.0 - (beat - floor(beat)) * 4.0).coerceAtLeast(0.0).toFloat()
    val g = r * (0.2f + 0.08f * flash)
    val top = c - Offset(0f, r * 0.7f)
    val gem = Path().apply {
        moveTo(top.x, top.y - g); lineTo(top.x + g, top.y); lineTo(top.x, top.y + g); lineTo(top.x - g, top.y); close()
    }
    drawPath(gem, lerp(Color(0xFFBDF7E6), Color.White, flash))
    drawPath(gem, CAL_INK, style = Stroke(width = r * 0.06f, join = StrokeJoin.Round))
    drawCircle(Color(0xFFFFD36A), radius = r * 0.12f, center = c)
}
