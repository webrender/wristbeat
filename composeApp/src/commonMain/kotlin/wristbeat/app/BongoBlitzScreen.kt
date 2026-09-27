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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
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
    // How this run compared with the song's saved best, once it has finished.
    var record by remember { mutableStateOf<RecordResult?>(null) }
    ReportRunning(started && !finished, onRunningChanged)
    // Highway: the player's upcoming response beats, dots for the high drum and swipe arrows for the low one.
    val highwayTargets = remember(stage) { stage.targets.map { bongoBlitzHighwayNote(it.beat, lowDrum = it.action == DrumAction.SWIPE) } }

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

            if (stage.isFinished(beat)) {
                finished = true
                record = submitScore(Stage.BONGO_BLITZ, tally.percent)
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
                    val beatAtDown = beatAt(down.uptimeMillis)
                    val result = press(beatAtDown)
                    if (result == BongoPressResult.CONSUMED) return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        val delta = change.position - down.position
                        if (delta.getDistance() >= swipeDistance) {
                            act(DrumAction.SWIPE, beatAt(change.uptimeMillis))
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
            drawBongoBlitzScene(
                beat = beatPosition,
                leadHiPulse = leadHiPulse,
                leadLoPulse = leadLoPulse,
                playerHiPulse = playerHiPulse,
                playerLoPulse = playerLoPulse,
                playerLoSwipeFlash = playerLoSwipeFlash,
                highway = highwayTargets.takeIf { chart.on },
                // 3.7 beats at BONGO_BLITZ_BPM is about the same 1.29s look-ahead as Snap Crabs'
                // 2.5 beats at 116 BPM and Mango Chop's 3.0 at 140 BPM, so the scroll speed matches.
                lookaheadBeats = 3.7,
                hitFlash = maxOf(beatFlashPhase * 0.5f, targetPulsePhase),
            )
        }

        StreakBadge(tally.streak, beat = { beatPosition }, visible = !finished)

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

/** A response note on the highway: a dot for the high drum, a swipe arrow for the low one. */
internal fun bongoBlitzHighwayNote(beat: Double, lowDrum: Boolean): HighwayNote =
    if (lowDrum) HighwayNote(beat, LO_NOTE_COLOR, 9f, NoteShape.SWIPE) else HighwayNote(beat, HI_NOTE_COLOR, 8f)

/**
 * The whole Bongo Blitz scene filling the canvas: the torchlit jungle, the note highway
 * ([highway], or none when the chart is hidden), the torches and both monkeys at their bongos.
 * Shared by [BongoBlitzScreen] and Remix 1's monkey third.
 */
internal fun DrawScope.drawBongoBlitzScene(
    beat: Double,
    leadHiPulse: Float,
    leadLoPulse: Float,
    playerHiPulse: Float,
    playerLoPulse: Float,
    playerLoSwipeFlash: Float,
    highway: List<HighwayNote>?,
    lookaheadBeats: Double,
    hitFlash: Float,
) {
    val squareExtent = minOf(size.width, size.height)
    val squareLeft = (size.width - squareExtent) / 2f
    val squareTop = (size.height - squareExtent) / 2f
    val k = squareExtent / 400f

    drawJungleBackground(squareLeft, squareTop, squareExtent, beat)

    if (highway != null) {
        drawNoteHighway(
            targets = highway,
            beatPosition = beat,
            lookaheadBeats = lookaheadBeats,
            hitFlash = hitFlash,
            laneY = squareTop + 0.30f * squareExtent,
            laneLeftX = squareLeft + 0.20f * squareExtent,
            laneRightX = squareLeft + 0.92f * squareExtent,
        )
    }

    withTransform({
        translate(squareLeft, squareTop)
        scale(k, k, Offset.Zero)
    }) {
        drawTorch(Offset(26f, 274f), beat, seed = 0f)
        drawTorch(Offset(374f, 274f), beat, seed = 2.3f)
        val bob = beatBob(beat) * 4f
        // The lead monkey calls from the left; the player's monkey answers on its own bongos
        // on the right, the same lead/player pairing as Snap Crabs' two crabs.
        drawDrummer(Offset(110f, 300f), LEAD_DRUMMER, leadHiPulse, leadLoPulse, leadLoPulse, bob, gaze = 1f)
        drawDrummer(Offset(290f, 300f), PLAYER_DRUMMER, playerHiPulse, playerLoPulse, playerLoSwipeFlash, bob, gaze = -1f)
    }
}

/** Beat-synced bounce: peaks right after the beat and eases out (see [SnapCrabsScreen]'s same shape). */
private fun beatBob(beat: Double): Float {
    if (beat < 0) return 0f
    val f = beat - floor(beat)
    return (1f - f.toFloat()).pow(3)
}


/** Where the jungle floor meets the treeline, in the stage's 400×400 logical space. */
private const val GROUND_Y = 264f

private val OUTLINE = Color(0xFF2A1708)
private val WOOD_LIT = Color(0xFFC0814A)
private val WOOD_MID = Color(0xFF8E5529)
private val WOOD_SHADE = Color(0xFF5A3216)
private val CHROME_LIT = Color(0xFFE4EAEF)
private val CHROME_DARK = Color(0xFF66717C)
private val DRUM_SKIN = Color(0xFFF3E1C0)

/** One monkey's palette, plus the paint on its bongos and the color its drum skins flash when struck. */
private class Drummer(
    val fur: Color,
    val face: Color,
    val band: Color,
    val bandTrim: Color,
    val hitColor: Color,
    val headband: Color?,
    val tailSide: Float,
)

private val LEAD_DRUMMER = Drummer(
    fur = Color(0xFF7A4A26),
    face = Color(0xFFF0C08A),
    band = Color(0xFFE4513A),
    bandTrim = Color(0xFFFFC857),
    hitColor = Color(0xFFFFD166),
    headband = Color(0xFFE4513A),
    tailSide = -1f,
)

private val PLAYER_DRUMMER = Drummer(
    fur = Color(0xFFB26B34),
    face = Color(0xFFF7D6A6),
    band = HI_NOTE_COLOR,
    bandTrim = Color(0xFFF3E1FF),
    hitColor = Color(0xFFD9A6FF),
    headband = null,
    tailSide = 1f,
)

// Each bongo pair, relative to the drummer's origin (the skins' centre line): the small macho on
// the left is the high drum (tap), the larger hembra on the right the low drum (swipe).
private const val HI_X = -24f
private const val HI_R = 20f
private const val HI_H = 28f
private const val LO_X = 26f
private const val LO_R = 26f
private const val LO_H = 32f

private fun hash01(i: Int, mul: Int, mod: Int): Float = (((i * mul + 11) % mod + mod) % mod) / mod.toFloat()

/**
 * A torchlit jungle clearing at night: starry sky and moon, two layers of treeline silhouette,
 * hanging leaves in the top corners and a warm pool of light on the ground under the drummers.
 * Laid out from the stage square (like Mango Chop's market) so the treeline sits behind the
 * monkeys on any aspect ratio, while sky, trees and ground bleed to the full canvas. Fireflies,
 * stars and leaves move with the beat rather than a separate clock, so the scene has one timebase.
 */
private fun DrawScope.drawJungleBackground(squareLeft: Float, squareTop: Float, extent: Float, beat: Double) {
    val w = size.width
    val h = size.height
    val k = extent / 400f
    fun lx(x: Float) = squareLeft + x * k
    fun ly(y: Float) = squareTop + y * k
    val horizon = ly(GROUND_Y)
    val t = beat.coerceAtLeast(0.0)
    val pulse = beatBob(beat)

    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF0B0622), Color(0xFF211548), Color(0xFF4A2868)),
            startY = 0f,
            endY = horizon,
        ),
        size = Size(w, horizon),
    )

    for (i in 0 until 48) {
        val x = hash01(i, 211, 997) * w
        val y = horizon * 0.7f * hash01(i, 97, 541)
        val twinkle = (sin(t * 1.3 + i * 1.7) * 0.5 + 0.5).toFloat()
        drawCircle(Color.White.copy(alpha = 0.2f + 0.55f * twinkle), radius = (0.8f + (i % 3) * 0.45f) * k, center = Offset(x, y))
    }

    val moon = Offset(lx(292f), ly(60f))
    val moonR = 22f * k
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFEDE4FF).copy(alpha = 0.3f), Color(0xFFEDE4FF).copy(alpha = 0f)),
            center = moon,
            radius = moonR * 3.4f,
        ),
        radius = moonR * 3.4f,
        center = moon,
    )
    drawCircle(Color(0xFFF7F1DE), radius = moonR, center = moon)
    drawCircle(Color(0xFFE2D9BE), radius = 5f * k, center = moon + Offset(-7f, -5f) * k)
    drawCircle(Color(0xFFE2D9BE), radius = 3.5f * k, center = moon + Offset(8f, 6f) * k)
    drawCircle(Color(0xFFE2D9BE), radius = 2.5f * k, center = moon + Offset(-2f, 11f) * k)

    // Palms stand out in the side margins of wide screens, where the square stage leaves room.
    val sway = (sin(t * PI) * 4.0).toFloat()
    drawPalm(Offset(lx(-70f), horizon), k, lean = 26f, sway = sway, color = Color(0xFF151A3C))
    drawPalm(Offset(lx(470f), horizon), k, lean = -26f, sway = -sway, color = Color(0xFF151A3C))

    drawTreeline(squareLeft, horizon, k, step = 28f, baseLift = 18f, bumpLift = 22f, radius = 26f, color = Color(0xFF1C1F45))
    drawTreeline(squareLeft, horizon, k, step = 22f, baseLift = 4f, bumpLift = 9f, radius = 17f, color = Color(0xFF0F271E))

    // Fireflies hover low over the treeline, blinking in and out.
    for (i in 0 until 12) {
        val x = hash01(i, 173, 977) * w + (sin(t * 0.6 + i * 6.2) * 8.0).toFloat() * k
        val y = horizon - (20f + 70f * hash01(i, 89, 613)) * k + (cos(t * 0.5 + i) * 5.0).toFloat() * k
        val blink = (sin(t * 1.8 + i * 12.3) * 0.5 + 0.5).toFloat()
        drawCircle(Color(0xFFE7FF7A).copy(alpha = 0.22f * blink), radius = 6f * k, center = Offset(x, y))
        drawCircle(Color(0xFFF4FFC0).copy(alpha = 0.3f + 0.6f * blink), radius = 1.8f * k, center = Offset(x, y))
    }

    drawRect(
        brush = Brush.verticalGradient(colors = listOf(Color(0xFF41281A), Color(0xFF1E120A)), startY = horizon, endY = h),
        topLeft = Offset(0f, horizon),
        size = Size(w, h - horizon),
    )
    // The torches' warm pool of light on the ground, brightening a touch on every beat.
    val poolCenter = Offset(lx(200f), ly(322f))
    withTransform({ scale(1f, 0.3f, poolCenter) }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFF9D4D).copy(alpha = 0.3f + 0.08f * pulse), Color(0xFFFF9D4D).copy(alpha = 0f)),
                center = poolCenter,
                radius = 250f * k,
            ),
            radius = 250f * k,
            center = poolCenter,
        )
    }
    // A fringe of grass tufts along the edge of the clearing.
    val grass = Path()
    var i = -((squareLeft / (9f * k)).toInt() + 2)
    while (lx(i * 9f) < w + 10f * k) {
        val x = lx(i * 9f)
        grass.moveTo(x - 5f * k, horizon + 3f * k)
        grass.lineTo(x - 1f * k, horizon - (5f + 7f * hash01(i, 37, 17)) * k)
        grass.lineTo(x + 5f * k, horizon + 3f * k)
        grass.close()
        i++
    }
    drawPath(grass, Color(0xFF0F271E))

    val leafSway = (sin(t * PI) * 3.0).toFloat()
    drawCornerLeaves(Offset(0f, 0f), 1f, k, leafSway)
    drawCornerLeaves(Offset(w, 0f), -1f, k, -leafSway)
}

