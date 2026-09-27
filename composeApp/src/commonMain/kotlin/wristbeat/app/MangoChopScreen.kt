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
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import wristbeat.core.ChopAction
import wristbeat.core.Fruit
import wristbeat.core.Grade
import wristbeat.core.MangoChopStage
import wristbeat.core.Rank
import wristbeat.core.ScoreTally
import wristbeat.core.SoundId
import wristbeat.core.Toss
import wristbeat.core.TossResult

/** A press held longer than this without moving isn't a tap any more (only matters for a deferred tap, see below). */
private const val DEFERRED_TAP_MAX_MS = 300L

/** The board's centre in the stage's 400×400 logical space, where every fruit lands. */
private const val BOARD_X = 200f
private const val BOARD_Y = 272f

private enum class PressResult { CONSUMED, CHOPPED, DEFERRED }

private enum class Cut { NONE, LEFT, RIGHT, TOP, BOTTOM }

/**
 * Mango Chop: a whistle marks each toss and the fruit lands on the board a set number of beats
 * later. Tap to chop mangoes (2 beats) and limes (1 beat); swipe to slice pineapples (2 beats,
 * tossed from the other side with a falling double whistle).
 *
 * Touch input has to tell a tap from the start of a swipe. When the nearest open fruit wants a
 * chop, the press chops the moment the finger lands, with no added latency, and a swipe that
 * follows still slices. When a pineapple is nearest, the press waits: crossing the swipe distance
 * slices at that moment, and lifting without moving chops, judged at the moment the finger landed.
 * On desktop, a mouse drag works the same way, and D/K/arrows slice (see [rememberTapKeyModifier]).
 */
