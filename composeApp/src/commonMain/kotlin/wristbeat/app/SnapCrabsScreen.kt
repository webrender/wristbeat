package wristbeat.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import wristbeat.core.Grade
import wristbeat.core.Rank
import wristbeat.core.SECONDS_PER_BEAT
import wristbeat.core.ScoreTally
import wristbeat.core.SnapCrabsStage
import wristbeat.core.SoundId

/**
 * Snap Crabs: the lead crab snaps a pattern, then it's the player's turn one bar later.
 * A note highway (like a rhythm game's approaching notes) shows the player's upcoming response
 * beats sliding toward a fixed hit line, so exactly when a tap is expected is visible ahead
 * of time, not just reacted to after the fact.
 */
@Composable
fun SnapCrabsScreen(
    calibration: Calibration,
    chart: ChartSetting,
    onRunningChanged: (Boolean) -> Unit = {},
    onMenu: (() -> Unit)? = null,
) {
    val audioClock = remember { AudioClock() }
    val audioEngine = remember { AudioEngine() }
    val haptics = remember { HapticEngine() }
    var stage by remember { mutableStateOf(SnapCrabsStage(calibration.inputOffsetMs)) }

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
    ReportRunning(started && !finished, onRunningChanged)
    val highwayTargets = remember(stage) { stage.targets.map { HighwayNote(it, Color(0xFF6FB6FF), 8f) } }

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

            val rawBeat = (now - t0) / SECONDS_PER_BEAT
            // Everything drawn follows the beat the player hears, which lags the raw audio clock by
            // the calibrated offset on a laggy output like a Bluetooth headset.
            val beat = stage.perceivedBeat(rawBeat)
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

            stage.updateMisses(rawBeat)
            tally = stage.tally()

            if (stage.isFinished(beat)) finished = true
        }
    }

    fun restart() {
        audioClock.start()
        calibration.refreshOutput()
        // Fresh stage per run: judged targets and the tally don't carry over into a replay.
        stage = SnapCrabsStage(calibration.inputOffsetMs)
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
    }

    fun handleTap() {
        if (!started || finished) {
            restart()
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

    // The stage begins as soon as it's opened — no tap needed to start the song.
    LaunchedEffect(Unit) { restart() }

    // A single full-bleed canvas with the results screen floated on top once a run finishes — no
    // header/footer flow layout, so the beach and crabs always fill the whole screen edge to edge.
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
            val squareLeft = (size.width - squareExtent) / 2f
            val squareTop = (size.height - squareExtent) / 2f
            val k = squareExtent / 400f

            drawBeachBackground(squareLeft, squareTop, squareExtent, beatPosition)

            if (chart.on) {
                drawNoteHighway(
                    targets = highwayTargets,
                    beatPosition = beatPosition,
                    lookaheadBeats = 2.5,
                    hitFlash = maxOf(beatFlashPhase * 0.5f, targetPulsePhase),
                    laneY = squareTop + 0.30f * squareExtent,
                    laneLeftX = squareLeft + 0.20f * squareExtent,
                    laneRightX = squareLeft + 0.92f * squareExtent,
                )
            }

            // The crabs are authored in the stage's 400×400 logical space, like the other stages.
            withTransform({
                translate(squareLeft, squareTop)
                scale(k, k, Offset.Zero)
            }) {
                val bob = beatBob(beatPosition) * 7f
                // They face each other: the lead crab calls from the left, the player answers on the right.
                drawCrab(Offset(112f, 268f), 1.13f, LEAD_CRAB, leadPulsePhase, bob, gaze = 1f)
                drawCrab(Offset(288f, 268f), 1.13f, PLAYER_CRAB, playerPulsePhase, bob, gaze = -1f)
            }
        }

        StageResults(
            visible = finished,
            accent = Color(0xFF2FBF9E),
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
            stats = gradeStats(tally.perfect, tally.ok, tally.miss),
            passed = tally.rank != Rank.TRY_AGAIN,
            onRestart = ::restart,
            onMenu = onMenu,
        )
    }
}

