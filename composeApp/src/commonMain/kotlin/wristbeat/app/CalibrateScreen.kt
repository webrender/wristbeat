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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import wristbeat.core.CALIBRATE_COUNT_IN_BEATS
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
 */
@Composable
fun CalibrateScreen(onCalibrated: (offsetMs: Double) -> Unit) {
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
    var tapCount by remember { mutableStateOf(0) }
    var recentErrors by remember { mutableStateOf(listOf<Double>()) }
    var result by remember { mutableStateOf<CalibrateResult?>(null) }
    val runLengthSeconds = remember { (CALIBRATE_TOTAL_BEATS * SECONDS_PER_BEAT).roundToInt() }

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
                if (r.ok) onCalibrated(r.offsetMs)
                finished = true
            }
        }
    }

    fun handleTap() {
        if (!started) {
            audioClock.start()
            t0 = audioClock.now() + 0.3
            scheduledIndex = 0
            lastFiredBeat = -1
            beatPosition = -1.0
            tapCount = 0
            recentErrors = emptyList()
            result = null
            finished = false
            started = true
            return
        }
        if (finished) {
            started = false
            return
        }
        val beat = (audioClock.now() - t0) / SECONDS_PER_BEAT
        val errorMs = stage.recordTap(beat)
        haptics.pulse() // confirms the tap registered; never plays a sound here (see class doc)
        tapCount = stage.tapCount
        if (errorMs != null) recentErrors = (recentErrors + errorMs).takeLast(16)
    }

    // A single full-bleed canvas with the status readout floated on top as a HUD overlay — no
    // header/footer flow layout, so the dial always fills the whole screen edge to edge.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(rememberTapKeyModifier(::handleTap))
            .pointerInput(Unit) { detectTapGestures { handleTap() } },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val squareExtent = minOf(size.width, size.height)
            val stageCenter = Offset(size.width / 2f, size.height / 2f)
            val radius = squareExtent / 2f * 0.85f
            val safeRadius = radius * 0.85f

            drawCalibrateBackground(size.width, size.height, stageCenter, radius * 1.35f)

            drawCircle(color = Color(0xFF0E3A3D), radius = radius, center = stageCenter)
            drawCircle(color = Color.White.copy(alpha = 0.10f), radius = safeRadius, center = stageCenter, style = Stroke(width = 3f))

            // Progress ring: how far through the fixed 36-beat run we are, so "how long is this"
            // has a visible answer instead of just a number.
            if (started) {
                val progress = (beatPosition / CALIBRATE_TOTAL_BEATS).coerceIn(0.0, 1.0).toFloat()
                drawArc(
                    color = Color(0xFF2FBF9E),
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    topLeft = Offset(stageCenter.x - radius + 3f, stageCenter.y - radius + 3f),
                    size = Size((radius - 3f) * 2f, (radius - 3f) * 2f),
                    style = Stroke(width = 5f),
                )
            }

            // Anticipatory sweep: one full revolution per beat, arriving at the target
            // mark (12 o'clock) exactly when the click plays.
            val phase = if (beatPosition < 0) 0.0 else beatPosition - floor(beatPosition)
            val angle = (-90f + phase.toFloat() * 360f) * (PI.toFloat() / 180f)
            val marker = Offset(stageCenter.x + safeRadius * cos(angle), stageCenter.y + safeRadius * sin(angle))
            if (started && !finished) {
                drawLine(Color(0xFFFFB320), stageCenter, marker, strokeWidth = 4f)
                drawCircle(Color(0xFFFFB320), radius = 8f, center = marker)
            }

            // Target mark flashes exactly on the beat, giving a second, reactive confirmation.
            val target = Offset(stageCenter.x, stageCenter.y - safeRadius)
            drawCircle(Color.White, radius = 10f + 16f * flashPhase, center = target, alpha = 0.20f + 0.55f * flashPhase)
            drawCircle(Color.White, radius = 6f, center = target)
        }

        HudChip(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            HudText("Calibrate", color = Color(0xFF2FBF9E))
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                result != null -> {
                    val r = result!!
                    HudChip {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HudText(
                                if (r.ok) "Offset set: ${formatMs(r.offsetMs)}" else "Not enough taps — try again",
                                loud = true,
                            )
                            Spacer(Modifier.height(2.dp))
                            HudText("from ${r.tapCount} taps", color = Color(0xFFAAB8B5))
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
                                "$CALIBRATE_TOTAL_BEATS beats, about ${runLengthSeconds}s — a $CALIBRATE_COUNT_IN_BEATS-beat " +
                                    "count-in, then tap every beat",
                                color = Color(0xFFAAB8B5),
                            )
                        }
                    }
                }
                else -> {
                    val beatIndex = floor(beatPosition).toInt()
                    HudChip {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HudText(if (beatIndex < CALIBRATE_COUNT_IN_BEATS) "Get ready…" else "Tap with the click", loud = true)
                            Spacer(Modifier.height(2.dp))
                            val shownBeat = beatIndex.coerceIn(0, CALIBRATE_TOTAL_BEATS)
                            HudText("Beat $shownBeat of $CALIBRATE_TOTAL_BEATS  ·  $tapCount taps", color = Color(0xFFAAB8B5))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TimingStrip(recentErrors)
                }
            }
        }
    }
}

@Composable
private fun TimingStrip(errors: List<Double>) {
    val width = 200.dp
    Box(modifier = Modifier.width(width).height(20.dp).background(Color.White.copy(alpha = 0.08f))) {
        for (err in errors) {
            val clamped = err.coerceIn(-120.0, 120.0)
            val fraction = ((clamped + 120.0) / 240.0).toFloat()
            Box(
                modifier = Modifier
                    .offset(x = width * fraction - 3.dp)
                    .align(Alignment.CenterStart)
                    .size(6.dp)
                    .background(
                        if (abs(err) <= PERFECT_WINDOW_MS) Color(0xFF1C9A6A) else Color(0xFFC98F00),
                        shape = CircleShape,
                    ),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.width(width)) {
        HudText("early", color = Color(0xFF9FB0AC))
        HudText("late", color = Color(0xFF9FB0AC))
    }
}

/** A dark gradient field with a soft glow behind the dial and faint sonar rings bleeding into the side margins. */
private fun DrawScope.drawCalibrateBackground(w: Float, h: Float, glowCenter: Offset, glowRadius: Float) {
    drawRect(
        brush = Brush.verticalGradient(colors = listOf(Color(0xFF14312F), Color(0xFF081514)), startY = 0f, endY = h),
        size = Size(w, h),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF2FBF9E).copy(alpha = 0.16f), Color.Transparent),
            center = glowCenter,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = glowCenter,
    )
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
