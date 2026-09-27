package wristbeat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangoChopStageTest {
    private fun firstToss(stage: MangoChopStage, fruit: Fruit): Int = stage.tosses.indexOfFirst { it.fruit == fruit }

    @Test fun fruitLandsAfterItsAirTime() {
        val stage = MangoChopStage()
        val mango = stage.tosses[firstToss(stage, Fruit.MANGO)]
        val lime = stage.tosses[firstToss(stage, Fruit.LIME)]
        assertEquals(mango.beat + 2.0, mango.landBeat)
        assertEquals(lime.beat + 1.0, lime.landBeat)
    }

    @Test fun runsFasterThanSnapCrabs() {
        assertTrue(MangoChopStage().secondsPerBeat < SECONDS_PER_BEAT)
    }

    @Test fun chopOnLandingIsPerfect() {
        val stage = MangoChopStage()
        val i = firstToss(stage, Fruit.MANGO)
        val outcome = stage.recordAction(ChopAction.CHOP, stage.tosses[i].landBeat)
        assertEquals(Grade.PERFECT, outcome.grade)
        assertEquals(i, outcome.tossIndex)
        assertEquals(1, stage.tally().perfect)
    }

    @Test fun sliceOnPineappleLandingIsPerfect() {
        val stage = MangoChopStage()
        val i = firstToss(stage, Fruit.PINEAPPLE)
        val outcome = stage.recordAction(ChopAction.SLICE, stage.tosses[i].landBeat)
        assertEquals(Grade.PERFECT, outcome.grade)
        assertEquals(i, outcome.tossIndex)
    }

    @Test fun wrongActionIsStrayAndLeavesTheFruitOpen() {
        val stage = MangoChopStage()
        val i = firstToss(stage, Fruit.PINEAPPLE)
        val land = stage.tosses[i].landBeat
        assertNull(stage.recordAction(ChopAction.CHOP, land).grade)
        assertNull(stage.resultOf(i))
        assertEquals(Grade.PERFECT, stage.recordAction(ChopAction.SLICE, land).grade)
    }

    @Test fun expectedActionFollowsTheNearestOpenFruit() {
        val stage = MangoChopStage()
        val pineapple = stage.tosses[firstToss(stage, Fruit.PINEAPPLE)]
        assertEquals(ChopAction.CHOP, stage.expectedAction(stage.tosses.first().landBeat))
        assertEquals(ChopAction.SLICE, stage.expectedAction(pineapple.landBeat))
    }

    @Test fun missWindowUsesTheStageTempo() {
        val stage = MangoChopStage()
        val land = stage.tosses.first().landBeat
        val justInside = land + (OK_WINDOW_MS / 1000.0 - 0.005) / stage.secondsPerBeat
        assertTrue(stage.updateMisses(justInside).isEmpty())
        val justPast = land + (OK_WINDOW_MS / 1000.0 + 0.005) / stage.secondsPerBeat
        assertEquals(listOf(0), stage.updateMisses(justPast))
        assertEquals(1, stage.tally().miss)
    }

    @Test fun calibratedOffsetShiftsJudgment() {
        val stage = MangoChopStage(inputOffsetMs = 80.0)
        val land = stage.tosses.first().landBeat
        val outcome = stage.recordAction(ChopAction.CHOP, land + 0.080 / stage.secondsPerBeat)
        assertEquals(Grade.PERFECT, outcome.grade)
        assertEquals(0.0, outcome.errorMs!!, 0.001)
    }

    @Test fun cuttingEveryFruitOnTimeIsSuperb() {
        val stage = MangoChopStage()
        for (toss in stage.tosses) stage.recordAction(toss.fruit.action, toss.landBeat)
        assertEquals(stage.tosses.size, stage.tally().perfect)
        assertEquals(Rank.SUPERB, stage.tally().rank)
    }

    @Test fun chartCarriesAWhistleForEveryToss() {
        val stage = MangoChopStage()
        for (toss in stage.tosses) {
            assertTrue(stage.chart.any { it.beat == toss.beat && it.sound == toss.fruit.cue })
        }
        assertTrue(stage.chart.any { it.sound == SoundId.STEEL_PAN })
    }

    @Test fun hasItsOwnSongRatherThanSnapCrabsBand() {
        val mango = Charts.mangoChopBacking(MANGO_CHOP_END_BEATS)
        val uke = setOf(SoundId.UKE_F, SoundId.UKE_C, SoundId.UKE_BB)
        assertTrue(mango.none { it.sound in uke || it.sound == SoundId.MEL })
        assertTrue(mango.any { it.sound == SoundId.KEYS } && mango.any { it.sound == SoundId.SHAKER })
        // The steel pan stays under the whistles (the lowest starts at 520Hz, about MIDI 72).
        assertTrue(mango.filter { it.sound == SoundId.STEEL_PAN }.all { it.param <= 74.0 })
    }

    @Test fun perceivedBeatSubtractsTheOffset() {
        val stage = MangoChopStage(inputOffsetMs = 200.0)
        assertEquals(10.0 - 0.2 / stage.secondsPerBeat, stage.perceivedBeat(10.0), 1e-9)
    }

    @Test fun resultBeatIsPerceivedSoAnimationsLineUpWithTheVisuals() {
        val stage = MangoChopStage(inputOffsetMs = 200.0)
        val i = firstToss(stage, Fruit.MANGO)
        val rawTap = stage.tosses[i].landBeat + 0.2 / stage.secondsPerBeat
        stage.recordAction(ChopAction.CHOP, rawTap)
        assertEquals(stage.tosses[i].landBeat, stage.resultOf(i)!!.beat, 1e-9)
    }
}