/** Where the sea meets the sky, and where the surf reaches the sand, in the stage's 400×400 logical space. */
private const val HORIZON_Y = 196f
private const val SHORE_Y = 236f

private fun hash01(i: Int, mul: Int, mod: Int): Float = (((i * mul + 11) % mod + mod) % mod) / mod.toFloat()

/**
 * A sunset beach: a striped setting sun on the horizon, drifting clouds and gulls, a sea with a
 * glittering sun path and scrolling swell, surf that washes up the sand on the beat, and a palm and
 * a beach umbrella framing the margins of wide screens. Laid out from the stage square (like
 * Bongo Blitz's jungle) so the shoreline always sits behind the crabs, while sky, sea and sand
 * bleed to the full canvas. Everything moves on the beat, so the scene has one timebase.
 */
private fun DrawScope.drawBeachBackground(squareLeft: Float, squareTop: Float, extent: Float, beat: Double) {
    val w = size.width
    val h = size.height
    val k = extent / 400f
    fun lx(x: Float) = squareLeft + x * k
    fun ly(y: Float) = squareTop + y * k
    val horizon = ly(HORIZON_Y)
    val t = beat.coerceAtLeast(0.0)
    val pulse = beatBob(beat)

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF6A3E8C), Color(0xFFE8607A), Color(0xFFFF9A6A), Color(0xFFFFD08A)),
            startY = 0f,
            endY = horizon,
        ),
        size = Size(w, horizon),
    )

    // The setting sun, half sunk into the sea, with the retro stripes cut across its lower half.
    val sun = Offset(lx(310f), horizon)
    val sunR = 50f * k * (1f + 0.02f * pulse)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFE9A8).copy(alpha = 0.55f), Color(0xFFFFB36B).copy(alpha = 0f)),
            center = sun,
            radius = sunR * 2.6f,
        ),
        radius = sunR * 2.6f,
        center = sun,
    )
    drawCircle(
        brush = Brush.verticalGradient(listOf(Color(0xFFFFF4C2), Color(0xFFFFC857), Color(0xFFFF8A4C)), startY = sun.y - sunR, endY = sun.y),
        radius = sunR,
        center = sun,
    )
    for (i in 0 until 4) {
        val y = sun.y - sunR * (0.08f + i * 0.13f)
        val bandH = sunR * (0.075f - i * 0.014f)
        drawRect(Color(0xFFFF9A6A), topLeft = Offset(sun.x - sunR, y - bandH), size = Size(sunR * 2f, bandH))
    }

    drawCloud(Offset(lx(-20f + ((t * 2.2) % 480).toFloat()), ly(58f)), 1.1f * k)
    drawCloud(Offset(lx(420f - ((t * 1.4) % 520).toFloat()), ly(96f)), 0.75f * k)
    drawCloud(Offset(lx(150f + ((t * 1.8) % 480).toFloat()) - 240f * k, ly(30f)), 0.6f * k)

    // Gulls glide across, wings flapping once per beat.
    for (i in 0 until 3) {
        val x = lx(-60f + ((t * (5.5 + i) + i * 170) % 560).toFloat())
        val y = ly(70f + i * 22f) + sin(t * 0.9 + i).toFloat() * 6f * k
        val flap = sin((t + i * 0.3) * 2 * PI).toFloat()
        val span = (9f - i * 1.5f) * k
        val gull = Path().apply {
            moveTo(x - span, y - flap * span * 0.4f)
            quadraticTo(x - span * 0.45f, y - span * 0.55f, x, y)
            quadraticTo(x + span * 0.45f, y - span * 0.55f, x + span, y - flap * span * 0.4f)
        }
        drawPath(gull, Color(0xFF4A2248).copy(alpha = 0.75f), style = Stroke(width = 2.2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // A far-off island on the horizon, with a lone palm.
    val island = Path().apply {
        moveTo(lx(20f), horizon + 1f)
        quadraticTo(lx(62f), horizon - 16f * k, lx(104f), horizon + 1f)
        close()
    }
    drawPath(island, Color(0xFF7A3D6E))
    drawLine(Color(0xFF7A3D6E), Offset(lx(66f), horizon - 12f * k), Offset(lx(70f), horizon - 34f * k), strokeWidth = 2.4f * k, cap = StrokeCap.Round)
    for (deg in listOf(-160f, -120f, -60f, -20f)) {
        val a = deg * PI.toFloat() / 180f
        drawLine(
            Color(0xFF7A3D6E),
            Offset(lx(70f), horizon - 34f * k),
            Offset(lx(70f) + cos(a) * 12f * k, horizon - 34f * k + sin(a) * 12f * k + 5f * k),
            strokeWidth = 2.4f * k,
            cap = StrokeCap.Round,
        )
    }

    // The sea, deepening toward the shore, with the sun's glittering path and scrolling swell.
    val shore = ly(SHORE_Y)
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF4FB9C4), Color(0xFF2A93A8), Color(0xFF237F97)), startY = horizon, endY = shore + 12f * k),
        topLeft = Offset(0f, horizon),
        size = Size(w, shore + 12f * k - horizon),
    )
    drawLine(Color(0xFFFFE3B0).copy(alpha = 0.7f), Offset(0f, horizon + 1f), Offset(w, horizon + 1f), strokeWidth = 2f * k)
    for (row in 0 until 6) {
        val y = horizon + (5f + row * 6f) * k
        val halfW = (sunR * (0.9f - row * 0.1f)).coerceAtLeast(6f * k)
        val shimmer = (sin(t * PI + row * 1.7) * 0.5 + 0.5).toFloat()
        drawLine(
            Color(0xFFFFF1C2).copy(alpha = 0.35f + 0.45f * shimmer),
            Offset(sun.x - halfW * (0.5f + 0.5f * shimmer), y),
            Offset(sun.x + halfW * (0.5f + 0.5f * shimmer), y),
            strokeWidth = 2.2f * k,
            cap = StrokeCap.Round,
        )
    }
    val shift = ((t % 2.0) / 2.0 * 36.0).toFloat() * k
    for (row in 0 until 3) {
        val y = horizon + (12f + row * 10f) * k
        var x = -60f * k + shift * (1f + row * 0.4f) + row * 17f * k
        while (x < w + 60f * k) {
            val swell = Path().apply {
                moveTo(x, y)
                quadraticTo(x + 8f * k, y - 3f * k, x + 16f * k, y)
            }
            drawPath(swell, Color.White.copy(alpha = 0.32f + 0.1f * row), style = Stroke(width = 1.8f * k, cap = StrokeCap.Round))
            x += (54f - row * 8f) * k
        }
    }

    // Sand, then the surf washing in and out over it twice a bar, leaving a darker wet band.
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFF7DCA6), Color(0xFFEBC284), Color(0xFFDDAE6E)), startY = shore, endY = h),
        topLeft = Offset(0f, shore),
        size = Size(w, h - shore),
    )
    val wash = (sin(t * PI / 2) * 0.5 + 0.5).toFloat()
    val surfEdge = shore + (4f + 9f * wash) * k
    drawRect(Color(0xFFD9B176).copy(alpha = 0.7f), topLeft = Offset(0f, shore), size = Size(w, surfEdge - shore + 7f * k))
    val surf = Path().apply {
        moveTo(0f, shore - 2f * k)
        var x = 0f
        var i = 0
        while (x < w) {
            val dip = (3f + 3f * hash01(i + (squareLeft / (30f * k)).toInt(), 29, 13)) * k
            quadraticTo(x + 15f * k, surfEdge + dip, x + 30f * k, surfEdge)
            x += 30f * k
            i++
        }
        lineTo(w, shore - 2f * k)
        close()
    }
    drawPath(surf, Color(0xFF3FA4B0).copy(alpha = 0.9f))
    val foam = Path().apply {
        moveTo(0f, surfEdge)
        var x = 0f
        var i = 0
        while (x < w) {
            val dip = (3f + 3f * hash01(i + (squareLeft / (30f * k)).toInt(), 29, 13)) * k
            quadraticTo(x + 15f * k, surfEdge + dip, x + 30f * k, surfEdge)
            x += 30f * k
            i++
        }
    }
    drawPath(foam, Color(0xFFFFFBF0).copy(alpha = 0.95f), style = Stroke(width = 3.2f * k, cap = StrokeCap.Round))
    drawPath(foam, Color.White.copy(alpha = 0.35f), style = Stroke(width = 8f * k, cap = StrokeCap.Round))

    // Speckles in the dry sand, seeded so they hold still.
    var i = 0
    val cols = (w / (23f * k)).toInt() + 1
    val rows = ((h - shore) / (19f * k)).toInt() + 1
    while (i < cols * rows) {
        val cx = (i % cols) * 23f * k + hash01(i, 53, 97) * 18f * k
        val cy = shore + 22f * k + (i / cols) * 19f * k + hash01(i, 71, 89) * 14f * k
        drawCircle(Color(0xFFC99A5C).copy(alpha = 0.55f), radius = (0.9f + hash01(i, 13, 7)) * k, center = Offset(cx, cy))
        i++
    }

    drawStarfish(Offset(lx(40f), ly(352f)), 11f * k, (sin(t * PI / 4) * 6.0).toFloat())
    drawShell(Offset(lx(356f), ly(338f)), 9f * k)
    drawShell(Offset(lx(214f), ly(374f)), 6.5f * k)

    // Wide screens: a leaning palm in the left margin and a beach umbrella in the right.
    val sway = (sin(t * PI) * 3.0).toFloat()
    drawBeachPalm(Offset(lx(-64f), ly(330f)), k, sway)
    drawUmbrella(Offset(lx(470f), ly(320f)), k)
}

