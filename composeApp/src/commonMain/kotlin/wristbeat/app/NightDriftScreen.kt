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
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import wristbeat.core.DriveAction
import wristbeat.core.DriveTarget
import wristbeat.core.Grade
import wristbeat.core.NightDriftStage
import wristbeat.core.Rank
import wristbeat.core.ScoreTally
import wristbeat.core.SoundId
import wristbeat.core.TossResult

/** A press held longer than this without moving isn't a tap any more (see [MangoChopScreen]'s same constant). */
private const val DEFERRED_TAP_MAX_MS = 300L

private enum class DrivePressResult { CONSUMED, HIT, DEFERRED }

private val BOOST_COLOR = Color(0xFF3FE0FF)
private val DRIFT_COLOR = Color(0xFFFF4FD8)
private val SIGN_YELLOW = Color(0xFFFFD23F)
private val NIGHT_INK = Color(0xFF120822)

/** How many beats ahead a gate first shows up on the road, far off by the horizon. */
private const val ROAD_LOOKAHEAD_BEATS = 3.0

/** How long after passing the car an un-hit gate takes to fade out as it rushes past. */
private const val PASS_BEATS = 0.25

// The road, in the stage's 400×400 logical space: it vanishes at (200, HORIZON_Y), and gates reach
// the car's rear bumper at HIT_Y, where the road is ROAD_HALF either side of the centre line.
private const val HORIZON_Y = 175f
private const val HIT_Y = 322f
private const val ROAD_HALF = 150f
private const val CAR_Y = 352f

/**
 * Night Drift: a car races down a neon mountain pass at night to a Eurobeat song. Neon gates come
 * up the road on nearly every beat and the player taps to boost through each as it reaches the
 * car; drift corners, marked by chevron boards and called two beats ahead by the navigator's
 * beep, take a swipe, and throw the car sideways in a cloud of tyre smoke.
 *
 * Touch input follows [MangoChopScreen]'s rule for telling a tap from the start of a swipe: when
 * the nearest open target is a gate the press boosts the moment the finger lands; when it's a
 * corner the press waits to see whether it becomes a swipe, and otherwise boosts on release,
 * judged at the moment the finger landed.
 */
