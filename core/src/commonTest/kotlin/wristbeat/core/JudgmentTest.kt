package wristbeat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JudgmentTest {
    @Test fun perfectAtZero() = assertEquals(Grade.PERFECT, judge(0.0))
    @Test fun perfectAtBoundary() = assertEquals(Grade.PERFECT, judge(45.0))
    @Test fun perfectIsSymmetric() = assertEquals(Grade.PERFECT, judge(-45.0))
    @Test fun okJustPastPerfectBoundary() = assertEquals(Grade.OK, judge(45.1))
    @Test fun okAtOuterBoundary() = assertEquals(Grade.OK, judge(120.0))
    @Test fun missPastOuterBoundary() = assertNull(judge(120.1))

    @Test fun missNotDetectedBeforeWindowPasses() {
        assertFalse(isMissed(currentBeat = 4.0, targetBeat = 4.0))
    }

    @Test fun missDetectedOnceWindowFullyPassed() {
        val justPastWindow = 4.0 + (OK_WINDOW_MS / 1000.0 + 0.001) / SECONDS_PER_BEAT
        assertTrue(isMissed(justPastWindow, 4.0))
    }

    @Test fun scoreTallyRank() {
        assertEquals(Rank.SUPERB, ScoreTally(perfect = 9, ok = 0, miss = 1).rank) // 0.9
        assertEquals(Rank.OK, ScoreTally(perfect = 6, ok = 0, miss = 4).rank) // 0.6
        assertEquals(Rank.TRY_AGAIN, ScoreTally(perfect = 5, ok = 0, miss = 5).rank) // 0.5
    }

    @Test fun scorekeeperCountsBackToBackPerfectsAsAStreak() {
        val score = Scorekeeper()
        repeat(6) { score.hit(Grade.PERFECT) }
        assertEquals(ScoreTally(perfect = 6, streak = 6), score.tally())
    }

    @Test fun anythingShortOfPerfectBreaksTheStreakAndItRestartsFromZero() {
        for (breaker in listOf<(Scorekeeper) -> Unit>({ it.hit(Grade.OK) }, { it.miss() }, { it.stray() })) {
            val score = Scorekeeper()
            repeat(7) { score.hit(Grade.PERFECT) }
            breaker(score)
            assertEquals(0, score.tally().streak)
            score.hit(Grade.PERFECT)
            assertEquals(1, score.tally().streak)
        }
    }

    @Test fun aStrayScoresNothing() {
        val score = Scorekeeper()
        score.stray()
        assertEquals(ScoreTally(), score.tally())
    }

    @Test fun percentRoundsTheScore() {
        assertEquals(67, ScoreTally(perfect = 2, miss = 1).percent)
        assertEquals(0, ScoreTally().percent)
    }

    @Test fun medianOddCount() = assertEquals(2.0, median(listOf(3.0, 1.0, 2.0)))
    @Test fun medianEvenCount() = assertEquals(2.5, median(listOf(1.0, 2.0, 3.0, 4.0)))
    @Test fun medianEmpty() = assertEquals(0.0, median(emptyList()))
}