@Composable
fun MangoChopScreen(
    calibration: Calibration,
    chart: ChartSetting,
    onRunningChanged: (Boolean) -> Unit = {},
    onMenu: (() -> Unit)? = null,
) {
    val audioClock = remember { AudioClock() }
    val audioEngine = remember { AudioEngine() }
    val haptics = remember { HapticEngine() }
    var stage by remember { mutableStateOf(MangoChopStage(calibration.inputOffsetMs)) }
    val secondsPerBeat = stage.secondsPerBeat

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var t0 by remember { mutableStateOf(0.0) }
    var scheduledIndex by remember { mutableStateOf(0) }
    var beatPosition by remember { mutableStateOf(-1.0) }
    var sinceChop by remember { mutableStateOf(10f) }
    var sinceSlash by remember { mutableStateOf(10f) }
    var slashAngle by remember { mutableStateOf(0f) }
    var keySlashFlip by remember { mutableStateOf(false) }
    var tally by remember { mutableStateOf(ScoreTally()) }
    ReportRunning(started && !finished, onRunningChanged)
    // Highway: each toss's landing (the beat to act on). Pineapples are swipe arrows, since they
    // take a swipe rather than a tap.
    val highwayTargets = remember(stage) { stage.tosses.map { highwayNote(it.fruit, it.landBeat) } }

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
            // Drawn at the beat the player hears (see SnapCrabsScreen), so fruit lands with its sound.
            val beat = stage.perceivedBeat(rawBeat)
            beatPosition = beat
            sinceChop += dt
            sinceSlash += dt

            if (stage.updateMisses(rawBeat).isNotEmpty()) audioEngine.play(SoundId.THUD, now)
            tally = stage.tally()

            if (stage.isFinished(beat)) finished = true
        }
    }

    fun beatNow(): Double = (audioClock.now() - t0) / secondsPerBeat

    fun restart() {
        audioClock.start()
        calibration.refreshOutput()
        // Fresh stage per run: judged tosses and the tally don't carry over into a replay.
        stage = MangoChopStage(calibration.inputOffsetMs)
        t0 = audioClock.now() + 0.3
        scheduledIndex = 0
        beatPosition = -1.0
        sinceChop = 10f
        sinceSlash = 10f
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

    fun act(action: ChopAction, beat: Double, angle: Float = 0f) {
        val outcome = stage.recordAction(action, beat)
        val now = audioClock.now()
        when (action) {
            ChopAction.CHOP -> {
                audioEngine.play(SoundId.CHOP, now)
                sinceChop = 0f
            }
            ChopAction.SLICE -> {
                audioEngine.play(SoundId.SLICE, now)
                sinceSlash = 0f
                slashAngle = angle
            }
        }
        when (outcome.grade) {
            Grade.PERFECT -> audioEngine.play(SoundId.PERFECT_DING, now + 0.01)
            Grade.OK -> Unit
            null -> audioEngine.play(SoundId.WHIFF, now + 0.02)
        }
        haptics.pulse()
        tally = stage.tally()
    }

    fun press(beat: Double): PressResult {
        if (handleMenuPress()) return PressResult.CONSUMED
        if (stage.expectedAction(beat) == ChopAction.SLICE) return PressResult.DEFERRED
        act(ChopAction.CHOP, beat)
        return PressResult.CHOPPED
    }

    fun keySlice() {
        if (handleMenuPress()) return
        // No drag direction from a key, so alternate the slash between two diagonals.
        keySlashFlip = !keySlashFlip
        act(ChopAction.SLICE, beatNow(), if (keySlashFlip) -20f else 20f)
    }

    // The stage begins as soon as it's opened — no tap needed to start the song.
    LaunchedEffect(Unit) { restart() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                rememberTapKeyModifier(
                    onTap = { if (!handleMenuPress()) act(ChopAction.CHOP, beatNow()) },
                    onSwipe = ::keySlice,
                ),
            )
            .pointerInput(Unit) {
                val swipeDistance = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val beatAtDown = beatNow()
                    val result = press(beatAtDown)
                    if (result == PressResult.CONSUMED) return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        val delta = change.position - down.position
                        if (delta.getDistance() >= swipeDistance) {
                            act(ChopAction.SLICE, beatNow(), atan2(delta.y, delta.x) * 180f / PI.toFloat())
                            return@awaitEachGesture
                        }
                        if (!change.pressed) {
                            val heldMs = change.uptimeMillis - down.uptimeMillis
                            if (result == PressResult.DEFERRED && heldMs <= DEFERRED_TAP_MAX_MS) {
                                act(ChopAction.CHOP, beatAtDown)
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

            drawMarketBackground(squareLeft, squareTop, squareExtent, beatPosition)

            if (chart.on) {
                drawNoteHighway(
                    targets = highwayTargets,
                    beatPosition = beatPosition,
                    // Same scroll speed as Snap Crabs' 2.5 beats at 116 BPM.
                    lookaheadBeats = 3.0,
                    hitFlash = highwayFlash(beatPosition, stage.tosses, secondsPerBeat),
                    laneY = squareTop + 0.36f * squareExtent,
                    laneLeftX = squareLeft + 0.20f * squareExtent,
                    laneRightX = squareLeft + 0.92f * squareExtent,
                )
            }

            // Everything interactive is authored in the prototype's 400×400 logical stage.
            withTransform({
                translate(squareLeft, squareTop)
                scale(k, k, Offset.Zero)
            }) {
                drawBoard()
                if (started) {
                    for ((i, toss) in stage.tosses.withIndex()) {
                        if (beatPosition < toss.beat) break
                        drawToss(toss.fruit, toss.beat, toss.landBeat, stage.resultOf(i), beatPosition, secondsPerBeat)
                    }
                } else {
                    drawFruit(Fruit.MANGO, Offset(150f, BOARD_Y), 0.2f)
                    drawFruit(Fruit.LIME, Offset(205f, BOARD_Y + 4f), 0f)
                    drawFruit(Fruit.PINEAPPLE, Offset(252f, BOARD_Y - 6f), -0.1f)
                }
                drawSlash(sinceSlash, slashAngle)
                drawCleaver(sinceChop)
            }
        }

        StageResults(
            visible = finished,
            accent = Color(0xFFFFB320),
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

private fun highwayNote(fruit: Fruit, beat: Double): HighwayNote {
    val color = when (fruit) {
        Fruit.MANGO -> Color(0xFFFFB320)
        Fruit.LIME -> Color(0xFF86C537)
        Fruit.PINEAPPLE -> Color(0xFFE8A93A)
    }
    return if (fruit == Fruit.PINEAPPLE) {
        HighwayNote(beat, color, 10f, NoteShape.SWIPE)
    } else {
        HighwayNote(beat, color, 8f)
    }
}

/**
 * The hit line's flash: a soft pulse on every beat that decays over a quarter second, flaring to
 * full as each fruit lands (decaying over 0.2s) — the same envelopes Snap Crabs drives per frame.
 */
private fun highwayFlash(beat: Double, tosses: List<Toss>, secondsPerBeat: Double): Float {
    if (beat < 0) return 0f
    val sinceBeat = (beat - floor(beat)) * secondsPerBeat
    val beatFlash = (1.0 - sinceBeat / 0.25).coerceAtLeast(0.0)
    val sinceLanding = tosses.map { beat - it.landBeat }.filter { it >= 0 }.minOrNull()?.times(secondsPerBeat)
    val landFlash = if (sinceLanding == null) 0.0 else (1.0 - sinceLanding / 0.2).coerceAtLeast(0.0)
    return maxOf(beatFlash * 0.5, landFlash).toFloat()
}

private val MARKET_INK = Color(0xFF3A1E0C)

private fun hash01(i: Int, mul: Int, mod: Int): Float = (((i * mul + 11) % mod + mod) % mod) / mod.toFloat()

/**
 * A sunny fruit stall: blue sky with drifting clouds, two layers of rolling hills dotted with
 * trees, a shaded, inked striped awning whose scalloped edge sways with the beat, a bunch of
 * bananas swinging from it, crates of fruit in the margins and a planked wooden counter. Laid out
 * from the stage square so the counter top always sits at the stage's logical y = 300, while
 * everything bleeds to the full canvas on wide or tall screens.
 */
private fun DrawScope.drawMarketBackground(squareLeft: Float, squareTop: Float, extent: Float, beat: Double) {
    val w = size.width
    val h = size.height
    val k = extent / 400f
    fun lx(x: Float) = squareLeft + x * k
    fun ly(y: Float) = squareTop + y * k
    val counterTop = ly(300f)
    val t = beat.coerceAtLeast(0.0)

    drawRect(brush = Brush.verticalGradient(listOf(Color(0xFF3FB4E6), Color(0xFF8ADCF0), Color(0xFFD8F6F2)), startY = 0f, endY = counterTop))

    drawSun(Offset(lx(78f), ly(196f)), 20f * k, beat)
    drawMarketCloud(Offset(lx(-40f + ((t * 2.0) % 520).toFloat()), ly(118f)), k)
    drawMarketCloud(Offset(lx(380f - ((t * 1.3) % 560).toFloat()), ly(150f)), 0.7f * k)

    // Far hills, then nearer, inked hills with a few lollipop trees.
    val far = Path().apply {
        moveTo(0f, ly(236f))
        var x = 0f
        var i = 0
        while (x < w) {
            quadraticTo(x + 60f * k, ly(206f + 16f * hash01(i, 37, 17)), x + 120f * k, ly(234f))
            x += 120f * k
            i++
        }
        lineTo(w, counterTop)
        lineTo(0f, counterTop)
        close()
    }
    drawPath(far, Color(0xFF9ED9B0))
    val nearTop = ly(262f)
    val near = Path().apply {
        moveTo(0f, nearTop)
        quadraticTo(lx(60f), ly(222f), lx(170f), ly(258f))
        quadraticTo(lx(280f), ly(214f), lx(420f), ly(254f))
        quadraticTo(lx(520f), ly(232f), w + 200f * k, ly(256f))
        lineTo(w, counterTop)
        lineTo(0f, counterTop)
        close()
    }
    drawPath(near, brush = Brush.verticalGradient(listOf(Color(0xFF5CC27E), Color(0xFF3E9E62)), startY = ly(214f), endY = counterTop))
    for ((x, y, r) in listOf(Triple(40f, 236f, 11f), Triple(252f, 230f, 9f), Triple(300f, 226f, 12f), Triple(-80f, 244f, 12f), Triple(470f, 244f, 11f))) {
        drawLine(MARKET_INK, Offset(lx(x), ly(y)), Offset(lx(x), ly(y + r * 1.6f)), strokeWidth = 5f * k, cap = StrokeCap.Round)
        drawLine(Color(0xFF8A5A30), Offset(lx(x), ly(y)), Offset(lx(x), ly(y + r * 1.6f)), strokeWidth = 2.5f * k, cap = StrokeCap.Round)
        drawCircle(MARKET_INK, radius = (r + 1.5f) * k, center = Offset(lx(x), ly(y)))
        drawCircle(Color(0xFF2F8A52), radius = r * k, center = Offset(lx(x), ly(y)))
        drawCircle(Color(0xFF6CCB84), radius = r * 0.45f * k, center = Offset(lx(x - r * 0.3f), ly(y - r * 0.3f)))
    }

    // Crates of fruit on the ground at either side of the stall.
    drawCrate(Offset(lx(-96f), counterTop), k, Color(0xFFFFB320), Color(0xFFF2703A))
    drawCrate(Offset(lx(426f), counterTop), k, Color(0xFF86C537), Color(0xFF5FA02A))

    // Counter: planks with seams and nails, a shadowed lip and a highlight along its front edge.
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFD08A50), Color(0xFFB06C38), Color(0xFF8E5226)), startY = counterTop, endY = h),
        topLeft = Offset(0f, counterTop),
        size = Size(w, h - counterTop),
    )
    var row = 0
    var plankY = counterTop + 26f * k
    while (plankY < h + 36f * k) {
        drawLine(MARKET_INK.copy(alpha = 0.35f), Offset(0f, plankY), Offset(w, plankY), strokeWidth = 2.2f * k)
        drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, plankY + 2.5f * k), Offset(w, plankY + 2.5f * k), strokeWidth = 1.2f * k)
        val seamStep = 150f * k
        var sx = squareLeft + (if (row % 2 == 0) 20f else 95f) * k - seamStep * ((squareLeft / seamStep).toInt() + 1)
        while (sx < w) {
            drawLine(MARKET_INK.copy(alpha = 0.3f), Offset(sx, plankY - 36f * k + 3f * k), Offset(sx, plankY - 3f * k), strokeWidth = 2f * k)
            for (dy in listOf(-30f, -9f)) drawCircle(MARKET_INK.copy(alpha = 0.4f), radius = 1.5f * k, center = Offset(sx + 7f * k, plankY + dy * k))
            sx += seamStep
        }
        plankY += 36f * k
        row++
    }
    drawRect(Color(0xFF7A4520), topLeft = Offset(0f, counterTop - 3f * k), size = Size(w, 9f * k))
    drawLine(Color(0xFFE8A868), Offset(0f, counterTop - 2f * k), Offset(w, counterTop - 2f * k), strokeWidth = 1.6f * k)
    drawLine(MARKET_INK, Offset(0f, counterTop - 4f * k), Offset(w, counterTop - 4f * k), strokeWidth = 2f * k)
    drawRect(Color.Black.copy(alpha = 0.18f), topLeft = Offset(0f, counterTop + 6f * k), size = Size(w, 6f * k))

    drawAwning(squareLeft, k, beat)
    // A bunch of bananas swinging from the awning.
    val swing = (sin(t * PI) * 7.0).toFloat()
    drawBananas(Offset(lx(24f), ly(64f)), k, swing)
}