@Composable
fun NightDriftScreen(
    calibration: Calibration,
    chart: ChartSetting,
    onRunningChanged: (Boolean) -> Unit = {},
    onMenu: (() -> Unit)? = null,
) {
    val audioClock = remember { AudioClock() }
    val audioEngine = remember { AudioEngine() }
    val haptics = remember { HapticEngine() }
    var stage by remember { mutableStateOf(NightDriftStage(calibration.inputOffsetMs)) }
    val secondsPerBeat = stage.secondsPerBeat

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var t0 by remember { mutableStateOf(0.0) }
    var scheduledIndex by remember { mutableStateOf(0) }
    var beatPosition by remember { mutableStateOf(-1.0) }
    var lastFiredBeat by remember { mutableStateOf(-1) }
    var beatFlashPhase by remember { mutableStateOf(0f) }
    var nextTargetIndex by remember { mutableStateOf(0) }
    var targetPulsePhase by remember { mutableStateOf(0f) }
    var boostPulse by remember { mutableStateOf(0f) }
    var driftPulse by remember { mutableStateOf(0f) }
    var driftDir by remember { mutableStateOf(1f) }
    var tally by remember { mutableStateOf(ScoreTally()) }
    // How this run compared with the song's saved best, once it has finished.
    var record by remember { mutableStateOf<RecordResult?>(null) }
    ReportRunning(started && !finished, onRunningChanged)

    // Corners alternate left and right, so the road's chevrons and the car's slide swap sides.
    val cornerDirs = remember(stage) {
        var n = 0
        stage.targets.map { if (it.action == DriveAction.DRIFT) (if (n++ % 2 == 0) -1f else 1f) else 0f }
    }
    val highwayTargets = remember(stage) { stage.targets.map { nightDriftHighwayNote(it.beat, drift = it.action == DriveAction.DRIFT) } }

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
            // Drawn at the beat the player hears (see SnapCrabsScreen).
            val beat = stage.perceivedBeat(rawBeat)
            beatPosition = beat

            val beatIndex = floor(beat).toInt()
            if (beatIndex > lastFiredBeat) {
                lastFiredBeat = beatIndex
                beatFlashPhase = 1f
            }
            while (nextTargetIndex < stage.targets.size && beat >= stage.targets[nextTargetIndex].beat) {
                nextTargetIndex++
                targetPulsePhase = 1f
            }
            beatFlashPhase = (beatFlashPhase - dt * 4f).coerceAtLeast(0f)
            targetPulsePhase = (targetPulsePhase - dt * 5f).coerceAtLeast(0f)
            boostPulse = (boostPulse - dt * 4f).coerceAtLeast(0f)
            driftPulse = (driftPulse - dt * 1.8f).coerceAtLeast(0f)

            stage.updateMisses(rawBeat)
            tally = stage.tally()

            if (stage.isFinished(beat)) {
                finished = true
                record = submitScore(Stage.NIGHT_DRIFT, tally.percent)
            }
        }
    }

    fun beatNow(): Double = (audioClock.now() - t0) / secondsPerBeat

    /** The beat when a pointer event happened, from its timestamp (see [AudioClock.timeAtInputEvent]). */
    fun beatAt(eventUptimeMillis: Long): Double = (audioClock.timeAtInputEvent(eventUptimeMillis) - t0) / secondsPerBeat

    fun restart() {
        audioClock.start()
        calibration.refreshOutput()
        // Fresh stage per run: judged targets and the tally don't carry over into a replay.
        stage = NightDriftStage(calibration.inputOffsetMs)
        t0 = audioClock.now() + 0.3
        scheduledIndex = 0
        beatPosition = -1.0
        lastFiredBeat = -1
        beatFlashPhase = 0f
        nextTargetIndex = 0
        targetPulsePhase = 0f
        boostPulse = 0f
        driftPulse = 0f
        tally = ScoreTally()
        record = null
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

    /** [swipeDx] is the swipe's horizontal travel, for which way to slide when no corner was hit. */
    fun act(action: DriveAction, beat: Double, swipeDx: Float = 0f) {
        val outcome = stage.recordAction(action, beat)
        val now = audioClock.now()
        if (action == DriveAction.BOOST) {
            audioEngine.play(SoundId.BOOST, now)
            boostPulse = 1f
        } else {
            audioEngine.play(SoundId.SKID, now)
            // Slide the way the corner turns; a stray drift follows the swipe, or else swaps sides.
            driftDir = outcome.targetIndex?.let { cornerDirs[it] }
                ?: if (swipeDx != 0f) (if (swipeDx < 0f) -1f else 1f) else -driftDir
            driftPulse = 1f
        }
        when (outcome.grade) {
            Grade.PERFECT -> audioEngine.play(SoundId.PERFECT_DING, now + 0.01)
            Grade.OK -> Unit
            null -> audioEngine.play(SoundId.WHIFF, now + 0.02)
        }
        haptics.pulse()
        tally = stage.tally()
    }

    fun press(beat: Double): DrivePressResult {
        if (handleMenuPress()) return DrivePressResult.CONSUMED
        if (stage.expectedAction(beat) == DriveAction.DRIFT) return DrivePressResult.DEFERRED
        act(DriveAction.BOOST, beat)
        return DrivePressResult.HIT
    }

    fun keyTap() {
        if (!handleMenuPress()) act(DriveAction.BOOST, beatNow())
    }

    fun keySwipe() {
        if (!handleMenuPress()) act(DriveAction.DRIFT, beatNow())
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
                    val beatAtDown = beatAt(down.uptimeMillis)
                    val result = press(beatAtDown)
                    if (result == DrivePressResult.CONSUMED) return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        val delta = change.position - down.position
                        if (delta.getDistance() >= swipeDistance) {
                            act(DriveAction.DRIFT, beatAt(change.uptimeMillis), delta.x)
                            return@awaitEachGesture
                        }
                        if (!change.pressed) {
                            val heldMs = change.uptimeMillis - down.uptimeMillis
                            if (result == DrivePressResult.DEFERRED && heldMs <= DEFERRED_TAP_MAX_MS) {
                                act(DriveAction.BOOST, beatAtDown)
                            }
                            return@awaitEachGesture
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawNightDriftScene(
                beat = beatPosition,
                targets = stage.targets,
                resultOf = stage::resultOf,
                cornerDirs = cornerDirs,
                boostPulse = boostPulse,
                driftPulse = driftPulse,
                driftDir = driftDir,
                highway = highwayTargets.takeIf { chart.on },
                // 3.33 beats at NIGHT_DRIFT_BPM is the same ~1.29s look-ahead every stage's highway
                // uses, so the notes scroll at the speed the player already knows.
                lookaheadBeats = 3.33,
                hitFlash = maxOf(beatFlashPhase * 0.5f, targetPulsePhase),
            )
        }

        StreakBadge(tally.streak, beat = { beatPosition }, visible = !finished)

        StageResults(
            visible = finished,
            accent = Stage.NIGHT_DRIFT.accent,
            sound = if (tally.rank == Rank.TRY_AGAIN) SoundId.BOO else SoundId.CHEER,
            audioEngine = audioEngine,
            audioClock = audioClock,
            headline = when (tally.rank) {
                Rank.SUPERB -> "Superb!"
                Rank.OK -> "OK"
                Rank.TRY_AGAIN -> "Try again"
            },
            heroValue = tally.percent,
            heroFormat = { "$it%" },
            stats = gradeStats(tally.perfect, tally.ok, tally.miss),
            passed = tally.rank != Rank.TRY_AGAIN,
            record = record,
            onRestart = ::restart,
            onMenu = onMenu,
        )
    }
}

/** A target on the highway: a dot for a boost gate, a swipe arrow for a drift corner. */
internal fun nightDriftHighwayNote(beat: Double, drift: Boolean): HighwayNote =
    if (drift) HighwayNote(beat, DRIFT_COLOR, 9f, NoteShape.SWIPE) else HighwayNote(beat, BOOST_COLOR, 8f)

/** Beat-synced bounce: peaks right after the beat and eases out (see [SnapCrabsScreen]'s same shape). */
private fun beatBob(beat: Double): Float {
    if (beat < 0) return 0f
    val f = beat - floor(beat)
    return (1f - f.toFloat()).pow(3)
}

/**
 * How big something [beatsAhead] of the car looks: 1 at the car, shrinking toward the horizon,
 * and a little bigger than 1 just after it rushes past.
 */
private fun depthScale(beatsAhead: Double): Float = (1.0 / (1.0 + 1.6 * beatsAhead)).toFloat()

