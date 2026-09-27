package wristbeat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Remix1StageTest {
    @Test fun eachStageGetsAThirdOfTheSong() {
        val stage = Remix1Stage()
        assertEquals(listOf(RemixScene.SNAP_CRABS, RemixScene.MANGO_CHOP, RemixScene.BONGO_BLITZ), stage.segments.map { it.scene })
        val gameplay = listOf(
            Remix1Song.MANGO - Remix1Song.CRABS,
            Remix1Song.BONGO - Remix1Song.MANGO,
            Remix1Song.OUTRO - Remix1Song.BONGO,
        )
        assertTrue(gameplay.all { it == gameplay.first() })
    }

    @Test fun everyTargetAndCueFallsInsideItsOwnScene() {
        val stage = Remix1Stage()
        for (target in stage.targets) assertEquals(target.scene, stage.sceneAt(target.beat), "target at ${target.beat}")
        for (cue in stage.leadCues) assertEquals(cue.scene, stage.sceneAt(cue.beat), "cue at ${cue.beat}")
        for (toss in stage.tosses) assertEquals(RemixScene.MANGO_CHOP, stage.sceneAt(toss.beat))
    }

    @Test fun eachSceneFinishesBeforeTheWipeIntoTheNext() {
        // The screen wipes to the next scene over the last beat of each third, so every target's
        // window has to have closed by then.
        val stage = Remix1Stage()
        val okWindowBeats = OK_WINDOW_MS / 1000.0 / stage.secondsPerBeat
        for (segment in stage.segments.dropLast(1)) {
            val last = stage.targets.last { it.scene == segment.scene }
            assertTrue(last.beat + okWindowBeats < segment.end - 1.0, "${segment.scene}'s last target at ${last.beat}")
        }
    }

    @Test fun crabThirdIsTapOnlyAndTheOthersMixInSwipes() {
        val stage = Remix1Stage()
        assertTrue(stage.targets.filter { it.scene == RemixScene.SNAP_CRABS }.all { it.action == RemixAction.TAP })
        for (scene in listOf(RemixScene.MANGO_CHOP, RemixScene.BONGO_BLITZ)) {
            val actions = stage.targets.filter { it.scene == scene }.map { it.action }.toSet()
            assertEquals(setOf(RemixAction.TAP, RemixAction.SWIPE), actions, "$scene")
        }
    }

    @Test fun notesAreNewNotTheOriginalStagesPatterns() {
        val stage = Remix1Stage()
        val crabs = stage.targets.filter { it.scene == RemixScene.SNAP_CRABS }.map { it.beat - Remix1Song.CRABS }
        assertNotEquals(SnapCrabsStage().targets.map { it - SnapCrabsSong.VERSE }, crabs)
        val bongo = stage.targets.filter { it.scene == RemixScene.BONGO_BLITZ }.map { it.beat - Remix1Song.BONGO }
        assertNotEquals(BongoBlitzStage().targets.map { it.beat - BongoBlitzSong.VERSE }, bongo)
        val mango = stage.tosses.map { it.beat - Remix1Song.MANGO to it.fruit }
        assertNotEquals(MangoChopStage().tosses.map { it.beat - MangoChopSong.VERSE to it.fruit }, mango)
    }

    @Test fun noTwoNotesAreEverCloserThanAnEighthNoteApart() {
        val sorted = Remix1Stage().targets
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].beat - sorted[i].beat
            assertTrue(gap >= 0.5 - 1e-9, "notes at beats ${sorted[i].beat} and ${sorted[i + 1].beat} are only $gap beats apart")
        }
    }

    @Test fun everySwipeHasAFullBeatClearOnEitherSide() {
        // Same reasoning as BongoBlitzStageTest: a swipe takes real time to drag out and re-touch.
        val sorted = Remix1Stage().targets
        for (i in sorted.indices) {
            if (sorted[i].action != RemixAction.SWIPE) continue
            sorted.getOrNull(i - 1)?.let { assertTrue(sorted[i].beat - it.beat >= 1.0, "swipe at ${sorted[i].beat} follows ${it.beat}") }
            sorted.getOrNull(i + 1)?.let { assertTrue(it.beat - sorted[i].beat >= 1.0, "swipe at ${sorted[i].beat} precedes ${it.beat}") }
        }
    }

    @Test fun callAndResponseTargetsAreOneBarAfterTheirCalls() {
        val stage = Remix1Stage()
        val responses = stage.targets.filter { it.toss == null }
        assertEquals(stage.leadCues.size, responses.size)
        for ((cue, target) in stage.leadCues.zip(responses)) {
            assertEquals(cue.beat + 4.0, target.beat)
            assertEquals(cue.action, target.action)
        }
    }

    @Test fun tossesLandAsTargetsAfterTheirAirTime() {
        val stage = Remix1Stage()
        for ((i, toss) in stage.tosses.withIndex()) {
            assertTrue(stage.targets.any { it.toss == toss && it.beat == toss.landBeat })
            assertNull(stage.tossResult(i))
        }
    }

    @Test fun matchingActionExactlyOnTargetIsPerfect() {
        val stage = Remix1Stage()
        for (target in stage.targets) {
            val outcome = stage.recordAction(target.action, target.beat)
            assertEquals(Grade.PERFECT, outcome.grade, "target at ${target.beat}")
        }
        assertEquals(stage.targets.size, stage.tally().perfect)
        assertEquals(Rank.SUPERB, stage.tally().rank)
    }

    @Test fun slicingAPineappleResolvesItsToss() {
        val stage = Remix1Stage()
        val i = stage.tosses.indexOfFirst { it.fruit == Fruit.PINEAPPLE }
        val land = stage.tosses[i].landBeat
        assertNull(stage.recordAction(RemixAction.TAP, land).grade)
        assertNull(stage.tossResult(i))
        assertEquals(Grade.PERFECT, stage.recordAction(RemixAction.SWIPE, land).grade)
        assertEquals(Grade.PERFECT, stage.tossResult(i)?.grade)
    }

    @Test fun expectedActionFollowsTheNearestOpenTarget() {
        val stage = Remix1Stage()
        val swipe = stage.targets.first { it.action == RemixAction.SWIPE }
        assertEquals(RemixAction.TAP, stage.expectedAction(stage.targets.first().beat))
        assertEquals(RemixAction.SWIPE, stage.expectedAction(swipe.beat))
    }

    @Test fun uncaughtTargetBecomesMissAfterWindowPasses() {
        val stage = Remix1Stage()
        val target = stage.targets.first()
        val justInside = target.beat + (OK_WINDOW_MS / 1000.0 - 0.005) / stage.secondsPerBeat
        assertTrue(stage.updateMisses(justInside).isEmpty())
        val justPast = target.beat + (OK_WINDOW_MS / 1000.0 + 0.005) / stage.secondsPerBeat
        assertEquals(listOf(0), stage.updateMisses(justPast))
        assertEquals(1, stage.tally().miss)
    }

    @Test fun calibratedOffsetShiftsJudgmentAndVisuals() {
        val stage = Remix1Stage(inputOffsetMs = 80.0)
        val target = stage.targets.first()
        val outcome = stage.recordAction(target.action, target.beat + 0.080 / stage.secondsPerBeat)
        assertEquals(Grade.PERFECT, outcome.grade)
        assertEquals(0.0, outcome.errorMs!!, 0.001)
        assertEquals(10.0 - 0.08 / stage.secondsPerBeat, stage.perceivedBeat(10.0), 1e-9)
    }

    @Test fun sceneAtCoversTheIntroAndOutro() {
        val stage = Remix1Stage()
        assertEquals(RemixScene.SNAP_CRABS, stage.sceneAt(-1.0))
        assertEquals(RemixScene.SNAP_CRABS, stage.sceneAt(Remix1Song.MANGO - 0.01))
        assertEquals(RemixScene.MANGO_CHOP, stage.sceneAt(Remix1Song.MANGO))
        assertEquals(RemixScene.BONGO_BLITZ, stage.sceneAt(Remix1Song.BONGO))
        assertEquals(RemixScene.BONGO_BLITZ, stage.sceneAt(stage.end + 1.0))
    }

    @Test fun chartCarriesEveryCueSound() {
        val stage = Remix1Stage()
        for (cue in stage.leadCues) {
            val expected = when {
                cue.scene == RemixScene.SNAP_CRABS -> SoundId.LEAD_SNAP
                cue.action == RemixAction.TAP -> SoundId.LEAD_BONGO_HI
                else -> SoundId.LEAD_BONGO_LO
            }
            assertTrue(stage.chart.any { it.beat == cue.beat && it.sound == expected }, "cue at ${cue.beat}")
        }
        for (toss in stage.tosses) assertTrue(stage.chart.any { it.beat == toss.beat && it.sound == toss.fruit.cue })
    }

    @Test fun songHasAnIntroBeforeTheFirstCallAndAnOutroAfterTheLastTarget() {
        val stage = Remix1Stage()
        assertEquals(Remix1Song.CRABS, stage.leadCues.first().beat)
        assertTrue(stage.chart.any { it.beat < Remix1Song.CRABS - 4 && it.sound == SoundId.MEL })
        assertTrue(stage.chart.any { it.beat < Remix1Song.CRABS && it.sound == SoundId.STICK })
        assertTrue(stage.targets.last().beat < Remix1Song.OUTRO)
        assertTrue(stage.chart.any { it.beat == Remix1Song.OUTRO && it.sound == SoundId.CRASH })
        assertTrue(stage.chart.all { it.beat < stage.end })
    }

    @Test fun eachThirdKeepsItsStagesColourInTheBand() {
        val chart = Remix1Stage().chart
        fun inThird(from: Double, to: Double, sound: SoundId) = chart.any { it.beat >= from && it.beat < to && it.sound == sound }
        assertTrue(inThird(Remix1Song.MANGO, Remix1Song.BONGO, SoundId.STEEL_PAN))
        assertTrue(inThird(Remix1Song.MANGO, Remix1Song.BONGO, SoundId.SHAKER))
        assertTrue(inThird(Remix1Song.BONGO, Remix1Song.OUTRO, SoundId.MARIMBA))
        assertTrue(!inThird(Remix1Song.CRABS, Remix1Song.MANGO, SoundId.STEEL_PAN))
        // The clap backbeat is the remix's own, in every third.
        assertTrue(inThird(Remix1Song.CRABS, Remix1Song.MANGO, SoundId.CLAP))
        assertTrue(inThird(Remix1Song.BONGO, Remix1Song.OUTRO, SoundId.CLAP))
    }
}