/** A row of overlapping canopy bumps resting on [horizon], filled down to it, seeded per bump so it doesn't shimmer. */
private fun DrawScope.drawTreeline(
    squareLeft: Float,
    horizon: Float,
    k: Float,
    step: Float,
    baseLift: Float,
    bumpLift: Float,
    radius: Float,
    color: Color,
) {
    val trees = Path()
    var i = -((squareLeft / (step * k)).toInt() + 2)
    while (squareLeft + (i - 1) * step * k < size.width) {
        val r = radius * (0.75f + 0.5f * hash01(i, 37, 17)) * k
        val cx = squareLeft + i * step * k
        val cy = horizon - (baseLift + bumpLift * hash01(i, 53, 23)) * k
        trees.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
        i++
    }
    trees.addRect(Rect(0f, horizon - (baseLift + 2f) * k, size.width, horizon + 1f))
    drawPath(trees, color)
}

private fun DrawScope.drawPalm(base: Offset, k: Float, lean: Float, sway: Float, color: Color) {
    val top = Offset(base.x + lean * k, base.y - 150f * k)
    val trunk = Path().apply {
        moveTo(base.x, base.y)
        quadraticTo(base.x + lean * 0.1f * k, base.y - 80f * k, top.x, top.y)
    }
    drawPath(trunk, color, style = Stroke(width = 8f * k, cap = StrokeCap.Round))
    for (deg in listOf(-170f, -140f, -105f, -75f, -40f, -10f)) {
        val a = (deg + sway) * PI.toFloat() / 180f
        drawLeaf(top, Offset(cos(a) * 60f * k, sin(a) * 60f * k + 26f * k), 7f * k, color, null)
    }
}