/** The logical y of something at depth scale [s]: the horizon at 0, the car's bumper at 1. */
private fun roadY(s: Float): Float = HORIZON_Y + (HIT_Y - HORIZON_Y) * s

/**
 * The whole Night Drift scene filling the canvas: the neon night sky, mountains and distant city,
 * the road with its lamps and lane dashes rushing by on the beat, the gates and corners coming up
 * it, the note highway ([highway], or none when the chart is hidden) and the car.
 */
private fun DrawScope.drawNightDriftScene(
    beat: Double,
    targets: List<DriveTarget>,
    resultOf: (Int) -> TossResult?,
    cornerDirs: List<Float>,
    boostPulse: Float,
    driftPulse: Float,
    driftDir: Float,
    highway: List<HighwayNote>?,
    lookaheadBeats: Double,
    hitFlash: Float,
) {
    val squareExtent = minOf(size.width, size.height)
    val squareLeft = (size.width - squareExtent) / 2f
    val squareTop = (size.height - squareExtent) / 2f
    val k = squareExtent / 400f

    drawNightSky(squareLeft, squareTop, squareExtent, beat)

    withTransform({
        translate(squareLeft, squareTop)
        scale(k, k, Offset.Zero)
    }) {
        // The road bleeds past the square to the canvas edges, in logical units.
        val bottom = (size.height - squareTop) / k + 10f
        drawRoad(beat, bottom, boostPulse)

        // The navigator's warning sign flashes by the roadside while a corner is called.
        val called = targets.indices.firstOrNull { targets[it].action == DriveAction.DRIFT && targets[it].beat - beat in 0.0..2.2 }
        if (called != null) drawCornerWarning(cornerDirs[called], beat)

        // Gates and corners still ahead, far to near, then the car, then anything rushing past it.
        val visible = targets.indices.filter { targets[it].beat - beat in -PASS_BEATS..ROAD_LOOKAHEAD_BEATS }
        for (i in visible.reversed()) {
            val ahead = targets[i].beat - beat
            if (ahead >= 0) drawRoadTarget(targets[i], ahead, resultOf(i), cornerDirs[i])
        }
        drawCar(Offset(200f, CAR_Y), beat, boostPulse, driftPulse, driftDir)
        for (i in visible.reversed()) {
            val ahead = targets[i].beat - beat
            if (ahead < 0) drawRoadTarget(targets[i], ahead, resultOf(i), cornerDirs[i])
        }
        // A burst of sparks as the car clears a gate or corner cleanly.
        for (i in targets.indices) {
            val result = resultOf(i) ?: continue
            if (!result.hit) continue
            val since = (beat - result.beat).toFloat()
            if (since in 0f..0.6f) drawClearBurst(if (targets[i].action == DriveAction.BOOST) BOOST_COLOR else DRIFT_COLOR, since / 0.6f)
        }
    }

    if (highway != null) {
        drawNoteHighway(
            targets = highway,
            beatPosition = beat,
            lookaheadBeats = lookaheadBeats,
            hitFlash = hitFlash,
            laneY = squareTop + 0.29f * squareExtent,
            laneLeftX = squareLeft + 0.20f * squareExtent,
            laneRightX = squareLeft + 0.92f * squareExtent,
        )
    }
}

private fun hash01(i: Int, mul: Int, mod: Int): Float = (((i * mul + 11) % mod + mod) % mod) / mod.toFloat()

/**
 * The sky, mountains, distant city and roadside ground, laid out from the stage square so the
 * horizon sits where the road vanishes on any aspect ratio, while everything bleeds to the full
 * canvas. Stars twinkle and city windows blink on the beat — one timebase for the whole scene.
 */