private val BEACH_INK = Color(0xFF4A2030)

/** A puffy cloud of overlapping circles, pale on top and blushing pink underneath from the sunset. */
private fun DrawScope.drawCloud(center: Offset, s: Float) {
    val puffs = listOf(Offset(-26f, 4f) to 14f, Offset(-8f, -6f) to 19f, Offset(14f, -2f) to 16f, Offset(30f, 6f) to 11f)
    for ((o, r) in puffs) drawCircle(Color(0xFFF59A9A).copy(alpha = 0.8f), radius = r * s, center = center + o * s + Offset(0f, 3f * s))
    for ((o, r) in puffs) drawCircle(Color(0xFFFFE6D6), radius = r * s, center = center + o * s)
    drawRect(Color(0xFFF59A9A).copy(alpha = 0.8f), topLeft = center + Offset(-38f, 8f) * s, size = Size(76f * s, 7f * s))
}

private fun DrawScope.drawStarfish(center: Offset, r: Float, rotationDeg: Float) {
    val star = Path()
    for (i in 0 until 10) {
        val a = ((rotationDeg - 90f + i * 36f) * PI / 180f).toFloat()
        val rr = if (i % 2 == 0) r else r * 0.45f
        val p = center + Offset(cos(a), sin(a)) * rr
        if (i == 0) star.moveTo(p.x, p.y) else star.lineTo(p.x, p.y)
    }
    star.close()
    drawPath(star, Color(0xFFFF7F5C))
    drawPath(star, BEACH_INK, style = Stroke(width = r * 0.16f, join = StrokeJoin.Round))
    for (i in 0 until 5) {
        val a = ((rotationDeg - 90f + i * 72f) * PI / 180f).toFloat()
        drawCircle(Color(0xFFFFD2B8), radius = r * 0.08f, center = center + Offset(cos(a), sin(a)) * (r * 0.5f))
    }
}

