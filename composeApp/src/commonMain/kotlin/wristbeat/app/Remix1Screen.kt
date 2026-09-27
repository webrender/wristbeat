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
import kotlin.math.atan2
import kotlin.math.floor
import wristbeat.core.Grade
import wristbeat.core.Rank
import wristbeat.core.Remix1Stage
import wristbeat.core.RemixAction
import wristbeat.core.RemixScene
import wristbeat.core.ScoreTally
import wristbeat.core.SoundId

/** A press held longer than this without moving isn't a tap any more (see [MangoChopScreen]'s same constant). */
private const val DEFERRED_TAP_MAX_MS = 300L

/** How long the wipe from one scene into the next takes: the last beat before the new third starts. */
private const val WIPE_BEATS = 1.0

/**
 * 2.75 beats at REMIX_1_BPM is about the same 1.29s look-ahead every stage's highway uses, so the
 * notes scroll at the speed the player already knows from each stage.
 */
private const val REMIX_LOOKAHEAD_BEATS = 2.75

private enum class RemixPressResult { CONSUMED, HIT, DEFERRED }

/** Which stage's accent a scene wipes in with. */
private val RemixScene.accent: Color
    get() = when (this) {
        RemixScene.SNAP_CRABS -> Stage.SNAP_CRABS.accent
        RemixScene.MANGO_CHOP -> Stage.MANGO_CHOP.accent
        RemixScene.BONGO_BLITZ -> Stage.BONGO_BLITZ.accent
    }

/**
 * Remix 1: one new song that runs through Snap Crabs, Mango Chop and Bongo Blitz in turn, a third
 * each, drawn with each stage's own scene and played by its own rules, with new patterns
 * throughout. A slanted panel in the next stage's accent wipes across the screen over the last
 * beat of each third, the way a Rhythm Heaven remix cuts from game to game.
 *
 * Touch input follows [BongoBlitzScreen]'s rule (a press hits the instant the finger lands unless
 * the nearest open target wants a swipe), except in the crab third, which has no swipe: there
 * every press is a snap, like [SnapCrabsScreen].
 */