/** Big leaves fanning down out of a top corner of the canvas, swaying with the beat. */
private fun DrawScope.drawCornerLeaves(anchor: Offset, dir: Float, k: Float, sway: Float) {
    val leaves = listOf(
        Triple(20f, 70f, Color(0xFF1D4A30)),
        Triple(52f, 62f, Color(0xFF163D27)),
        Triple(82f, 52f, Color(0xFF1D4A30)),
    )
    for ((deg, len, fill) in leaves) {
        val a = (if (dir > 0) deg + sway else 180f - deg + sway) * PI.toFloat() / 180f
        drawLeaf(anchor, Offset(cos(a), sin(a)) * (len * k), len * 0.26f * k, fill, Color(0xFF2F7048))
    }
}

/** A pointed leaf from [base] to base + [reach], [halfWidth] wide at its fullest, with an optional midrib. */
private fun DrawScope.drawLeaf(base: Offset, reach: Offset, halfWidth: Float, fill: Color, rib: Color?) {
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
    drawPath(leaf, fill)
    if (rib != null) drawLine(rib, base, base + reach * 0.9f, strokeWidth = halfWidth * 0.12f, cap = StrokeCap.Round)
}

/** A bamboo tiki torch standing on the ground at [base], its flame flaring on every beat. */
private fun DrawScope.drawTorch(base: Offset, beat: Double, seed: Float) {
    val t = beat.coerceAtLeast(0.0)
    val pulse = beatBob(beat)
    val flameBase = Offset(base.x, base.y - 92f)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFA24A).copy(alpha = 0.3f + 0.2f * pulse), Color(0xFFFFA24A).copy(alpha = 0f)),
            center = flameBase,
            radius = 60f,
        ),
        radius = 60f,
        center = flameBase,
    )

    drawRoundRect(Color(0xFF9C7640), topLeft = Offset(base.x - 3.5f, base.y - 82f), size = Size(7f, 82f), cornerRadius = CornerRadius(2f))
    drawRoundRect(OUTLINE, topLeft = Offset(base.x - 3.5f, base.y - 82f), size = Size(7f, 82f), cornerRadius = CornerRadius(2f), style = Stroke(width = 2f))
    for (y in listOf(20f, 42f, 64f)) {
        drawLine(OUTLINE.copy(alpha = 0.7f), Offset(base.x - 3.5f, base.y - y), Offset(base.x + 3.5f, base.y - y), strokeWidth = 2f)
    }
    val bowl = Path().apply {
        moveTo(base.x - 11f, base.y - 93f)
        lineTo(base.x + 11f, base.y - 93f)
        lineTo(base.x + 6f, base.y - 80f)
        lineTo(base.x - 6f, base.y - 80f)
        close()
    }
    drawPath(bowl, Color(0xFF6B4524))
    drawLine(Color(0xFFC8A060), Offset(base.x - 9f, base.y - 88f), Offset(base.x + 9f, base.y - 88f), strokeWidth = 1.6f)
    drawPath(bowl, OUTLINE, style = Stroke(width = 2f, join = StrokeJoin.Round))

    val flicker = 1f + 0.25f * pulse + 0.07f * sin(t * 7.3 + seed).toFloat()
    val lean = 2.5f * sin(t * 4.1 + seed).toFloat()
    drawFlame(flameBase, 28f * flicker, 11f, lean, Color(0xFFFF5E2A))
    drawFlame(flameBase, 19f * flicker, 7.5f, lean * 0.7f, Color(0xFFFFB33A))
    drawFlame(flameBase, 10f * flicker, 4f, lean * 0.4f, Color(0xFFFFF3B8))

    for (i in 0 until 3) {
        val life = ((t * 1.1 + i / 3.0 + seed) % 1.0).toFloat()
        val ember = flameBase + Offset(sin(life * 9f + i * 2f + seed) * 5f, -30f - life * 34f)
        drawCircle(Color(0xFFFFC266).copy(alpha = 1f - life), radius = 1.6f, center = ember)
    }
}