private fun DrawScope.drawSun(center: Offset, r: Float, beat: Double) {
    val pulse = if (beat < 0) 0f else (1f - (beat - floor(beat)).toFloat()).let { it * it * it }
    val spin = (beat.coerceAtLeast(0.0) * 8.0).toFloat()
    for (i in 0 until 12) {
        val a = (spin + i * 30f) * PI.toFloat() / 180f
        val d = Offset(cos(a), sin(a))
        drawLine(Color(0xFFFFE27A).copy(alpha = 0.8f), center + d * (r * 1.3f), center + d * (r * (1.75f + 0.2f * pulse)), strokeWidth = r * 0.16f, cap = StrokeCap.Round)
    }
    drawCircle(Color(0xFFFFF3B0).copy(alpha = 0.5f), radius = r * 1.2f, center = center)
    drawCircle(brush = Brush.radialGradient(listOf(Color(0xFFFFF7C8), Color(0xFFFFD04A)), center = center - Offset(r * 0.3f, r * 0.3f), radius = r * 1.2f), radius = r, center = center)
}

private fun DrawScope.drawMarketCloud(center: Offset, s: Float) {
    val puffs = listOf(Offset(-24f, 4f) to 13f, Offset(-6f, -5f) to 17f, Offset(14f, -1f) to 14f, Offset(28f, 6f) to 9f)
    for ((o, r) in puffs) drawCircle(Color(0xFFBFE6F2), radius = r * s, center = center + o * s + Offset(0f, 3f * s))
    for ((o, r) in puffs) drawCircle(Color.White, radius = r * s, center = center + o * s)
    drawRect(Color(0xFFBFE6F2), topLeft = center + Offset(-34f, 8f) * s, size = Size(66f * s, 5f * s))
}