private fun DrawScope.drawNightSky(squareLeft: Float, squareTop: Float, extent: Float, beat: Double) {
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
            colors = listOf(Color(0xFF07041C), Color(0xFF1E0B45), Color(0xFF5A1D6B), Color(0xFFB8436E)),
            startY = 0f,
            endY = horizon,
        ),
        size = Size(w, horizon),
    )
    for (i in 0 until 54) {
        val x = hash01(i, 211, 997) * w
        val y = horizon * 0.72f * hash01(i, 97, 541)
        val twinkle = (sin(t * 1.7 + i * 1.3) * 0.5 + 0.5).toFloat()
        drawCircle(Color.White.copy(alpha = 0.18f + 0.6f * twinkle), radius = (0.7f + (i % 3) * 0.45f) * k, center = Offset(x, y))
    }

    // A big crescent moon, inked like everything else.
    val moon = Offset(lx(88f), ly(62f))
    val moonR = 24f * k
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFD6F2).copy(alpha = 0.32f), Color(0xFFFFD6F2).copy(alpha = 0f)),
            center = moon,
            radius = moonR * 3.2f,
        ),
        radius = moonR * 3.2f,
        center = moon,
    )
    val crescent = Path.combine(
        PathOperation.Difference,
        Path().apply { addOval(Rect(center = moon, radius = moonR)) },
        Path().apply { addOval(Rect(center = moon + Offset(10f, -6f) * k, radius = moonR * 0.86f)) },
    )
    drawPath(crescent, Color(0xFFFFF1D6))
    drawPath(crescent, NIGHT_INK, style = Stroke(width = 2.5f * k, join = StrokeJoin.Round))

    // The city's glow and skyline in the valley where the road vanishes, windows blinking with the beat.
    val cityGlow = Offset(lx(200f), horizon)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFF8A5B).copy(alpha = 0.45f + 0.1f * pulse), Color(0xFFFF8A5B).copy(alpha = 0f)),
            center = cityGlow,
            radius = 150f * k,
        ),
        radius = 150f * k,
        center = cityGlow,
    )
    for (i in 0 until 16) {
        val bx = 118f + i * 10.5f
        val bh = 12f + 34f * hash01(i, 53, 29) * (1f - abs(i - 7.5f) / 12f)
        val bw = 9f
        drawRect(Color(0xFF2A1240), topLeft = Offset(lx(bx), horizon - bh * k), size = Size(bw * k, bh * k))
        var row = 0
        while (row * 5f + 4f < bh) {
            for (col in 0 until 2) {
                val lit = hash01(i * 7 + row * 3 + col + floor(t / 2).toInt() * 5, 31, 17) > 0.45f
                if (lit) {
                    drawRect(
                        Color(0xFFFFD98A).copy(alpha = 0.85f),
                        topLeft = Offset(lx(bx + 1.5f + col * 4f), horizon - (bh - 2f - row * 5f) * k),
                        size = Size(2f * k, 2f * k),
                    )
                }
            }
            row++
        }
    }

    // Two ranges of mountains framing the valley, inked along their ridges.
    drawRidge(
        squareLeft, horizon, k,
        peaks = listOf(-220f to 50f, -150f to 88f, -90f to 60f, -30f to 104f, 30f to 72f, 70f to 92f, 118f to 34f, 150f to 14f, 250f to 14f, 282f to 40f, 322f to 96f, 366f to 70f, 420f to 110f, 490f to 64f, 560f to 98f, 620f to 60f),
        top = Color(0xFF5E2A86),
        bottom = Color(0xFF2E1250),
    )
    drawRidge(
        squareLeft, horizon, k,
        peaks = listOf(-200f to 30f, -120f to 52f, -60f to 34f, 0f to 64f, 56f to 40f, 104f to 22f, 150f to 6f, 250f to 6f, 300f to 26f, 344f to 58f, 394f to 34f, 452f to 62f, 520f to 38f, 600f to 56f),
        top = Color(0xFF3A1762),
        bottom = Color(0xFF1C0B34),
    )

    drawRect(
        brush = Brush.verticalGradient(colors = listOf(Color(0xFF2A0F45), Color(0xFF0B0518)), startY = horizon, endY = h),
        topLeft = Offset(0f, horizon),
        size = Size(w, h - horizon),
    )

    // A neon grid on the ground: lines fanning out from the vanishing point, and cross lines rushing
    // toward the viewer once per beat, in step with the road's dashes.
    val vanish = Offset(lx(200f), horizon)
    val gridColor = Color(0xFFFF4FD8)
    for (i in -14..14) {
        val end = Offset(lx(200f + i * 90f), h)
        drawLine(gridColor.copy(alpha = 0.16f), vanish, end, strokeWidth = 1.2f * k)
    }
    val phase = if (beat < 0) 0.0 else beat - floor(beat)
    for (n in -1..8) {
        val ahead = n - phase
        if (ahead < -0.5) continue
        val y = ly(roadY(depthScale(ahead)))
        if (y > h) continue
        val fade = (1f - ahead.toFloat() / 8f).coerceIn(0f, 1f)
        drawLine(gridColor.copy(alpha = 0.22f * fade), Offset(0f, y), Offset(w, y), strokeWidth = 1.2f * k)
    }
}

/**
 * A mountain range: straight-sided peaks at (x, height) pairs, shaded from [top] down to [bottom]
 * at [horizon], with an inked ridge line and a rim of moonlight along it.
 */
private fun DrawScope.drawRidge(squareLeft: Float, horizon: Float, k: Float, peaks: List<Pair<Float, Float>>, top: Color, bottom: Color) {
    val ridge = Path()
    val fill = Path()
    // Stretch the outermost peaks to the canvas edges so the range bleeds into wide margins.
    val first = minOf(squareLeft + peaks.first().first * k, 0f)
    val last = maxOf(squareLeft + peaks.last().first * k, size.width)
    fill.moveTo(first, horizon)
    ridge.moveTo(first, horizon - peaks.first().second * k)
    fill.lineTo(first, horizon - peaks.first().second * k)
    for ((i, peak) in peaks.withIndex()) {
        val x = when (i) {
            0 -> first
            peaks.lastIndex -> last
            else -> squareLeft + peak.first * k
        }
        val y = horizon - peak.second * k
        ridge.lineTo(x, y)
        fill.lineTo(x, y)
    }
    fill.lineTo(last, horizon)
    fill.close()
    val highest = peaks.maxOf { it.second }
    drawPath(fill, brush = Brush.verticalGradient(listOf(top, bottom), startY = horizon - highest * k, endY = horizon))
    drawPath(ridge, Color(0xFFFF9BD6).copy(alpha = 0.35f), style = Stroke(width = 5f * k, join = StrokeJoin.Round))
    drawPath(ridge, NIGHT_INK, style = Stroke(width = 2.5f * k, join = StrokeJoin.Round))
}

/**
 * The road from the horizon down past the bottom of the canvas ([bottom], logical): asphalt with
 * neon edge lines, centre dashes and roadside lamps that rush past once per beat, so the car's
 * speed is locked to the song's tempo. A boost stretches the dashes into streaks.
 */