private fun DrawScope.drawFlame(bottom: Offset, height: Float, halfWidth: Float, lean: Float, color: Color) {
    val flame = Path().apply {
        moveTo(bottom.x + lean, bottom.y - height)
        cubicTo(bottom.x + halfWidth, bottom.y - height * 0.45f, bottom.x + halfWidth, bottom.y, bottom.x, bottom.y)
        cubicTo(bottom.x - halfWidth, bottom.y, bottom.x - halfWidth, bottom.y - height * 0.45f, bottom.x + lean, bottom.y - height)
        close()
    }
    drawPath(flame, color)
}

/**
 * A monkey seated behind a pair of bongos, at [pos] (the skins' centre line). [hiPulse] and
 * [loPulse] bring a paw down on the high or low drum and flash that skin; [swipeFlash] sweeps the
 * low-drum paw across the skin with a claw-swipe streak, since the low drum is the swipe drum.
 * The body bobs with the beat while the drums stay put, so the arms flex to keep the paws on them.
 */
private fun DrawScope.drawDrummer(
    pos: Offset,
    drummer: Drummer,
    hiPulse: Float,
    loPulse: Float,
    swipeFlash: Float,
    bob: Float,
    gaze: Float,
) {
    withTransform({ translate(pos.x, pos.y) }) {
        val shadowCenter = Offset(0f, 38f)
        withTransform({ scale(1f, 0.14f, shadowCenter) }) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0f)),
                    center = shadowCenter,
                    radius = 76f,
                ),
                radius = 76f,
                center = shadowCenter,
            )
        }

        for (side in listOf(-1f, 1f)) drawFoot(Offset(side * 60f, 28f), drummer)

        withTransform({ translate(0f, bob) }) {
            drawTail(drummer)
            drawOval(drummer.fur, topLeft = Offset(-28f, -70f), size = Size(56f, 66f))
            drawOval(OUTLINE, topLeft = Offset(-28f, -70f), size = Size(56f, 66f), style = Stroke(width = 3f))
            drawOval(lerp(drummer.face, drummer.fur, 0.2f), topLeft = Offset(-17f, -58f), size = Size(34f, 46f))
            drawMonkeyHead(drummer, mouthOpen = maxOf(hiPulse, loPulse), gaze = gaze, bob = bob)
        }

        drawRoundRect(WOOD_SHADE, topLeft = Offset(-12f, 6f), size = Size(22f, 15f), cornerRadius = CornerRadius(3f))
        drawRoundRect(OUTLINE, topLeft = Offset(-12f, 6f), size = Size(22f, 15f), cornerRadius = CornerRadius(3f), style = Stroke(width = 2f))
        drawBongo(HI_X, HI_R, HI_H, hiPulse, drummer)
        drawBongo(LO_X, LO_R, LO_H, loPulse, drummer)

        // Paws rest raised over each drum and slam onto the skin on a hit, staying down briefly.
        val hiContact = (hiPulse * 1.6f).coerceAtMost(1f)
        val loContact = (loPulse * 1.6f).coerceAtMost(1f)
        val swipeX = if (swipeFlash > 0f) (-14f + 28f * (1f - swipeFlash)) * loContact else 0f
        drawArm(Offset(-19f, -56f + bob), Offset(HI_X, -28f + 25f * hiContact), -1f, drummer)
        drawArm(Offset(19f, -56f + bob), Offset(LO_X + swipeX, -30f + 27f * loContact), 1f, drummer)

        drawHitBurst(Offset(HI_X, -2f), HI_R, hiPulse, drummer.hitColor)
        drawSwipeHit(Offset(LO_X, 0f), LO_R, swipeFlash)
    }
}