/** A wooden crate on the ground at [bottomCenter], heaped with round fruit. */
private fun DrawScope.drawCrate(bottomCenter: Offset, k: Float, fruit: Color, fruitDark: Color) {
    val cw = 90f * k
    val ch = 44f * k
    for (i in 0 until 5) {
        val c = Offset(bottomCenter.x - cw * 0.36f + i * cw * 0.18f, bottomCenter.y - ch - 4f * k + (i % 2) * 3f * k)
        drawCircle(MARKET_INK, radius = 13.5f * k, center = c)
        drawCircle(brush = Brush.radialGradient(listOf(lerp(fruit, Color.White, 0.3f), fruit, fruitDark), center = c - Offset(4f * k, 4f * k), radius = 16f * k), radius = 12f * k, center = c)
    }
    val top = Offset(bottomCenter.x - cw / 2f, bottomCenter.y - ch)
    drawRect(Color(0xFFC8925A), topLeft = top, size = Size(cw, ch))
    for (j in 1 until 3) drawLine(MARKET_INK.copy(alpha = 0.5f), top + Offset(0f, ch * j / 3f), top + Offset(cw, ch * j / 3f), strokeWidth = 2f * k)
    drawRect(MARKET_INK, topLeft = top, size = Size(cw, ch), style = Stroke(width = 2.5f * k))
}

/**
 * The stall's striped awning, laid out from the square's left edge and repeated outward to fill the
 * full width: each stripe shaded top to bottom, a scalloped, inked edge that sways with the beat,
 * and a soft shadow cast underneath it.
 */
private fun DrawScope.drawAwning(squareLeft: Float, k: Float, beat: Double) {
    val w = size.width
    val stripe = 50f * k
    val awningHeight = 78f * k
    val sway = (sin(beat.coerceAtLeast(0.0) * PI) * 1.5).toFloat() * k
    val firstIndex = -((squareLeft / stripe).toInt() + 2)

    val edge = Path()
    var i = firstIndex
    edge.moveTo(squareLeft + i * stripe - stripe / 2f, awningHeight + sway)
    while (squareLeft + i * stripe - stripe / 2f < w) {
        val cx = squareLeft + i * stripe
        edge.arcTo(Rect(cx - stripe / 2f, awningHeight + sway - stripe / 2f, cx + stripe / 2f, awningHeight + sway + stripe / 2f), 180f, -180f, false)
        i++
    }
    // The shadow the scallops cast on whatever's behind them.
    withTransform({ translate(0f, 8f * k) }) {
        drawPath(edge, Color.Black.copy(alpha = 0.14f), style = Stroke(width = 14f * k))
    }

    i = firstIndex
    while (squareLeft + i * stripe - stripe / 2f < w) {
        val cx = squareLeft + i * stripe
        val red = abs(i) % 2 == 0
        val top = if (red) Color(0xFFF26A4F) else Color(0xFFFFFBEF)
        val bottom = if (red) Color(0xFFC7372A) else Color(0xFFEBDDBF)
        val brush = Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = awningHeight + sway + stripe / 2f)
        drawRect(brush, topLeft = Offset(cx - stripe / 2f, 0f), size = Size(stripe, awningHeight + sway))
        drawArc(
            brush = brush,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(cx - stripe / 2f, awningHeight + sway - stripe / 2f),
            size = Size(stripe, stripe),
        )
        // A fold shadow down one side of each stripe.
        drawRect(Color.Black.copy(alpha = 0.06f), topLeft = Offset(cx + stripe * 0.2f, 0f), size = Size(stripe * 0.3f, awningHeight + sway))
        i++
    }
    drawPath(edge, MARKET_INK, style = Stroke(width = 3f * k, join = StrokeJoin.Round))
    drawRect(Color(0xFF7A4520), topLeft = Offset(0f, 0f), size = Size(w, 10f * k))
    drawLine(MARKET_INK, Offset(0f, 10f * k), Offset(w, 10f * k), strokeWidth = 2.5f * k)
}