/** A scallop shell: a ribbed fan with a little hinge at the bottom. */
private fun DrawScope.drawShell(base: Offset, r: Float) {
    val fan = Path().apply {
        moveTo(base.x, base.y)
        lineTo(base.x - r, base.y - r * 0.5f)
        quadraticTo(base.x, base.y - r * 1.7f, base.x + r, base.y - r * 0.5f)
        close()
    }
    drawPath(fan, Color(0xFFFFE3E0))
    for (dx in listOf(-0.5f, 0f, 0.5f)) {
        drawLine(Color(0xFFE59AA0), base, Offset(base.x + dx * r * 1.3f, base.y - r * (1.05f - abs(dx) * 0.3f)), strokeWidth = r * 0.12f, cap = StrokeCap.Round)
    }
    drawPath(fan, BEACH_INK, style = Stroke(width = r * 0.16f, join = StrokeJoin.Round))
    drawRect(Color(0xFFF2B8B8), topLeft = Offset(base.x - r * 0.28f, base.y - r * 0.05f), size = Size(r * 0.56f, r * 0.28f))
}

private fun DrawScope.drawBeachPalm(base: Offset, k: Float, sway: Float) {
    val top = Offset(base.x + 40f * k, base.y - 200f * k)
    val trunk = Path().apply {
        moveTo(base.x, base.y)
        quadraticTo(base.x - 6f * k, base.y - 110f * k, top.x, top.y)
    }
    drawPath(trunk, BEACH_INK, style = Stroke(width = 15f * k, cap = StrokeCap.Round))
    drawPath(trunk, Color(0xFFB9824E), style = Stroke(width = 10f * k, cap = StrokeCap.Round))
    for (j in 1 until 9) {
        val f = j / 9f
        val p = Offset(
            (1 - f) * (1 - f) * base.x + 2 * (1 - f) * f * (base.x - 6f * k) + f * f * top.x,
            (1 - f) * (1 - f) * base.y + 2 * (1 - f) * f * (base.y - 110f * k) + f * f * top.y,
        )
        drawLine(Color(0xFF7A4E2A), p + Offset(-5f * k, 0f), p + Offset(5f * k, -2f * k), strokeWidth = 1.8f * k, cap = StrokeCap.Round)
    }
    for (deg in listOf(-165f, -130f, -95f, -60f, -25f, 5f)) {
        val a = (deg + sway) * PI.toFloat() / 180f
        val reach = Offset(cos(a) * 74f * k, sin(a) * 60f * k + 34f * k)
        drawFrond(top, reach, 10f * k)
    }
    for (dx in listOf(-5f, 5f)) drawCircle(Color(0xFF7A4E2A), radius = 6f * k, center = top + Offset(dx * k, 6f * k))
}

