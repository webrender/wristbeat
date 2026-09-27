package wristbeat.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
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
import wristbeat.core.MANGO_CHOP_END_BEATS
import wristbeat.core.MANGO_CHOP_BPM
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
fun MangoChopScreen(inputOffsetMs: Double, chart: ChartSetting, onRunningChanged: (Boolean) -> Unit = {}) {
    val audioClock = remember { AudioClock() }
    val audioEngine = remember { AudioEngine() }
    val haptics = remember { HapticEngine() }
    var stage by remember { mutableStateOf(MangoChopStage(inputOffsetMs)) }
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
    val runLengthSeconds = remember { (MANGO_CHOP_END_BEATS * secondsPerBeat).roundToInt() }
    val watch = LocalHudLayout.current.watch
    ReportRunning(started && !finished, onRunningChanged)
    // Highway: each toss's whistle on the cue row, and its landing (the beat to act on) on the
    // target row. Pineapples are swipe arrows, since they take a swipe rather than a tap.
    val highwayCues = remember(stage) { stage.tosses.map { highwayNote(it.fruit, it.beat, cue = true) } }
    val highwayTargets = remember(stage) { stage.tosses.map { highwayNote(it.fruit, it.landBeat, cue = false) } }

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

            val beat = (now - t0) / secondsPerBeat
            beatPosition = beat
            sinceChop += dt
            sinceSlash += dt

            if (stage.updateMisses(beat).isNotEmpty()) audioEngine.play(SoundId.THUD, now)
            tally = stage.tally()

            if (stage.isFinished(beat)) finished = true
        }
    }

    fun beatNow(): Double = (audioClock.now() - t0) / secondsPerBeat

    /** Starts or restarts a run; returns false when a run is in progress and the input is gameplay. */
    fun handleMenuPress(): Boolean {
        if (!started) {
            audioClock.start()
            // Fresh stage per run: judged tosses and the tally don't carry over into a replay.
            stage = MangoChopStage(inputOffsetMs)
            t0 = audioClock.now() + 0.3
            scheduledIndex = 0
            beatPosition = -1.0
            sinceChop = 10f
            sinceSlash = 10f
            tally = ScoreTally()
            finished = false
            started = true
            return true
        }
        if (finished) {
            started = false
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
                    cues = highwayCues,
                    targets = highwayTargets,
                    beatPosition = beatPosition,
                    // Same scroll speed as Snap Crabs' 2.5 beats at 116 BPM, and a mango's whistle
                    // (2 beats ahead of its landing) is on screen together with the landing.
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

        StageHeader("Mango Chop", Color(0xFFFFB320), chart) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(Color(0xFFFFB320), "Tap: chop")
                LegendDot(Color(0xFFE8A93A), "Swipe: slice", NoteShape.SWIPE)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = statusBottomPadding),
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
                            HudText(tallyLine(tally), color = Color(0xFFAAB8B5))
                            HudText("Tap to try again", color = Color(0xFFAAB8B5))
                        }
                    }
                }
                !started -> WatchAutoHide("idle") {
                    HudChip {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HudText("Tap to start", loud = true)
                            Spacer(Modifier.height(2.dp))
                            if (watch) {
                                // No keyboard on a watch, and the menu shows the calibration offset.
                                HudText("Tap: chop · swipe: slice", color = Color(0xFFAAB8B5))
                            } else {
                                HudText(
                                    "Chop mangoes and limes as they land, swipe to slice pineapples " +
                                        "(~${runLengthSeconds}s, ${MANGO_CHOP_BPM.toInt()} BPM)",
                                    color = Color(0xFFAAB8B5),
                                )
                                HudText("Keys: Space/J/F chop · D/K/arrows slice", color = Color(0xFFAAB8B5))
                                HudText(
                                    if (inputOffsetMs == 0.0) "Not calibrated" else "Calibrated offset ${formatMs(inputOffsetMs)}",
                                    color = Color(0xFFAAB8B5),
                                )
                            }
                        }
                    }
                }
                else -> WatchAutoHide("run") {
                    HudChip {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HudText(if (beatPosition < 4.0) "Get ready…" else "Chop on the landing", loud = true)
                            Spacer(Modifier.height(2.dp))
                            HudText(tallyLine(tally), color = Color(0xFFAAB8B5))
                        }
                    }
                }
            }
        }
    }
}