/** A bunch of bananas hanging from a string at [anchor], swinging [swingDeg] with the beat. */
private fun DrawScope.drawBananas(anchor: Offset, k: Float, swingDeg: Float) {
    withTransform({ rotate(swingDeg, anchor) }) {
        val knot = anchor + Offset(0f, 18f * k)
        drawLine(MARKET_INK, anchor, knot, strokeWidth = 1.6f * k)
        // Three fat crescents fanning out from the stem, each curving back up at the tip.
        for ((tilt, flip) in listOf(40f to -1f, -40f to 1f, 0f to -1f)) {
            withTransform({ rotate(tilt, knot) }) {
                fun p(x: Float, y: Float) = Offset(knot.x + flip * x * k, knot.y + y * k)
                val a = p(-3f, 2f)
                val banana = Path().apply {
                    moveTo(a.x, a.y)
                    p(-15f, 14f).let { c1 -> p(-16f, 36f).let { c2 -> p(2f, 46f).let { e -> cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) } } }
                    p(-4f, 34f).let { c1 -> p(-3f, 16f).let { c2 -> p(4f, 3f).let { e -> cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y) } } }
                    close()
                }
                drawPath(banana, brush = Brush.horizontalGradient(listOf(Color(0xFFE8B830), Color(0xFFFFE066)), startX = knot.x - 16f * k, endX = knot.x + 4f * k))
                val ridge = Path().apply {
                    val s = p(-2f, 6f)
                    val c = p(-10f, 26f)
                    val e = p(0f, 42f)
                    moveTo(s.x, s.y)
                    quadraticTo(c.x, c.y, e.x, e.y)
                }
                drawPath(ridge, Color(0xFFD9A320), style = Stroke(width = 1.4f * k, cap = StrokeCap.Round))
                drawPath(banana, MARKET_INK, style = Stroke(width = 2.2f * k, join = StrokeJoin.Round))
                val tip = p(2f, 46f)
                drawCircle(Color(0xFF5A3A1A), radius = 2f * k, center = tip)
            }
        }
        drawRoundRect(Color(0xFF6B8A2A), topLeft = knot - Offset(4f * k, 3f * k), size = Size(8f * k, 8f * k), cornerRadius = CornerRadius(2f * k))
        drawRoundRect(MARKET_INK, topLeft = knot - Offset(4f * k, 3f * k), size = Size(8f * k, 8f * k), cornerRadius = CornerRadius(2f * k), style = Stroke(width = 1.6f * k))
    }
}

/** The cutting board, seen slightly from above: a grained top face over a darker front edge, with a hang hole. */
private fun DrawScope.drawBoard() {
    drawRoundRect(Color.Black.copy(alpha = 0.22f), topLeft = Offset(114f, 292f), size = Size(178f, 14f), cornerRadius = CornerRadius(7f))
    drawRoundRect(Color(0xFFB9854C), topLeft = Offset(116f, 280f), size = Size(172f, 22f), cornerRadius = CornerRadius(8f))
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFF7D9A6), Color(0xFFE8BF82)), startY = 276f, endY = 294f),
        topLeft = Offset(116f, 276f),
        size = Size(172f, 18f),
        cornerRadius = CornerRadius(8f),
    )
    for ((y, x0, x1) in listOf(Triple(281f, 130f, 210f), Triple(285f, 170f, 262f), Triple(289f, 136f, 188f))) {
        drawLine(Color(0xFFC99A5E).copy(alpha = 0.7f), Offset(x0, y), Offset(x1, y), strokeWidth = 1.2f, cap = StrokeCap.Round)
    }
    drawCircle(Color(0xFF8E5A2A), radius = 3.2f, center = Offset(276f, 285f))
    drawRoundRect(MARKET_INK, topLeft = Offset(116f, 276f), size = Size(172f, 26f), cornerRadius = CornerRadius(8f), style = Stroke(width = 2.5f))
}

/**
 * One toss, following the prototype's drawMangoStage: in flight it arcs from the side onto the
 * board; once cut, its halves fly apart; once missed, it bounces off the board and rolls away.
 */