private fun DrawScope.drawRoad(beat: Double, bottom: Float, boostPulse: Float) {
    val sBottom = (bottom - HORIZON_Y) / (HIT_Y - HORIZON_Y)
    val halfBottom = ROAD_HALF * sBottom
    val road = Path().apply {
        moveTo(200f, HORIZON_Y)
        lineTo(200f + halfBottom, bottom)
        lineTo(200f - halfBottom, bottom)
        close()
    }
    drawPath(road, brush = Brush.verticalGradient(listOf(Color(0xFF2B1F42), Color(0xFF1A1328)), startY = HORIZON_Y, endY = bottom))
    for (side in listOf(-1f, 1f)) {
        val edgeTop = Offset(200f, HORIZON_Y)
        val edgeBottom = Offset(200f + side * halfBottom, bottom)
        drawLine(NIGHT_INK, edgeTop, edgeBottom, strokeWidth = 7f, cap = StrokeCap.Round)
        val lineColor = if (side < 0) BOOST_COLOR else DRIFT_COLOR
        val inset = Offset(200f + side * halfBottom * 0.94f, bottom)
        drawLine(lineColor.copy(alpha = 0.35f), edgeTop, inset, strokeWidth = 7f)
        drawLine(lineColor, edgeTop, inset, strokeWidth = 2.5f)
    }

    val phase = if (beat < 0) 0.0 else beat - floor(beat)
    val stretch = 0.4 + 0.35 * boostPulse
    // Lane dashes, one per beat, from just behind the car to the horizon.
    for (n in -1..6) {
        val near = n - phase
        val far = near + stretch
        if (far < -0.2) continue
        val s0 = depthScale(near.coerceAtLeast(-0.2))
        val s1 = depthScale(far)
        val dash = Path().apply {
            moveTo(200f - 3.5f * s0, roadY(s0))
            lineTo(200f + 3.5f * s0, roadY(s0))
            lineTo(200f + 3.5f * s1, roadY(s1))
            lineTo(200f - 3.5f * s1, roadY(s1))
            close()
        }
        drawPath(dash, Color(0xFFF4ECFF).copy(alpha = 0.85f))
    }
    // Roadside lamps, half a beat out of step with the dashes, far ones first.
    for (n in 6 downTo -1) {
        val ahead = n + 0.5 - phase
        if (ahead < -0.25) continue
        val s = depthScale(ahead)
        val y = roadY(s)
        for (side in listOf(-1f, 1f)) {
            val x = 200f + side * ROAD_HALF * 1.22f * s
            val top = y - 118f * s
            val color = if ((n + floor(beat.coerceAtLeast(0.0)).toInt()) % 2 == 0) BOOST_COLOR else DRIFT_COLOR
            drawLine(NIGHT_INK, Offset(x, y), Offset(x, top), strokeWidth = 5f * s, cap = StrokeCap.Round)
            drawLine(Color(0xFF6B5A88), Offset(x, y), Offset(x, top), strokeWidth = 2.2f * s)
            val lamp = Offset(x - side * 16f * s, top)
            drawLine(NIGHT_INK, Offset(x, top), lamp, strokeWidth = 5f * s, cap = StrokeCap.Round)
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)), center = lamp, radius = 26f * s),
                radius = 26f * s,
                center = lamp,
            )
            drawCircle(lerp(color, Color.White, 0.5f), radius = 4.5f * s, center = lamp)
            drawCircle(NIGHT_INK, radius = 4.5f * s, center = lamp, style = Stroke(width = 1.5f * s))
        }
    }
}