/** One palm frond: an inked, pointed leaf with a pale midrib. */
private fun DrawScope.drawFrond(base: Offset, reach: Offset, halfWidth: Float) {
    val length = reach.getDistance()
    if (length == 0f) return
    val d = reach / length
    val n = Offset(-d.y, d.x)
    val tip = base + reach
    val mid = base + reach * 0.45f
    val leaf = Path().apply {
        moveTo(base.x, base.y)
        quadraticTo(mid.x + n.x * halfWidth * 2f, mid.y + n.y * halfWidth * 2f, tip.x, tip.y)
        quadraticTo(mid.x - n.x * halfWidth * 2f, mid.y - n.y * halfWidth * 2f, base.x, base.y)
        close()
    }
    drawPath(leaf, Color(0xFF3E9A55))
    drawPath(leaf, BEACH_INK, style = Stroke(width = halfWidth * 0.3f, join = StrokeJoin.Round))
    drawLine(Color(0xFF8ED07A), base, base + reach * 0.85f, strokeWidth = halfWidth * 0.18f, cap = StrokeCap.Round)
}

/** A striped beach umbrella planted in the sand. */
private fun DrawScope.drawUmbrella(base: Offset, k: Float) {
    val top = base + Offset(-18f * k, -120f * k)
    drawLine(BEACH_INK, base, top, strokeWidth = 6f * k, cap = StrokeCap.Round)
    drawLine(Color(0xFFF5F0E6), base, top, strokeWidth = 3f * k, cap = StrokeCap.Round)
    val canopyR = 64f * k
    for (i in 0 until 6) {
        val slice = Path().apply {
            moveTo(top.x, top.y)
            arcTo(Rect(top.x - canopyR, top.y - canopyR * 0.6f, top.x + canopyR, top.y + canopyR * 0.6f), 180f + i * 30f, 30f, false)
            close()
        }
        drawPath(slice, if (i % 2 == 0) Color(0xFFE4513A) else Color(0xFFFFF3DC))
    }
    val canopy = Path().apply {
        moveTo(top.x - canopyR, top.y)
        arcTo(Rect(top.x - canopyR, top.y - canopyR * 0.6f, top.x + canopyR, top.y + canopyR * 0.6f), 180f, 180f, false)
        close()
    }
    drawPath(canopy, BEACH_INK, style = Stroke(width = 3f * k, join = StrokeJoin.Round))
    drawCircle(BEACH_INK, radius = 3.5f * k, center = top - Offset(0f, canopyR * 0.6f))
}