private fun DrawScope.drawToss(
    fruit: Fruit,
    tossBeat: Double,
    landBeat: Double,
    result: TossResult?,
    beat: Double,
    secondsPerBeat: Double,
) {
    // Pineapples come from the right, mangoes and limes from the left.
    val side = if (fruit == Fruit.PINEAPPLE) -1f else 1f
    when {
        result?.hit == true -> {
            val dt = ((beat - result.beat) * secondsPerBeat).toFloat()
            if (dt > 1.2f || dt < 0f) return
            drawJuice(fruit, dt, (tossBeat * 7.0).toInt())
            if (fruit.action == ChopAction.CHOP) {
                val dx = 60f * dt + 10f
                val dy = 260f * dt * dt - 90f * dt
                drawFruit(fruit, Offset(BOARD_X - dx, BOARD_Y + dy), -dt * 4f, Cut.LEFT)
                drawFruit(fruit, Offset(BOARD_X + dx, BOARD_Y + dy), dt * 4f, Cut.RIGHT)
            } else {
                // The top half is knocked up and away; the bottom half stays on the board.
                drawFruit(fruit, Offset(BOARD_X + 110f * dt, BOARD_Y - 6f - 120f * dt + 300f * dt * dt), dt * 5f, Cut.TOP)
                drawFruit(fruit, Offset(BOARD_X - 10f * dt, BOARD_Y), 0f, Cut.BOTTOM)
            }
        }
        result != null -> {
            val d = ((beat - landBeat) * secondsPerBeat).toFloat()
            if (d > 1.5f || d < 0f) return
            val x = BOARD_X + side * d * 220f
            val y = BOARD_Y - abs(sin(d * 7f)) * 50f * exp(-d * 2f)
            drawFruit(fruit, Offset(x, y), side * d * 8f)
        }
        else -> {
            val u = ((beat - tossBeat) / fruit.airBeats).toFloat().coerceIn(0f, 1f)
            val height = when (fruit) {
                Fruit.MANGO -> 230f
                Fruit.LIME -> 140f
                Fruit.PINEAPPLE -> 210f
            }
            val startX = BOARD_X - side * 224f
            val x = startX + (BOARD_X - startX) * u
            val y = 250f + (BOARD_Y - 250f) * u - 4f * height * u * (1f - u)
            val spin = if (fruit == Fruit.PINEAPPLE) 3f else 7f
            drawFruit(fruit, Offset(x, y), side * u * spin)
        }
    }
}

/** A fruit at [pos] rotated by [rotation] radians, optionally cut in half ([cut] picks which half to draw). */
private fun DrawScope.drawFruit(fruit: Fruit, pos: Offset, rotation: Float, cut: Cut = Cut.NONE) {
    withTransform({
        translate(pos.x, pos.y)
        rotate(rotation * 180f / PI.toFloat(), Offset.Zero)
    }) {
        val (l, t, r, b) = when (cut) {
            Cut.NONE -> listOf(-60f, -60f, 60f, 60f)
            Cut.LEFT -> listOf(-60f, -60f, 0f, 60f)
            Cut.RIGHT -> listOf(0f, -60f, 60f, 60f)
            Cut.TOP -> listOf(-60f, -60f, 60f, 2f)
            Cut.BOTTOM -> listOf(-60f, 2f, 60f, 60f)
        }
        clipRect(l, t, r, b) {
            when (fruit) {
                Fruit.MANGO -> drawMango(cut)
                Fruit.LIME -> drawLime(cut)
                Fruit.PINEAPPLE -> drawPineapple(cut)
            }
        }
    }
}

/** Juice droplets bursting off a fruit as it's cut, arcing out and falling over half a second. */
private fun DrawScope.drawJuice(fruit: Fruit, dt: Float, seed: Int) {
    if (dt > 0.55f) return
    val color = when (fruit) {
        Fruit.MANGO -> Color(0xFFFFB02E)
        Fruit.LIME -> Color(0xFFB8E65A)
        Fruit.PINEAPPLE -> Color(0xFFFFE066)
    }
    val fade = 1f - dt / 0.55f
    for (i in 0 until 9) {
        val a = (-160f + 140f * hash01(i + seed, 61, 97)) * PI.toFloat() / 180f
        val speed = 110f + 90f * hash01(i + seed, 43, 89)
        val p = Offset(BOARD_X + cos(a) * speed * dt, BOARD_Y - 8f + sin(a) * speed * dt + 380f * dt * dt)
        val r = (2.2f + 2.2f * hash01(i + seed, 17, 31)) * (0.5f + 0.5f * fade)
        drawCircle(color.copy(alpha = fade), radius = r, center = p)
        drawCircle(Color.White.copy(alpha = 0.7f * fade), radius = r * 0.35f, center = p - Offset(r * 0.3f, r * 0.3f))
    }
    // A splash ring on the board at the moment of the cut.
    if (dt < 0.2f) {
        val s = dt / 0.2f
        drawOval(
            Color.White.copy(alpha = 0.7f * (1f - s)),
            topLeft = Offset(BOARD_X - 18f - 30f * s, BOARD_Y - 6f - 6f * s),
            size = Size(36f + 60f * s, 12f + 12f * s),
            style = Stroke(width = 3f * (1f - s) + 0.5f),
        )
    }
}

private fun DrawScope.drawMango(cut: Cut) {
    val bodyRect = Rect(-22f, -17f, 22f, 17f)
    val body = Path().apply { addOval(bodyRect) }
    drawOval(
        brush = Brush.radialGradient(
            listOf(Color(0xFFFFE07A), Color(0xFFFFB320), Color(0xFFE88A1E)),
            center = Offset(-8f, -6f),
            radius = 30f,
        ),
        topLeft = bodyRect.topLeft,
        size = bodyRect.size,
    )
    clipPath(body) {
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFFF2503A), Color(0xFFF2703A).copy(alpha = 0f)), center = Offset(14f, -10f), radius = 20f),
            radius = 20f,
            center = Offset(14f, -10f),
        )
    }
    if (cut == Cut.NONE) {
        drawOval(Color.White.copy(alpha = 0.55f), topLeft = Offset(-15f, -12f), size = Size(12f, 6f))
        drawOval(MARKET_INK, topLeft = bodyRect.topLeft, size = bodyRect.size, style = Stroke(width = 2.5f))
        drawLine(MARKET_INK, Offset(-4f, -16f), Offset(-6f, -21f), strokeWidth = 3f, cap = StrokeCap.Round)
        rotate(-0.5f * 180f / PI.toFloat(), Offset(-6f, -19f)) {
            drawOval(Color(0xFF3DA34E), topLeft = Offset(-17f, -23f), size = Size(19f, 8f))
            drawLine(Color(0xFF8ED07A), Offset(-15f, -19f), Offset(0f, -19f), strokeWidth = 1f)
            drawOval(MARKET_INK, topLeft = Offset(-17f, -23f), size = Size(19f, 8f), style = Stroke(width = 1.8f))
        }
    } else {
        drawOval(MARKET_INK, topLeft = bodyRect.topLeft, size = bodyRect.size, style = Stroke(width = 2.5f))
        // The juicy cut face, with the flat pit showing.
        val x = if (cut == Cut.LEFT) -5f else 0f
        drawRect(Color(0xFFFFD86E), topLeft = Offset(x, -16f), size = Size(5f, 32f))
        drawRect(Color(0xFFF4E4B0), topLeft = Offset(x + (if (cut == Cut.LEFT) 2f else 0f), -8f), size = Size(3f, 16f))
        drawLine(MARKET_INK, Offset(if (cut == Cut.LEFT) 0f else 0f, -16f), Offset(0f, 16f), strokeWidth = 1.8f)
    }
}