private fun DrawScope.drawFoot(center: Offset, drummer: Drummer) {
    drawOval(drummer.face, topLeft = center - Offset(11f, 6f), size = Size(22f, 12f))
    drawOval(OUTLINE, topLeft = center - Offset(11f, 6f), size = Size(22f, 12f), style = Stroke(width = 2.5f))
    for (dx in listOf(-3.5f, 3.5f)) {
        drawLine(OUTLINE.copy(alpha = 0.6f), center + Offset(dx, -5f), center + Offset(dx, -1.5f), strokeWidth = 1.5f, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawTail(drummer: Drummer) {
    val s = drummer.tailSide
    val tail = Path().apply {
        moveTo(s * 16f, -16f)
        quadraticTo(s * 60f, -8f, s * 56f, -46f)
        quadraticTo(s * 53f, -72f, s * 37f, -68f)
        quadraticTo(s * 28f, -64f, s * 34f, -56f)
    }
    drawPath(tail, OUTLINE, style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(tail, drummer.fur, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Head centred on (0, -93): ears, a hair tuft, a heart-shaped face with big eyes, and a mouth that opens on each hit. */
private fun DrawScope.drawMonkeyHead(drummer: Drummer, mouthOpen: Float, gaze: Float, bob: Float) {
    for (side in listOf(-1f, 1f)) {
        val ear = Offset(side * 31f, -97f)
        drawCircle(drummer.fur, radius = 12.5f, center = ear)
        drawCircle(OUTLINE, radius = 12.5f, center = ear, style = Stroke(width = 3f))
        drawCircle(drummer.face, radius = 7f, center = ear)
    }

    val tuft = Path().apply {
        moveTo(-8f, -119f)
        quadraticTo(-5f, -136f, 9f, -131f)
        quadraticTo(0f, -128f, 5f, -119f)
        close()
    }
    drawPath(tuft, drummer.fur)
    drawPath(tuft, OUTLINE, style = Stroke(width = 2.5f, join = StrokeJoin.Round))

    val headRect = Rect(-32f, -122f, 32f, -64f)
    drawOval(drummer.fur, topLeft = headRect.topLeft, size = headRect.size)
    if (drummer.headband != null) {
        clipPath(Path().apply { addOval(headRect) }) {
            drawRect(drummer.headband, topLeft = Offset(-34f, -117f), size = Size(68f, 10f))
            drawRect(drummer.bandTrim, topLeft = Offset(-34f, -112.5f), size = Size(68f, 2.2f))
        }
    }
    drawOval(OUTLINE, topLeft = headRect.topLeft, size = headRect.size, style = Stroke(width = 3f))
    if (drummer.headband != null) {
        // The knot's two tails flap with the bob.
        val knot = Offset(-29f, -112f)
        for ((end, flap) in listOf(Offset(-46f, -118f) to 1.5f, Offset(-44f, -103f) to 1f)) {
            val tip = end + Offset(0f, bob * flap)
            drawLine(OUTLINE, knot, tip, strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(drummer.headband, knot, tip, strokeWidth = 4.5f, cap = StrokeCap.Round)
        }
        drawCircle(drummer.headband, radius = 4.5f, center = knot)
        drawCircle(OUTLINE, radius = 4.5f, center = knot, style = Stroke(width = 2f))
    }

    val mask = Path().apply {
        addOval(Rect(center = Offset(-10f, -97f), radius = 12.5f))
        addOval(Rect(center = Offset(10f, -97f), radius = 12.5f))
        addOval(Rect(-17f, -91f, 17f, -67f))
    }
    drawPath(mask, drummer.face)

    for (side in listOf(-1f, 1f)) {
        drawOval(Color.White, topLeft = Offset(side * 10f - 5.5f, -104f), size = Size(11f, 14f))
        drawOval(OUTLINE, topLeft = Offset(side * 10f - 5.5f, -104f), size = Size(11f, 14f), style = Stroke(width = 1.6f))
        val pupil = Offset(side * 10f + gaze * 1.8f, -96f)
        drawCircle(Color(0xFF1A1206), radius = 3.8f, center = pupil)
        drawCircle(Color.White, radius = 1.4f, center = pupil + Offset(-1.2f, -1.5f))
        drawCircle(Color(0xFFFF7A8A).copy(alpha = 0.35f), radius = 4.5f, center = Offset(side * 19f, -80f))
        drawCircle(OUTLINE, radius = 1.5f, center = Offset(side * 3f, -85f))
    }

    if (mouthOpen > 0.05f) {
        val mouth = Rect(-4f - 3f * mouthOpen, -81f, 4f + 3f * mouthOpen, -78f + 8f * mouthOpen)
        drawOval(Color(0xFF6B1E1E), topLeft = mouth.topLeft, size = mouth.size)
        drawOval(OUTLINE, topLeft = mouth.topLeft, size = mouth.size, style = Stroke(width = 1.5f))
    } else {
        drawArc(
            OUTLINE,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(-7f, -85f),
            size = Size(14f, 10f),
            style = Stroke(width = 2.2f, cap = StrokeCap.Round),
        )
    }
}

/** An arm from [shoulder] to a paw at [hand], bowed outward at the elbow toward [side]. */
private fun DrawScope.drawArm(shoulder: Offset, hand: Offset, side: Float, drummer: Drummer) {
    val elbow = Offset((shoulder.x + hand.x) / 2f + side * 16f, (shoulder.y + hand.y) / 2f + 2f)
    val arm = Path().apply {
        moveTo(shoulder.x, shoulder.y)
        quadraticTo(elbow.x, elbow.y, hand.x, hand.y)
    }
    drawPath(arm, OUTLINE, style = Stroke(width = 14f, cap = StrokeCap.Round))
    drawPath(arm, drummer.fur, style = Stroke(width = 9f, cap = StrokeCap.Round))
    drawOval(drummer.face, topLeft = hand - Offset(9f, 6f), size = Size(18f, 12f))
    drawOval(OUTLINE, topLeft = hand - Offset(9f, 6f), size = Size(18f, 12f), style = Stroke(width = 2.5f))
}

/**
 * One bongo seen from slightly above: a tapered wooden shell with a painted band in the drummer's
 * colors, chrome tuning lugs, and a skin under a chrome rim. On [pulse] the skin tints toward the
 * drummer's hit color and a ripple spreads out from the centre.
 */
private fun DrawScope.drawBongo(x: Float, r: Float, h: Float, pulse: Float, drummer: Drummer) {
    val ry = r * 0.42f
    val shell = Path().apply {
        moveTo(x - r, 0f)
        quadraticTo(x - r * 0.96f, h * 0.6f, x - r * 0.72f, h)
        quadraticTo(x, h + ry * 0.9f, x + r * 0.72f, h)
        quadraticTo(x + r * 0.96f, h * 0.6f, x + r, 0f)
        close()
    }
    drawPath(
        shell,
        brush = Brush.horizontalGradient(
            0f to WOOD_SHADE,
            0.35f to WOOD_LIT,
            0.7f to WOOD_MID,
            1f to WOOD_SHADE,
            startX = x - r,
            endX = x + r,
        ),
    )
    clipPath(shell) {
        val bandY = h * 0.52f
        val band = Path().apply {
            moveTo(x - r - 2f, bandY)
            quadraticTo(x, bandY + ry * 1.5f, x + r + 2f, bandY)
        }
        drawPath(band, drummer.band, style = Stroke(width = 6f))
        val trim = Path().apply {
            moveTo(x - r - 2f, bandY + 5.5f)
            quadraticTo(x, bandY + 5.5f + ry * 1.5f, x + r + 2f, bandY + 5.5f)
        }
        drawPath(trim, drummer.bandTrim, style = Stroke(width = 2f))
    }
    drawPath(shell, OUTLINE, style = Stroke(width = 2.5f, join = StrokeJoin.Round))

    for (deg in listOf(155f, 115f, 65f, 25f)) {
        val a = deg * PI.toFloat() / 180f
        val px = x + cos(a) * r * 0.97f
        val py = sin(a) * ry * 0.97f
        drawLine(CHROME_DARK, Offset(px, py), Offset(px, py + 12f), strokeWidth = 3.6f, cap = StrokeCap.Round)
        drawLine(CHROME_LIT, Offset(px - 0.6f, py + 1f), Offset(px - 0.6f, py + 10f), strokeWidth = 1.4f, cap = StrokeCap.Round)
        drawCircle(CHROME_DARK, radius = 2.2f, center = Offset(px, py + 12f))
    }

    val skinTopLeft = Offset(x - r, -ry)
    val skinSize = Size(r * 2f, ry * 2f)
    val skin = lerp(DRUM_SKIN, drummer.hitColor, 0.7f * pulse)
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(lerp(Color.White, skin, 0.35f), skin, lerp(skin, Color(0xFF9A7A55), 0.5f)),
            center = Offset(x - r * 0.25f, -ry * 0.35f),
            radius = r * 1.15f,
        ),
        topLeft = skinTopLeft,
        size = skinSize,
    )
    if (pulse > 0f) {
        val spread = 0.3f + 0.7f * (1f - pulse)
        drawOval(
            Color.White.copy(alpha = 0.75f * pulse),
            topLeft = Offset(x - r * spread, -ry * spread),
            size = Size(r * 2f * spread, ry * 2f * spread),
            style = Stroke(width = 2.2f),
        )
    }
    drawOval(CHROME_DARK, topLeft = skinTopLeft, size = skinSize, style = Stroke(width = 4.5f))
    drawArc(CHROME_LIT, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = skinTopLeft, size = skinSize, style = Stroke(width = 1.6f))
    drawArc(Color.White.copy(alpha = 0.85f), startAngle = 25f, sweepAngle = 55f, useCenter = false, topLeft = skinTopLeft, size = skinSize, style = Stroke(width = 1.4f, cap = StrokeCap.Round))
}

/** Short impact rays bursting up off a struck drum, spreading and fading as [pulse] decays. */
private fun DrawScope.drawHitBurst(center: Offset, r: Float, pulse: Float, color: Color) {
    if (pulse <= 0f) return
    val spread = 1f - pulse
    for (i in 0 until 5) {
        val a = (-150f + i * 30f) * PI.toFloat() / 180f
        val dir = Offset(cos(a), sin(a))
        val inner = r * (0.8f + 0.55f * spread)
        val outer = inner + 3f + 11f * pulse
        drawLine(color.copy(alpha = pulse), center + dir * inner, center + dir * outer, strokeWidth = 1f + 3f * pulse, cap = StrokeCap.Round)
    }
}

/**
 * A claw-swipe flash across the low drum's skin: three fading speed-lines along the swipe, plus a
 * ring spreading across the skin, so a swipe reads differently from a tap's burst at a glance.
 */
private fun DrawScope.drawSwipeHit(center: Offset, r: Float, flash: Float) {
    if (flash <= 0f) return
    val angle = -14f * PI.toFloat() / 180f
    val dir = Offset(cos(angle), sin(angle))
    val perp = Offset(-dir.y, dir.x)
    val length = r * 2.2f
    for ((i, width) in listOf(3f, 5f, 3f).withIndex()) {
        val lineOffset = perp * ((i - 1) * r * 0.2f) + Offset(0f, -4f)
        val start = center + lineOffset - dir * (length / 2f)
        val end = center + lineOffset + dir * (length / 2f)
        drawLine(Color.White.copy(alpha = 0.85f * flash), start, end, strokeWidth = width * flash, cap = StrokeCap.Round)
    }
    val spread = 1f + 0.4f * (1f - flash)
    drawOval(
        LO_NOTE_COLOR.copy(alpha = 0.6f * flash),
        topLeft = Offset(center.x - r * spread, center.y - r * 0.42f * spread),
        size = Size(r * 2f * spread, r * 0.84f * spread),
        style = Stroke(width = 1f + 3f * flash),
    )
}

/** The main menu's Bongo Blitz emblem: the lead monkey's head, bopping and hollering on every beat of [beat]. */
internal fun DrawScope.drawBongoBlitzEmblem(beat: Double) {
    val s = size.minDimension / 100f
    val pulse = beatBob(beat)
    withTransform({
        translate(size.width / 2f, size.height / 2f)
        scale(s, s, Offset.Zero)
        translate(0f, 100f - 3f * pulse)
    }) {
        drawMonkeyHead(LEAD_DRUMMER, mouthOpen = pulse, gaze = 0f, bob = pulse * 3f)
    }
}