/** Beat-synced bounce: peaks right after the beat and eases out, ported from the prototype's beatBob. */
private fun beatBob(beat: Double): Float {
    if (beat < 0) return 0f
    val f = beat - floor(beat)
    return (1f - f.toFloat()).pow(3)
}

/** One crab's palette: shell, its lit and shaded tones, the ink it's outlined in, and its belly. */
private class CrabLook(
    val shell: Color,
    val shellLit: Color,
    val shellDark: Color,
    val ink: Color,
    val belly: Color,
    val hat: Boolean,
)

private val LEAD_CRAB = CrabLook(
    shell = Color(0xFFE5483A),
    shellLit = Color(0xFFFF8F72),
    shellDark = Color(0xFFA82A22),
    ink = Color(0xFF4A1210),
    belly = Color(0xFFFFC9A6),
    hat = true,
)

private val PLAYER_CRAB = CrabLook(
    shell = Color(0xFF3D86DD),
    shellLit = Color(0xFF8CC6FF),
    shellDark = Color(0xFF1F559E),
    ink = Color(0xFF0E2650),
    belly = Color(0xFFCFE6FF),
    hat = false,
)

/**
 * A cartoon crab at [pos] (its body centre) in the stage's logical space: inked outlines, a shaded
 * shell with spots and a pale belly, jointed legs, and big claws that fly up and snap shut on
 * [pulse] with a spark at each tip. The body squashes a little on the snap and bobs with the beat;
 * [gaze] turns the pupils toward the other crab.
 */