private fun DrawScope.drawLime(cut: Cut) {
    drawCircle(
        brush = Brush.radialGradient(listOf(Color(0xFFC6EE7E), Color(0xFF86C537), Color(0xFF4E9A22)), center = Offset(-4f, -4f), radius = 17f),
        radius = 13f,
        center = Offset.Zero,
    )
    for ((dx, dy) in listOf(4f to 5f, 7f to -2f, -2f to 8f)) drawCircle(Color(0xFF5FA02A), radius = 0.9f, center = Offset(dx, dy))
    drawOval(Color.White.copy(alpha = 0.6f), topLeft = Offset(-8f, -9f), size = Size(8f, 5f))
    drawCircle(MARKET_INK, radius = 13f, center = Offset.Zero, style = Stroke(width = 2.3f))
    if (cut == Cut.NONE) {
        drawCircle(MARKET_INK, radius = 1.6f, center = Offset(12f, -3f))
    } else {
        // A lime wheel's pale face with its segment lines.
        val x = if (cut == Cut.LEFT) -4f else 0f
        drawRect(Color(0xFFE4F7B4), topLeft = Offset(x, -12f), size = Size(4f, 24f))
        drawLine(Color(0xFFA6D86A), Offset(x + 2f, -10f), Offset(x + 2f, 10f), strokeWidth = 1f)
        drawLine(MARKET_INK, Offset(0f, -13f), Offset(0f, 13f), strokeWidth = 1.6f)
    }
}

private fun DrawScope.drawPineapple(cut: Cut) {
    for ((tipX, tipY, shade) in listOf(Triple(-15f, -36f, 0.2f), Triple(15f, -36f, 0.2f), Triple(-7f, -46f, 0f), Triple(7f, -46f, 0f), Triple(0f, -50f, 0.1f))) {
        val blade = Path().apply {
            moveTo(tipX * 0.3f - 6f, -14f)
            quadraticTo(tipX * 0.5f - 3f, (tipY - 14f) / 2f, tipX, tipY)
            quadraticTo(tipX * 0.5f + 3f, (tipY - 14f) / 2f, tipX * 0.3f + 6f, -14f)
            close()
        }
        drawPath(blade, lerp(Color(0xFF4DB35E), Color(0xFF2A7A3A), shade))
        drawPath(blade, MARKET_INK, style = Stroke(width = 1.8f, join = StrokeJoin.Round))
    }
    val bodyRect = Rect(-18f, -18f, 18f, 26f)
    drawOval(
        brush = Brush.radialGradient(listOf(Color(0xFFFFD36A), Color(0xFFE8A93A), Color(0xFFB9772A)), center = Offset(-6f, -4f), radius = 30f),
        topLeft = bodyRect.topLeft,
        size = bodyRect.size,
    )
    // Diamond crosshatch on the skin, with a spiky eye in each diamond.
    clipPath(Path().apply { addOval(bodyRect) }) {
        var d = -48f
        while (d <= 48f) {
            drawLine(Color(0xFFA0621E), Offset(d - 24f, -18f), Offset(d + 24f, 26f), strokeWidth = 1.8f)
            drawLine(Color(0xFFA0621E), Offset(d + 24f, -18f), Offset(d - 24f, 26f), strokeWidth = 1.8f)
            d += 11f
        }
        for (row in 0 until 5) for (col in -2..2) {
            val p = Offset(col * 11f + (if (row % 2 == 0) 0f else 5.5f), -13f + row * 9.5f)
            drawCircle(Color(0xFF7A4A18), radius = 1.2f, center = p)
        }
    }
    if (cut == Cut.NONE) drawOval(Color.White.copy(alpha = 0.4f), topLeft = Offset(-12f, -12f), size = Size(8f, 14f))
    drawOval(MARKET_INK, topLeft = bodyRect.topLeft, size = bodyRect.size, style = Stroke(width = 2.5f))
    if (cut == Cut.TOP || cut == Cut.BOTTOM) {
        drawRect(Color(0xFFFFE58A), topLeft = Offset(-17f, if (cut == Cut.TOP) -2f else 2f), size = Size(34f, 4f))
        drawRect(Color(0xFFFFF4C2), topLeft = Offset(-4f, if (cut == Cut.TOP) -2f else 2f), size = Size(8f, 4f))
        drawLine(MARKET_INK, Offset(-17f, 2f), Offset(17f, 2f), strokeWidth = 1.6f)
    }
}