/** A diamond "curve ahead" sign by the right-hand roadside, its arrow pointing [dir], flashing on the beat. */
private fun DrawScope.drawCornerWarning(dir: Float, beat: Double) {
    val flash = beatBob(beat)
    val c = Offset(345f, 163f)
    val r = 20f + 3f * flash
    drawLine(NIGHT_INK, Offset(c.x, c.y + r), Offset(c.x, HORIZON_Y + 20f), strokeWidth = 5f, cap = StrokeCap.Round)
    drawLine(Color(0xFF8C7AA8), Offset(c.x, c.y + r), Offset(c.x, HORIZON_Y + 20f), strokeWidth = 2.5f)
    drawCircle(
        brush = Brush.radialGradient(listOf(SIGN_YELLOW.copy(alpha = 0.5f * flash), SIGN_YELLOW.copy(alpha = 0f)), center = c, radius = r * 2.2f),
        radius = r * 2.2f,
        center = c,
    )
    val diamond = Path().apply {
        moveTo(c.x, c.y - r)
        lineTo(c.x + r, c.y)
        lineTo(c.x, c.y + r)
        lineTo(c.x - r, c.y)
        close()
    }
    drawPath(diamond, lerp(SIGN_YELLOW, Color.White, 0.3f * flash))
    drawPath(diamond, NIGHT_INK, style = Stroke(width = 3f, join = StrokeJoin.Round))
    // A bent arrow: up the sign, then turning toward [dir].
    val arrow = Path().apply {
        moveTo(c.x - dir * 4f, c.y + 10f)
        lineTo(c.x - dir * 4f, c.y - 1f)
        quadraticTo(c.x - dir * 4f, c.y - 6f, c.x + dir * 2f, c.y - 6f)
        lineTo(c.x + dir * 6f, c.y - 6f)
    }
    drawPath(arrow, NIGHT_INK, style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val head = Path().apply {
        moveTo(c.x + dir * 11f, c.y - 6f)
        lineTo(c.x + dir * 4f, c.y - 12f)
        lineTo(c.x + dir * 4f, c.y)
        close()
    }
    drawPath(head, NIGHT_INK)
}

/**
 * One gate or corner on the road, [ahead] beats from reaching the car (negative once it's passed).
 * A boost gate is a neon arch over the road; a corner is a board of chevrons pointing [dir]. It
 * fades in far off, and once [result] is a clean hit it's gone (the car burst through it); a missed
 * one greys out as it rushes past.
 */
private fun DrawScope.drawRoadTarget(target: DriveTarget, ahead: Double, result: TossResult?, dir: Float) {
    if (result?.hit == true) return
    val s = depthScale(ahead)
    val y = roadY(s)
    val half = ROAD_HALF * s
    val fadeIn = ((ROAD_LOOKAHEAD_BEATS - ahead) / 0.6).toFloat().coerceIn(0f, 1f)
    // Once past the car it fades out fast as it rushes by, rather than looming over everything.
    val fadeOut = (1.0 + ahead / PASS_BEATS).toFloat().coerceIn(0f, 1f)
    val missed = result != null
    val alpha = fadeIn * fadeOut * if (missed) 0.5f else 1f
    val base = if (target.action == DriveAction.BOOST) BOOST_COLOR else DRIFT_COLOR
    val color = if (missed) lerp(base, Color(0xFF7A7488), 0.75f) else base

    // A glowing line across the road where the target sits.
    drawLine(color.copy(alpha = 0.3f * alpha), Offset(200f - half, y), Offset(200f + half, y), strokeWidth = 9f * s)
    drawLine(color.copy(alpha = alpha), Offset(200f - half, y), Offset(200f + half, y), strokeWidth = 2.5f * s)

    if (target.action == DriveAction.BOOST) {
        val left = 200f - half * 1.06f
        val right = 200f + half * 1.06f
        val top = y - 112f * s
        val arch = Path().apply {
            moveTo(left, y)
            lineTo(left, top + 16f * s)
            quadraticTo(left, top, left + 16f * s, top)
            lineTo(right - 16f * s, top)
            quadraticTo(right, top, right, top + 16f * s)
            lineTo(right, y)
        }
        drawPath(arch, NIGHT_INK.copy(alpha = alpha), style = Stroke(width = 11f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(arch, color.copy(alpha = 0.4f * alpha), style = Stroke(width = 16f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(arch, color.copy(alpha = alpha), style = Stroke(width = 6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(arch, Color.White.copy(alpha = 0.8f * alpha), style = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Double chevrons on the crossbar, pointing onward down the road.
        for (i in 0..1) {
            val cy = top + (4f + i * 7f) * s
            val chevron = Path().apply {
                moveTo(200f - 12f * s, cy + 6f * s)
                lineTo(200f, cy)
                lineTo(200f + 12f * s, cy + 6f * s)
            }
            drawPath(chevron, NIGHT_INK.copy(alpha = alpha), style = Stroke(width = 6f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(chevron, lerp(color, Color.White, 0.5f).copy(alpha = alpha), style = Stroke(width = 3f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    } else {
        val boardTop = y - 74f * s
        val boardBottom = y - 42f * s
        val left = 200f - half * 0.95f
        val right = 200f + half * 0.95f
        for (x in listOf(left + 10f * s, right - 10f * s)) {
            drawLine(NIGHT_INK.copy(alpha = alpha), Offset(x, y), Offset(x, boardBottom), strokeWidth = 6f * s, cap = StrokeCap.Round)
            drawLine(Color(0xFF8C7AA8).copy(alpha = alpha), Offset(x, y), Offset(x, boardBottom), strokeWidth = 3f * s)
        }
        val corner = CornerRadius(6f * s)
        drawRoundRect(Color(0xFF1C0B2E).copy(alpha = alpha), topLeft = Offset(left, boardTop), size = Size(right - left, boardBottom - boardTop), cornerRadius = corner)
        drawRoundRect(color.copy(alpha = alpha), topLeft = Offset(left, boardTop), size = Size(right - left, boardBottom - boardTop), cornerRadius = corner, style = Stroke(width = 4f * s))
        drawRoundRect(NIGHT_INK.copy(alpha = alpha), topLeft = Offset(left - 2f * s, boardTop - 2f * s), size = Size(right - left + 4f * s, boardBottom - boardTop + 4f * s), cornerRadius = corner, style = Stroke(width = 2f * s))
        // Hairpin chevrons pointing the way the corner turns.
        val chevronColor = if (missed) color else SIGN_YELLOW
        val cy = (boardTop + boardBottom) / 2f
        val count = 5
        for (i in 0 until count) {
            val cx = left + (right - left) * (i + 0.5f) / count
            val chevron = Path().apply {
                moveTo(cx - dir * 6f * s, cy - 10f * s)
                lineTo(cx + dir * 6f * s, cy)
                lineTo(cx - dir * 6f * s, cy + 10f * s)
            }
            drawPath(chevron, chevronColor.copy(alpha = alpha), style = Stroke(width = 5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Sparks flying out around the car as it clears a gate or corner, [progress] going 0→1 as they fade. */
private fun DrawScope.drawClearBurst(color: Color, progress: Float) {
    val center = Offset(200f, HIT_Y - 50f)
    val fade = 1f - progress
    for (i in 0 until 12) {
        val a = (i * 30f + 15f) * PI.toFloat() / 180f
        val dir = Offset(cos(a), sin(a) * 0.6f)
        val inner = 70f + 60f * progress
        val outer = inner + 18f * fade
        drawLine(color.copy(alpha = fade), center + dir * inner, center + dir * outer, strokeWidth = 1f + 4f * fade, cap = StrokeCap.Round)
    }
    withTransform({ scale(1f, 0.5f, center) }) {
        drawCircle(Color.White.copy(alpha = 0.6f * fade), radius = 60f + 90f * progress, center = center, style = Stroke(width = 3f * fade))
    }
}

private val CAR_RED_LIT = Color(0xFFFF6B78)
private val CAR_RED = Color(0xFFD9233F)
private val CAR_RED_DARK = Color(0xFF7E1026)
private val TAIL_LIGHT = Color(0xFFFF2E4D)

/**
 * The player's car seen from behind, standing on the road at [ground]: an inked red coupe with a
 * spoiler and glowing tail lights that pulse on the beat, bouncing on its suspension. [boostPulse]
 * shoots a flame from the exhaust and lurches it forward; [driftPulse] swings it sideways toward
 * [driftDir] with tyre smoke pouring off the rear wheels.
 */
private fun DrawScope.drawCar(ground: Offset, beat: Double, boostPulse: Float, driftPulse: Float, driftDir: Float) {
    val bob = beatBob(beat)
    // Out and back: the slide peaks early and eases home as the pulse decays.
    val slide = sin(driftPulse.toDouble().pow(0.6) * PI).toFloat() * driftPulse.coerceAtMost(1f)
    val yaw = driftDir * 16f * slide
    val shift = driftDir * 26f * slide

    withTransform({ translate(ground.x + shift, ground.y) }) {
        // Tyre smoke first, so it billows out from behind the car.
        if (driftPulse > 0f) {
            val age = 1f - driftPulse
            for (i in 0 until 6) {
                val side = if (i % 2 == 0) -1f else 1f
                val drift = age * (30f + i * 9f)
                val puff = Offset(side * 58f - driftDir * drift, -8f - drift * 0.5f - i * 3f)
                val r = 10f + 26f * age + i * 2f
                drawCircle(Color(0xFFE9E1F5).copy(alpha = 0.5f * driftPulse), radius = r, center = puff)
                drawCircle(NIGHT_INK.copy(alpha = 0.25f * driftPulse), radius = r, center = puff, style = Stroke(width = 2f))
            }
        }

        val shadowCenter = Offset(0f, 2f)
        withTransform({ scale(1f, 0.16f, shadowCenter) }) {
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0f)), center = shadowCenter, radius = 90f),
                radius = 90f,
                center = shadowCenter,
            )
        }

        withTransform({
            rotate(yaw, Offset(0f, -30f))
            // Lurch forward (away, so smaller) on a boost, and bounce on the beat.
            scale(1f - 0.05f * boostPulse, 1f - 0.05f * boostPulse, Offset(0f, 0f))
            translate(0f, -2.5f * bob - 3f * boostPulse)
        }) {
            drawCarBody(tailGlow = 0.35f + 0.65f * bob, boostPulse = boostPulse)
        }
    }
}

/** The coupe itself, standing on (0, 0); authored for the scene, and reused at a smaller size for the menu emblem. */
private fun DrawScope.drawCarBody(tailGlow: Float, boostPulse: Float) {
    for (x in listOf(-66f, 42f)) {
        drawRoundRect(Color(0xFF15121C), topLeft = Offset(x, -28f), size = Size(24f, 30f), cornerRadius = CornerRadius(6f))
        drawRoundRect(NIGHT_INK, topLeft = Offset(x, -28f), size = Size(24f, 30f), cornerRadius = CornerRadius(6f), style = Stroke(width = 3f))
        drawLine(Color(0xFF4A4458), Offset(x + 6f, -22f), Offset(x + 6f, -4f), strokeWidth = 2f, cap = StrokeCap.Round)
    }

    // The cabin and rear window, behind the body's top edge.
    val cabin = Path().apply {
        moveTo(-52f, -56f)
        lineTo(-39f, -92f)
        quadraticTo(0f, -97f, 39f, -92f)
        lineTo(52f, -56f)
        close()
    }
    drawPath(cabin, brush = Brush.verticalGradient(listOf(CAR_RED, CAR_RED_DARK), startY = -96f, endY = -56f))
    drawPath(cabin, NIGHT_INK, style = Stroke(width = 3.5f, join = StrokeJoin.Round))
    val window = Path().apply {
        moveTo(-43f, -60f)
        lineTo(-33f, -87f)
        quadraticTo(0f, -91f, 33f, -87f)
        lineTo(43f, -60f)
        close()
    }
    drawPath(window, brush = Brush.verticalGradient(listOf(Color(0xFF5B6FB8), Color(0xFF1A1F3E)), startY = -90f, endY = -60f))
    val glare = Path().apply {
        moveTo(-24f, -87f)
        lineTo(-12f, -88f)
        lineTo(-26f, -61f)
        lineTo(-36f, -61f)
        close()
    }
    drawPath(glare, Color.White.copy(alpha = 0.35f))
    drawPath(window, NIGHT_INK, style = Stroke(width = 2.5f, join = StrokeJoin.Round))

    // The body.
    val bodyTopLeft = Offset(-74f, -60f)
    val bodySize = Size(148f, 48f)
    val bodyCorner = CornerRadius(14f)
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(CAR_RED_LIT, CAR_RED, CAR_RED_DARK), startY = -60f, endY = -12f),
        topLeft = bodyTopLeft,
        size = bodySize,
        cornerRadius = bodyCorner,
    )
    drawLine(Color.White.copy(alpha = 0.55f), Offset(-60f, -55f), Offset(60f, -55f), strokeWidth = 2.5f, cap = StrokeCap.Round)
    drawRoundRect(NIGHT_INK, topLeft = bodyTopLeft, size = bodySize, cornerRadius = bodyCorner, style = Stroke(width = 3.5f))

    // Bumper, plate and exhaust.
    drawRoundRect(Color(0xFF2A2230), topLeft = Offset(-70f, -24f), size = Size(140f, 14f), cornerRadius = CornerRadius(6f))
    drawRoundRect(NIGHT_INK, topLeft = Offset(-70f, -24f), size = Size(140f, 14f), cornerRadius = CornerRadius(6f), style = Stroke(width = 2.5f))
    drawRoundRect(Color(0xFFF4E9C8), topLeft = Offset(-17f, -42f), size = Size(34f, 14f), cornerRadius = CornerRadius(3f))
    drawRoundRect(NIGHT_INK, topLeft = Offset(-17f, -42f), size = Size(34f, 14f), cornerRadius = CornerRadius(3f), style = Stroke(width = 2f))
    for (x in listOf(-10f, -3f, 4f, 11f)) drawLine(NIGHT_INK.copy(alpha = 0.7f), Offset(x - 1.5f, -38f), Offset(x - 1.5f, -32f), strokeWidth = 2f, cap = StrokeCap.Round)
    val pipe = Offset(-46f, -13f)
    drawCircle(Color(0xFFB7BFCA), radius = 5.5f, center = pipe)
    drawCircle(Color(0xFF221C2A), radius = 3f, center = pipe)
    drawCircle(NIGHT_INK, radius = 5.5f, center = pipe, style = Stroke(width = 2f))

    // Tail lights, glowing brighter on each beat.
    for (x in listOf(-68f, 34f)) {
        val light = Rect(x, -52f, x + 34f, -40f)
        drawCircle(
            brush = Brush.radialGradient(listOf(TAIL_LIGHT.copy(alpha = 0.55f * tailGlow), TAIL_LIGHT.copy(alpha = 0f)), center = light.center, radius = 34f),
            radius = 34f,
            center = light.center,
        )
        drawRoundRect(lerp(TAIL_LIGHT, Color.White, 0.45f * tailGlow), topLeft = light.topLeft, size = light.size, cornerRadius = CornerRadius(4f))
        drawRoundRect(NIGHT_INK, topLeft = light.topLeft, size = light.size, cornerRadius = CornerRadius(4f), style = Stroke(width = 2.5f))
    }

    // The spoiler, on two struts over the boot.
    for (x in listOf(-44f, 44f)) drawLine(NIGHT_INK, Offset(x, -58f), Offset(x, -72f), strokeWidth = 5f, cap = StrokeCap.Round)
    drawRoundRect(Color(0xFF1C1824), topLeft = Offset(-78f, -80f), size = Size(156f, 10f), cornerRadius = CornerRadius(4f))
    drawLine(Color(0xFF5A5268), Offset(-72f, -77.5f), Offset(72f, -77.5f), strokeWidth = 1.8f, cap = StrokeCap.Round)
    drawRoundRect(NIGHT_INK, topLeft = Offset(-78f, -80f), size = Size(156f, 10f), cornerRadius = CornerRadius(4f), style = Stroke(width = 2.5f))

    // A boost's exhaust flame, bursting toward the viewer.
    if (boostPulse > 0f) {
        val p = boostPulse
        val flame = pipe + Offset(-8f * p, 16f * p)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFFFF7A2A).copy(alpha = 0.7f * p), Color(0xFFFF7A2A).copy(alpha = 0f)), center = flame, radius = 46f * p),
            radius = 46f * p,
            center = flame,
        )
        drawCircle(Color(0xFFFF5E2A), radius = 24f * p, center = flame)
        drawCircle(NIGHT_INK, radius = 24f * p, center = flame, style = Stroke(width = 2.5f * p))
        drawCircle(Color(0xFFFFB33A), radius = 15f * p, center = flame + Offset(2f, -4f) * p)
        drawCircle(Color(0xFFFFF3B8), radius = 7f * p, center = flame + Offset(3f, -6f) * p)
    }
}

/** The main menu's Night Drift emblem: the car from behind, bouncing on the beat with its tail lights pulsing. */
internal fun DrawScope.drawNightDriftEmblem(beat: Double) {
    val s = size.minDimension / 100f * 0.64f
    val pulse = beatBob(beat)
    withTransform({
        // The car stands about 98 units tall, so this centres it in the emblem.
        translate(size.width / 2f, size.height / 2f + 48f * s)
        scale(s, s, Offset.Zero)
        translate(0f, -3f * pulse)
    }) {
        val shadowCenter = Offset(0f, 2f)
        withTransform({ scale(1f, 0.16f, shadowCenter) }) {
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0f)), center = shadowCenter, radius = 90f),
                radius = 90f,
                center = shadowCenter,
            )
        }
        drawCarBody(tailGlow = 0.35f + 0.65f * pulse, boostPulse = pulse * 0.8f)
    }
}