private fun DrawScope.drawCrab(pos: Offset, scale: Float, look: CrabLook, pulse: Float, bob: Float, gaze: Float) {
    withTransform({
        translate(pos.x, pos.y)
        scale(scale, scale, Offset.Zero)
    }) {
        val shadowCenter = Offset(0f, 44f)
        withTransform({ scale(1f, 0.2f, shadowCenter) }) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF7A4A20).copy(alpha = 0.45f), Color(0xFF7A4A20).copy(alpha = 0f)),
                    center = shadowCenter,
                    radius = 62f,
                ),
                radius = 62f,
                center = shadowCenter,
            )
        }

        withTransform({
            translate(0f, bob)
            scale(1f + 0.05f * pulse, 1f - 0.06f * pulse, Offset(0f, 30f))
        }) {
            // Legs: three jointed legs a side, tiptoeing on the sand.
            for (side in listOf(-1f, 1f)) {
                for (i in 0 until 3) {
                    val leg = Path().apply {
                        moveTo(side * 30f, 6f + i * 7f)
                        lineTo(side * (50f + i * 4f), 6f + i * 9f)
                        lineTo(side * (56f + i * 5f), 34f + i * 4f)
                    }
                    drawPath(leg, look.ink, style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(leg, look.shellDark, style = Stroke(width = 5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            // Arms and claws: raised a little at rest, thrown up and snapped shut on [pulse].
            for (side in listOf(-1f, 1f)) {
                val claw = Offset(side * (60f + pulse * 5f), -34f - pulse * 24f)
                val arm = Path().apply {
                    moveTo(side * 36f, -4f)
                    quadraticTo(side * 62f, -2f, claw.x - side * 3f, claw.y + 14f)
                }
                drawPath(arm, look.ink, style = Stroke(width = 11f, cap = StrokeCap.Round))
                drawPath(arm, look.shell, style = Stroke(width = 6.5f, cap = StrokeCap.Round))
                drawClaw(claw, side, pulse, look)
            }

            // Eye stalks.
            for (side in listOf(-1f, 1f)) {
                drawLine(look.ink, Offset(side * 12f, -24f), Offset(side * 16f, -48f), strokeWidth = 8f, cap = StrokeCap.Round)
                drawLine(look.shell, Offset(side * 12f, -24f), Offset(side * 16f, -48f), strokeWidth = 4f, cap = StrokeCap.Round)
            }

            // Shell: shaded dome with spots, a pale belly band and a glossy highlight.
            val bodyRect = Rect(-48f, -32f, 48f, 32f)
            val body = Path().apply { addOval(bodyRect) }
            drawOval(
                brush = Brush.radialGradient(
                    listOf(look.shellLit, look.shell, look.shellDark),
                    center = Offset(-14f, -16f),
                    radius = 70f,
                ),
                topLeft = bodyRect.topLeft,
                size = bodyRect.size,
            )
            clipPath(body) {
                drawOval(look.belly.copy(alpha = 0.6f), topLeft = Offset(-34f, 8f), size = Size(68f, 40f))
                for ((o, r) in listOf(Offset(-26f, -12f) to 4.5f, Offset(24f, -16f) to 3.5f, Offset(30f, -2f) to 2.5f)) {
                    drawCircle(look.shellDark.copy(alpha = 0.45f), radius = r, center = o)
                }
            }
            drawOval(look.ink, topLeft = bodyRect.topLeft, size = bodyRect.size, style = Stroke(width = 3.5f))
            drawOval(Color.White.copy(alpha = 0.4f), topLeft = Offset(-32f, -26f), size = Size(26f, 11f))

            // Eyes on top of the stalks, looking at the other crab.
            for (side in listOf(-1f, 1f)) {
                val eye = Offset(side * 16f, -52f)
                drawCircle(Color.White, radius = 11f, center = eye)
                drawCircle(look.ink, radius = 11f, center = eye, style = Stroke(width = 2.6f))
                val pupil = eye + Offset(gaze * 3f, 1f)
                drawCircle(Color(0xFF1A1206), radius = 5f, center = pupil)
                drawCircle(Color.White, radius = 1.8f, center = pupil + Offset(-1.6f, -1.8f))
            }

            // Blushing cheeks, and a mouth that pops open on each snap.
            for (side in listOf(-1f, 1f)) {
                drawOval(Color(0xFFFF7A8A).copy(alpha = 0.45f), topLeft = Offset(side * 26f - 6f, -8f), size = Size(12f, 7f))
            }
            if (pulse > 0.1f) {
                val mouth = Rect(-5f - 2f * pulse, -10f, 5f + 2f * pulse, -6f + 9f * pulse)
                drawOval(Color(0xFF6B1E1E), topLeft = mouth.topLeft, size = mouth.size)
                drawOval(look.ink, topLeft = mouth.topLeft, size = mouth.size, style = Stroke(width = 2f))
            } else {
                drawArc(
                    look.ink,
                    startAngle = 25f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(-8f, -16f),
                    size = Size(16f, 12f),
                    style = Stroke(width = 3f, cap = StrokeCap.Round),
                )
            }

            // The brim rests on top of the eyes, tipped back at a jaunty angle.
            if (look.hat) withTransform({ translate(0f, 8f); rotate(-8f, Offset(0f, -74f)) }) { drawStrawHat(look.ink) }
        }
    }
}

/**
 * One big claw at [center]: a fixed upper pincer and a lower one that swings shut as [pulse]
 * rises, with a spark bursting off the tip at the moment of the snap.
 */
private fun DrawScope.drawClaw(center: Offset, side: Float, pulse: Float, look: CrabLook) {
    val r = 19f
    val open = 0.62f * (1f - pulse) + 0.04f
    val dir = -PI.toFloat() / 2f + side * 0.45f
    val startAngle = dir + open
    val sweep = 2f * PI.toFloat() - 2f * open
    val claw = Path().apply {
        moveTo(center.x, center.y)
        lineTo(center.x + r * cos(startAngle), center.y + r * sin(startAngle))
        arcTo(
            rect = Rect(center.x - r, center.y - r, center.x + r, center.y + r),
            startAngleDegrees = startAngle * 180f / PI.toFloat(),
            sweepAngleDegrees = sweep * 180f / PI.toFloat(),
            forceMoveTo = false,
        )
        close()
    }
    drawPath(
        claw,
        brush = Brush.radialGradient(
            listOf(look.shellLit, look.shell, look.shellDark),
            center = center + Offset(-6f, -6f),
            radius = r * 1.4f,
        ),
    )
    // A pale inner edge along each pincer's bite.
    for (a in listOf(startAngle, startAngle + sweep)) {
        drawLine(look.belly, center + Offset(cos(a), sin(a)) * (r * 0.35f), center + Offset(cos(a), sin(a)) * (r * 0.9f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    }
    drawPath(claw, look.ink, style = Stroke(width = 3f, join = StrokeJoin.Round))

    if (pulse > 0f) {
        val tip = center + Offset(cos(dir), sin(dir)) * (r + 4f)
        val spread = 1f - pulse
        for (i in -2..2) {
            val a = dir + i * 0.5f
            val d = Offset(cos(a), sin(a))
            val inner = 4f + 10f * spread
            drawLine(
                Color(0xFFFFF4B8).copy(alpha = pulse),
                tip + d * inner,
                tip + d * (inner + 4f + 9f * pulse),
                strokeWidth = 1.5f + 2.5f * pulse,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The lead crab's straw sun hat, with a red band, perched above its eyes. */
private fun DrawScope.drawStrawHat(ink: Color) {
    val straw = Color(0xFFF2C66D)
    val crown = Path().apply {
        moveTo(-22f, -75f)
        cubicTo(-22f, -98f, 22f, -98f, 22f, -75f)
        close()
    }
    drawPath(crown, brush = Brush.verticalGradient(listOf(Color(0xFFFFE19A), straw), startY = -92f, endY = -75f))
    clipPath(crown) {
        drawRect(Color(0xFFE4513A), topLeft = Offset(-24f, -87f), size = Size(48f, 7f))
    }
    drawPath(crown, ink, style = Stroke(width = 3f, join = StrokeJoin.Round))
    val brim = Rect(-42f, -80f, 42f, -67f)
    drawOval(brush = Brush.verticalGradient(listOf(Color(0xFFFFE19A), Color(0xFFD9A54A)), startY = brim.top, endY = brim.bottom), topLeft = brim.topLeft, size = brim.size)
    drawArc(Color(0xFFB9853A), startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(-34f, -78f), size = Size(68f, 9f), style = Stroke(width = 1.4f))
    drawOval(ink, topLeft = brim.topLeft, size = brim.size, style = Stroke(width = 3f))
}

/** The main menu's Snap Crabs emblem: the lead crab, snapping its claws on every beat of [beat]. */
internal fun DrawScope.drawSnapCrabsEmblem(beat: Double) {
    val s = size.minDimension / 150f
    drawCrab(Offset(size.width / 2f, size.height / 2f + 17f * s), s, LEAD_CRAB, beatBob(beat), bob = 0f, gaze = 0f)
}