@Composable
fun Remix1Screen(
    calibration: Calibration,
    chart: ChartSetting,
    onRunningChanged: (Boolean) -> Unit = {},
    onMenu: (() -> Unit)? = null,
) {
    val audioClock = remember { AudioClock() }
    val audioEngine = remember { AudioEngine() }
    val haptics = remember { HapticEngine() }
    var stage by remember { mutableStateOf(Remix1Stage(calibration.inputOffsetMs)) }
    val secondsPerBeat = stage.secondsPerBeat

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var t0 by remember { mutableStateOf(0.0) }
    var scheduledIndex by remember { mutableStateOf(0) }
    var beatPosition by remember { mutableStateOf(-1.0) }
    var lastFiredBeat by remember { mutableStateOf(-1) }
    var beatFlashPhase by remember { mutableStateOf(0f) }
    var nextLeadCueIndex by remember { mutableStateOf(0) }
    var nextTargetIndex by remember { mutableStateOf(0) }
    var targetPulsePhase by remember { mutableStateOf(0f) }
    // Crabs.
    var crabLeadPulse by remember { mutableStateOf(0f) }
    var crabPlayerPulse by remember { mutableStateOf(0f) }
    // Mangoes.
    var sinceChop by remember { mutableStateOf(10f) }
    var sinceSlash by remember { mutableStateOf(10f) }
    var slashAngle by remember { mutableStateOf(0f) }
    var keySlashFlip by remember { mutableStateOf(false) }
    // Monkeys.
    var leadHiPulse by remember { mutableStateOf(0f) }
    var leadLoPulse by remember { mutableStateOf(0f) }
    var playerHiPulse by remember { mutableStateOf(0f) }
    var playerLoPulse by remember { mutableStateOf(0f) }
    var playerLoSwipeFlash by remember { mutableStateOf(0f) }
    var tally by remember { mutableStateOf(ScoreTally()) }
    // How this run compared with the song's saved best, once it has finished.
    var record by remember { mutableStateOf<RecordResult?>(null) }
    ReportRunning(started && !finished, onRunningChanged)

    // Each scene's own highway, showing only its own third's targets in its own note style.
    val highways = remember(stage) {
        stage.targets.groupBy({ it.scene }) { target ->
            when (target.scene) {
                RemixScene.SNAP_CRABS -> snapCrabsHighwayNote(target.beat)
                RemixScene.MANGO_CHOP -> mangoChopHighwayNote(target.toss!!.fruit, target.beat)
                RemixScene.BONGO_BLITZ -> bongoBlitzHighwayNote(target.beat, lowDrum = target.action == RemixAction.SWIPE)
            }
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
            // Drawn at the beat the player hears (see SnapCrabsScreen).
            val beat = stage.perceivedBeat(rawBeat)
            beatPosition = beat
            sinceChop += dt
            sinceSlash += dt

            val beatIndex = floor(beat).toInt()
            if (beatIndex > lastFiredBeat) {
                lastFiredBeat = beatIndex
                beatFlashPhase = 1f
            }
            while (nextLeadCueIndex < stage.leadCues.size && beat >= stage.leadCues[nextLeadCueIndex].beat) {
                val cue = stage.leadCues[nextLeadCueIndex]
                when {
                    cue.scene == RemixScene.SNAP_CRABS -> crabLeadPulse = 1f
                    cue.action == RemixAction.TAP -> leadHiPulse = 1f
                    else -> leadLoPulse = 1f
                }
                nextLeadCueIndex++
            }
            while (nextTargetIndex < stage.targets.size && beat >= stage.targets[nextTargetIndex].beat) {
                nextTargetIndex++
                targetPulsePhase = 1f
            }
            beatFlashPhase = (beatFlashPhase - dt * 4f).coerceAtLeast(0f)
            targetPulsePhase = (targetPulsePhase - dt * 5f).coerceAtLeast(0f)
            crabLeadPulse = (crabLeadPulse - dt * 5f).coerceAtLeast(0f)
            crabPlayerPulse = (crabPlayerPulse - dt * 5f).coerceAtLeast(0f)
            leadHiPulse = (leadHiPulse - dt * 5f).coerceAtLeast(0f)
            leadLoPulse = (leadLoPulse - dt * 5f).coerceAtLeast(0f)
            playerHiPulse = (playerHiPulse - dt * 5f).coerceAtLeast(0f)
            playerLoPulse = (playerLoPulse - dt * 5f).coerceAtLeast(0f)
            playerLoSwipeFlash = (playerLoSwipeFlash - dt * 3f).coerceAtLeast(0f)

            // A fruit that isn't cut in time thuds off the board, as in Mango Chop.
            if (stage.updateMisses(rawBeat).any { stage.targets[it].toss != null }) audioEngine.play(SoundId.THUD, now)
            tally = stage.tally()

            if (stage.isFinished(beat)) {
                finished = true
                record = submitScore(Stage.REMIX_1, tally.percent)
            }
        }
    }

    fun beatNow(): Double = (audioClock.now() - t0) / secondsPerBeat

    fun restart() {
        audioClock.start()
        calibration.refreshOutput()
        // Fresh stage per run: judged targets and the tally don't carry over into a replay.
        stage = Remix1Stage(calibration.inputOffsetMs)
        t0 = audioClock.now() + 0.3
        scheduledIndex = 0
        beatPosition = -1.0
        lastFiredBeat = -1
        beatFlashPhase = 0f
        nextLeadCueIndex = 0
        nextTargetIndex = 0
        targetPulsePhase = 0f
        crabLeadPulse = 0f
        crabPlayerPulse = 0f
        sinceChop = 10f
        sinceSlash = 10f
        leadHiPulse = 0f
        leadLoPulse = 0f
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

    fun sceneNow(rawBeat: Double): RemixScene = stage.sceneAt(stage.perceivedBeat(rawBeat))

    /** Plays the current scene's own sound and animation for [action], then judges it. */
    fun act(action: RemixAction, beat: Double, angle: Float = 0f) {
        val scene = sceneNow(beat)
        // The crabs only snap; there's nothing for a swipe to do there.
        if (scene == RemixScene.SNAP_CRABS && action == RemixAction.SWIPE) return
        val outcome = stage.recordAction(action, beat)
        val now = audioClock.now()
        when (scene) {
            RemixScene.SNAP_CRABS -> {
                audioEngine.play(SoundId.PLAYER_SNAP, now)
                crabPlayerPulse = 1f
            }
            RemixScene.MANGO_CHOP -> if (action == RemixAction.TAP) {
                audioEngine.play(SoundId.CHOP, now)
                sinceChop = 0f
            } else {
                audioEngine.play(SoundId.SLICE, now)
                sinceSlash = 0f
                slashAngle = angle
            }
            RemixScene.BONGO_BLITZ -> if (action == RemixAction.TAP) {
                audioEngine.play(SoundId.PLAYER_BONGO_HI, now)
                playerHiPulse = 1f
            } else {
                audioEngine.play(SoundId.PLAYER_BONGO_LO, now)
                playerLoPulse = 1f
                playerLoSwipeFlash = 1f
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

    fun press(beat: Double): RemixPressResult {
        if (handleMenuPress()) return RemixPressResult.CONSUMED
        if (stage.expectedAction(beat) == RemixAction.SWIPE) return RemixPressResult.DEFERRED
        act(RemixAction.TAP, beat)
        return RemixPressResult.HIT
    }

    fun keySwipe() {
        if (handleMenuPress()) return
        // No drag direction from a key, so alternate a slice between two diagonals (see MangoChopScreen).
        keySlashFlip = !keySlashFlip
        act(RemixAction.SWIPE, beatNow(), if (keySlashFlip) -20f else 20f)
    }

    // The stage begins as soon as it's opened — no tap needed to start the song.
    LaunchedEffect(Unit) { restart() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                rememberTapKeyModifier(
                    onTap = { if (!handleMenuPress()) act(RemixAction.TAP, beatNow()) },
                    onSwipe = ::keySwipe,
                ),
            )
            .pointerInput(Unit) {
                val swipeDistance = 24.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val beatAtDown = beatNow()
                    val result = press(beatAtDown)
                    if (result == RemixPressResult.CONSUMED) return@awaitEachGesture
                    // A drag in the crab third is just a snap that moved; it doesn't become a swipe.
                    if (sceneNow(beatAtDown) == RemixScene.SNAP_CRABS) return@awaitEachGesture
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        val delta = change.position - down.position
                        if (delta.getDistance() >= swipeDistance) {
                            act(RemixAction.SWIPE, beatNow(), atan2(delta.y, delta.x) * 180f / PI.toFloat())
                            return@awaitEachGesture
                        }
                        if (!change.pressed) {
                            val heldMs = change.uptimeMillis - down.uptimeMillis
                            if (result == RemixPressResult.DEFERRED && heldMs <= DEFERRED_TAP_MAX_MS) {
                                act(RemixAction.TAP, beatAtDown)
                            }
                            return@awaitEachGesture
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun drawScene(scene: RemixScene) {
                val highway = highways[scene].takeIf { chart.on }
                val hitFlash = maxOf(beatFlashPhase * 0.5f, targetPulsePhase)
                when (scene) {
                    RemixScene.SNAP_CRABS -> drawSnapCrabsScene(
                        beat = beatPosition,
                        leadPulse = crabLeadPulse,
                        playerPulse = crabPlayerPulse,
                        highway = highway,
                        lookaheadBeats = REMIX_LOOKAHEAD_BEATS,
                        hitFlash = hitFlash,
                    )
                    RemixScene.MANGO_CHOP -> drawMangoChopScene(
                        beat = beatPosition,
                        tosses = stage.tosses,
                        resultOf = stage::tossResult,
                        secondsPerBeat = secondsPerBeat,
                        started = true,
                        sinceChop = sinceChop,
                        sinceSlash = sinceSlash,
                        slashAngle = slashAngle,
                        highway = highway,
                        lookaheadBeats = REMIX_LOOKAHEAD_BEATS,
                    )
                    RemixScene.BONGO_BLITZ -> drawBongoBlitzScene(
                        beat = beatPosition,
                        leadHiPulse = leadHiPulse,
                        leadLoPulse = leadLoPulse,
                        playerHiPulse = playerHiPulse,
                        playerLoPulse = playerLoPulse,
                        playerLoSwipeFlash = playerLoSwipeFlash,
                        highway = highway,
                        lookaheadBeats = REMIX_LOOKAHEAD_BEATS,
                        hitFlash = hitFlash,
                    )
                }
            }

            drawScene(stage.sceneAt(beatPosition))
            val incoming = stage.segments.drop(1).firstOrNull { beatPosition >= it.start - WIPE_BEATS && beatPosition < it.start }
            if (incoming != null) {
                val progress = ((beatPosition - (incoming.start - WIPE_BEATS)) / WIPE_BEATS).toFloat()
                drawWipe(progress, incoming.scene.accent) { drawScene(incoming.scene) }
            }
        }

        StreakBadge(tally.streak, beat = { beatPosition }, visible = !finished)

        StageResults(
            visible = finished,
            accent = Stage.REMIX_1.accent,
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

/**
 * Wipes the scene drawn by [incoming] in over whatever's already on the canvas, from the right, as
 * [progress] goes 0→1: a slanted edge sweeps across, trailing a thick inked band in [accent].
 */
private fun DrawScope.drawWipe(progress: Float, accent: Color, incoming: DrawScope.() -> Unit) {
    val p = progress.coerceIn(0f, 1f)
    val e = p * p * (3f - 2f * p)
    val w = size.width
    val h = size.height
    val slant = h * 0.25f
    val band = minOf(w, h) * 0.05f
    // The edge runs from (topX, 0) down to (topX - slant, h); it starts just off the right side of
    // the canvas and finishes with the band just off the left.
    val topX = (w + slant) + (-band - (w + slant)) * e
    fun region(left: Float) = Path().apply {
        moveTo(left, 0f)
        lineTo(w + slant + band, 0f)
        lineTo(w + slant + band, h)
        lineTo(left - slant, h)
        close()
    }

    clipPath(region(topX + band)) { incoming() }

    val stripe = Path().apply {
        moveTo(topX, 0f)
        lineTo(topX + band, 0f)
        lineTo(topX + band - slant, h)
        lineTo(topX - slant, h)
        close()
    }
    drawPath(stripe, accent)
    drawLine(lerp(accent, Color.White, 0.5f), Offset(topX + band * 0.3f, 0f), Offset(topX + band * 0.3f - slant, h), strokeWidth = band * 0.18f)
    drawPath(stripe, Color(0xFF1A1206), style = Stroke(width = band * 0.22f, join = StrokeJoin.Round))
}

/**
 * The main menu's Remix 1 emblem: a chunky number 1 in the inked cartoon style — thickly outlined
 * in a deep shade of the remix's pink, with a soft shadow — squashing and stretching on every beat
 * of [beat].
 */
internal fun DrawScope.drawRemix1Emblem(beat: Double) {
    val s = size.minDimension / 100f
    val pulse = if (beat < 0) 0f else (1f - (beat - floor(beat)).toFloat()).let { it * it * it }
    val accent = Stage.REMIX_1.accent
    val ink = lerp(accent, Color.Black, 0.72f)
    // Authored in a 100×100 box, standing on y = 86.
    val one = Path().apply {
        moveTo(46f, 14f)
        lineTo(60f, 14f)
        lineTo(60f, 74f)
        lineTo(70f, 74f)
        lineTo(70f, 86f)
        lineTo(32f, 86f)
        lineTo(32f, 74f)
        lineTo(44f, 74f)
        lineTo(44f, 37f)
        lineTo(33f, 42f)
        lineTo(29f, 31f)
        close()
    }
    withTransform({
        translate(size.width / 2f - 50f * s, size.height / 2f - 50f * s)
        scale(s, s, Offset.Zero)
    }) {
        withTransform({ scale(1f, 0.18f, Offset(51f, 88f)) }) {
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Black.copy(alpha = 0f)), center = Offset(51f, 88f), radius = 30f),
                radius = 30f,
                center = Offset(51f, 88f),
            )
        }
        // Squash on the beat, anchored at the base, then spring back up.
        withTransform({ scale(1f + 0.08f * pulse, 1f - 0.1f * pulse, Offset(51f, 86f)) }) {
            // Near-white with a blush of pink toward the base, so it stands out on the pink button.
            drawPath(one, ink, style = Stroke(width = 9f, join = StrokeJoin.Round))
            drawPath(one, brush = Brush.verticalGradient(listOf(Color.White, lerp(Color.White, accent, 0.45f)), startY = 14f, endY = 86f))
            drawLine(Color.White, Offset(50f, 20f), Offset(50f, 64f), strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
}