/**
 * The swipe's blade trail across the board along the swipe direction: a tapered white streak with
 * a warm glow around it, fading over a quarter second.
 */
private fun DrawScope.drawSlash(sinceSlash: Float, angleDegrees: Float) {
    val life = 0.25f
    if (sinceSlash >= life) return
    val fade = 1f - sinceSlash / life
    val radians = angleDegrees * PI.toFloat() / 180f
    val dir = Offset(cos(radians), sin(radians))
    val n = Offset(-dir.y, dir.x)
    val reach = 120f
    val center = Offset(BOARD_X, BOARD_Y - 10f)
    val tail = center - dir * reach
    val head = center + dir * reach
    fun blade(halfWidth: Float) = Path().apply {
        moveTo(tail.x, tail.y)
        quadraticTo(center.x + n.x * halfWidth, center.y + n.y * halfWidth, head.x, head.y)
        quadraticTo(center.x - n.x * halfWidth * 0.3f, center.y - n.y * halfWidth * 0.3f, tail.x, tail.y)
        close()
    }
    drawPath(blade(22f * fade + 4f), Color(0xFFFFE08A).copy(alpha = 0.45f * fade))
    drawPath(blade(10f * fade + 2f), Color.White.copy(alpha = 0.95f * fade))
}

/**
 * The player's cleaver, ported from the prototype's drawCleaver: resting raised, it strikes down
 * in 45ms and lifts back over the next 175ms, with a motion smear behind the blade on the way down.
 */
private fun DrawScope.drawCleaver(sinceChop: Float) {
    val raised = 0.75f
    val angle = when {
        sinceChop < 0.045f -> raised * (1f - sinceChop / 0.045f)
        sinceChop < 0.22f -> raised * ((sinceChop - 0.045f) / 0.175f)
        else -> raised
    }
    translate(318f, 274f) {
        if (sinceChop < 0.12f) {
            val smear = 1f - sinceChop / 0.12f
            rotate(angle * 180f / PI.toFloat(), Offset.Zero) {
                // A band of speed lines swept out by the blade's outer half.
                for ((r, width) in listOf(112f to 4f, 96f to 3f, 80f to 2f)) {
                    drawArc(
                        Color.White.copy(alpha = 0.6f * smear),
                        startAngle = 184f,
                        sweepAngle = 26f * smear,
                        useCenter = false,
                        topLeft = Offset(-r, -r - 17f),
                        size = Size(r * 2f, r * 2f),
                        style = Stroke(width = width, cap = StrokeCap.Round),
                    )
                }
            }
        }
        rotate(angle * 180f / PI.toFloat(), Offset.Zero) {
            val blade = Rect(-118f, -34f, -14f, 0f)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFFB7C2C6), Color(0xFFEFF4F5), Color(0xFFCBD4D7)), startY = blade.top, endY = blade.bottom),
                topLeft = blade.topLeft,
                size = blade.size,
                cornerRadius = CornerRadius(4f),
            )
            drawRect(Color(0xFF8C979B), topLeft = Offset(-118f, -34f), size = Size(104f, 7f))
            drawLine(Color.White, Offset(-114f, -3f), Offset(-18f, -3f), strokeWidth = 2.5f, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.7f), Offset(-96f, -22f), Offset(-60f, -14f), strokeWidth = 2f, cap = StrokeCap.Round)
            drawCircle(Color(0xFF3A4448), radius = 5f, center = Offset(-102f, -20f))
            drawCircle(Color(0xFF8C979B), radius = 5f, center = Offset(-102f, -20f), style = Stroke(width = 1.5f))
            drawRoundRect(MARKET_INK, topLeft = blade.topLeft, size = blade.size, cornerRadius = CornerRadius(4f), style = Stroke(width = 2.5f))
            val handle = Rect(-16f, -27f, 44f, -11f)
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFF8A5A34), Color(0xFF5A3A22), Color(0xFF3E2616)), startY = handle.top, endY = handle.bottom),
                topLeft = handle.topLeft,
                size = handle.size,
                cornerRadius = CornerRadius(7f),
            )
            drawRoundRect(MARKET_INK, topLeft = handle.topLeft, size = handle.size, cornerRadius = CornerRadius(7f), style = Stroke(width = 2.5f))
            for (x in listOf(2f, 20f)) {
                drawCircle(Color(0xFFE2E8EA), radius = 2.6f, center = Offset(x, -19f))
                drawCircle(MARKET_INK, radius = 2.6f, center = Offset(x, -19f), style = Stroke(width = 1f))
            }
        }
    }
}

/** The main menu's Mango Chop emblem: a mango rocking with [beat]. */
internal fun DrawScope.drawMangoChopEmblem(beat: Double) {
    val s = size.minDimension / 58f
    withTransform({
        translate(size.width / 2f, size.height / 2f + 3f * s)
        scale(s, s, Offset.Zero)
        rotate((sin(beat * PI / 2) * 12.0).toFloat(), Offset.Zero)
    }) {
        drawMango(Cut.NONE)
    }
}