private fun highwayNote(fruit: Fruit, beat: Double, cue: Boolean): HighwayNote {
    val color = when (fruit) {
        Fruit.MANGO -> Color(0xFFFFB320)
        Fruit.LIME -> Color(0xFF86C537)
        Fruit.PINEAPPLE -> Color(0xFFE8A93A)
    }
    return when {
        fruit == Fruit.PINEAPPLE -> HighwayNote(beat, if (cue) color.copy(alpha = 0.75f) else color, if (cue) 7f else 10f, NoteShape.SWIPE)
        cue -> HighwayNote(beat, color.copy(alpha = 0.75f), 6f)
        else -> HighwayNote(beat, color, 8f)
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

/**
 * A market stall ported from the prototype's drawMarket: sky, hills, a swaying striped awning and
 * the wooden counter. Drawn in canvas coordinates so it bleeds past the square stage on wide or
 * tall screens; the counter top lines up with the stage's logical y = 300.
 */
private fun DrawScope.drawMarketBackground(squareLeft: Float, squareTop: Float, extent: Float, beat: Double) {
    val w = size.width
    val h = size.height
    val k = extent / 400f
    val counterTop = squareTop + 300f * k

    drawRect(Color(0xFF8ED6E3))

    val hills = Path().apply {
        moveTo(0f, counterTop - 50f * k)
        quadraticTo(w * 0.225f, counterTop - 130f * k, w * 0.475f, counterTop - 65f * k)
        quadraticTo(w * 0.725f, counterTop - 140f * k, w, counterTop - 70f * k)
        lineTo(w, counterTop)
        lineTo(0f, counterTop)
        close()
    }
    drawPath(hills, Color(0xFF6BBF8E))

    // Awning stripes are laid out from the square's left edge (like the prototype's i * 50) and
    // repeated outward to fill the full width; the scalloped edge sways with the beat.
    val stripe = 50f * k
    val awningHeight = 78f * k
    val sway = (sin(beat.coerceAtLeast(0.0) * PI) * 1.5).toFloat() * k
    val firstIndex = -((squareLeft / stripe).toInt() + 2)
    var i = firstIndex
    while (squareLeft + i * stripe - stripe / 2f < w) {
        val cx = squareLeft + i * stripe
        val color = if (abs(i) % 2 == 1) Color(0xFFFFF3DC) else Color(0xFFE4513A)
        drawRect(color, topLeft = Offset(cx - stripe / 2f, 0f), size = Size(stripe, awningHeight))
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(cx - stripe / 2f, awningHeight + sway - stripe / 2f),
            size = Size(stripe, stripe),
        )
        i++
    }

    drawRect(Color(0xFFC37E47), topLeft = Offset(0f, counterTop), size = Size(w, h - counterTop))
    drawRect(Color(0xFFA4622F), topLeft = Offset(0f, counterTop - 2f * k), size = Size(w, 8f * k))
    var grainY = counterTop + 35f * k
    while (grainY < h) {
        drawLine(Color.Black.copy(alpha = 0.12f), Offset(0f, grainY), Offset(w, grainY), strokeWidth = 2f * k)
        grainY += 35f * k
    }
}

private fun DrawScope.drawBoard() {
    drawRoundRect(Color(0xFFEAC690), topLeft = Offset(118f, 282f), size = Size(168f, 20f), cornerRadius = CornerRadius(8f))
    drawRoundRect(
        Color(0xFFB98A52),
        topLeft = Offset(118f, 282f),
        size = Size(168f, 20f),
        cornerRadius = CornerRadius(8f),
        style = Stroke(width = 2f),
    )
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
            val dt = ((beat - result.rawBeat) * secondsPerBeat).toFloat()
            if (dt > 1.2f || dt < 0f) return
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

private fun DrawScope.drawMango(cut: Cut) {
    drawOval(Color(0xFFFFB320), topLeft = Offset(-22f, -17f), size = Size(44f, 34f))
    val body = Path().apply { addOval(Rect(-22f, -17f, 22f, 17f)) }
    clipPath(body) { drawCircle(Color(0xFFF2703A), radius = 14f, center = Offset(12f, -8f)) }
    if (cut == Cut.NONE) {
        rotate(-0.5f * 180f / PI.toFloat(), Offset(-6f, -19f)) {
            drawOval(Color(0xFF3DA34E), topLeft = Offset(-15f, -23f), size = Size(18f, 8f))
        }
    } else {
        drawRect(Color(0xFFFFD86E), topLeft = Offset(if (cut == Cut.LEFT) -4f else 0f, -17f), size = Size(4f, 34f))
    }
}

private fun DrawScope.drawLime(cut: Cut) {
    drawCircle(Color(0xFF86C537), radius = 13f, center = Offset.Zero)
    drawCircle(Color(0xFFB4E26A), radius = 4f, center = Offset(-4f, -4f))
    if (cut != Cut.NONE) {
        drawRect(Color(0xFFDDF3A8), topLeft = Offset(if (cut == Cut.LEFT) -3f else 0f, -13f), size = Size(3f, 26f))
    }
}

private fun DrawScope.drawPineapple(cut: Cut) {
    val leaf = Color(0xFF3DA34E)
    for ((tipX, tipY) in listOf(-14f to -36f, 0f to -44f, 14f to -36f)) {
        val blade = Path().apply {
            moveTo(tipX * 0.35f - 6f, -14f)
            lineTo(tipX, tipY)
            lineTo(tipX * 0.35f + 6f, -14f)
            close()
        }
        drawPath(blade, leaf)
    }
    val bodyRect = Rect(-18f, -18f, 18f, 26f)
    drawOval(Color(0xFFE8A93A), topLeft = bodyRect.topLeft, size = bodyRect.size)
    // Diamond crosshatch on the skin.
    clipPath(Path().apply { addOval(bodyRect) }) {
        var d = -48f
        while (d <= 48f) {
            drawLine(Color(0xFFB9772A), Offset(d - 24f, -18f), Offset(d + 24f, 26f), strokeWidth = 2f)
            drawLine(Color(0xFFB9772A), Offset(d + 24f, -18f), Offset(d - 24f, 26f), strokeWidth = 2f)
            d += 11f
        }
    }
    if (cut == Cut.TOP || cut == Cut.BOTTOM) {
        drawRect(Color(0xFFFFE58A), topLeft = Offset(-17f, if (cut == Cut.TOP) -1f else 2f), size = Size(34f, 3f))
    }
}

/** A white streak across the board along the swipe direction, fading over a quarter second. */
private fun DrawScope.drawSlash(sinceSlash: Float, angleDegrees: Float) {
    val life = 0.25f
    if (sinceSlash >= life) return
    val fade = 1f - sinceSlash / life
    val radians = angleDegrees * PI.toFloat() / 180f
    val reach = 110f
    val dx = cos(radians) * reach
    val dy = sin(radians) * reach
    val center = Offset(BOARD_X, BOARD_Y - 10f)
    drawLine(
        Color.White.copy(alpha = 0.85f * fade),
        center - Offset(dx, dy),
        center + Offset(dx, dy),
        strokeWidth = 3f + 7f * fade,
        cap = StrokeCap.Round,
    )
}

/**
 * The player's cleaver, ported from the prototype's drawCleaver: resting raised, it strikes down
 * in 45ms and lifts back over the next 175ms.
 */
private fun DrawScope.drawCleaver(sinceChop: Float) {
    val raised = 0.75f
    val angle = when {
        sinceChop < 0.045f -> raised * (1f - sinceChop / 0.045f)
        sinceChop < 0.22f -> raised * ((sinceChop - 0.045f) / 0.175f)
        else -> raised
    }
    translate(318f, 274f) {
        rotate(angle * 180f / PI.toFloat(), Offset.Zero) {
            drawRoundRect(Color(0xFFD7DFE1), topLeft = Offset(-118f, -34f), size = Size(104f, 34f), cornerRadius = CornerRadius(4f))
            drawRect(Color(0xFF9FAAAE), topLeft = Offset(-118f, -34f), size = Size(104f, 7f))
            drawRect(Color.White, topLeft = Offset(-116f, -4f), size = Size(100f, 3f))
            drawCircle(Color(0xFF1A2927).copy(alpha = 0.55f), radius = 5f, center = Offset(-102f, -20f))
            drawRoundRect(Color(0xFF5A3A22), topLeft = Offset(-16f, -26f), size = Size(58f, 14f), cornerRadius = CornerRadius(6f))
            drawCircle(Color(0xFFC9CFD1), radius = 2.5f, center = Offset(0f, -19f))
            drawCircle(Color(0xFFC9CFD1), radius = 2.5f, center = Offset(18f, -19f))
        }
    }
}
