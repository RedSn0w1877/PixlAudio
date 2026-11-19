package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YourSoundTest {
    private val day = 86_400_000L
    private val now = 1_760_000_000_000L

    private fun play(rid: Long, aid: Long, ago: Long, first: Boolean = false, tz: Int = 0) = YourSound.Play(rid, aid, now - ago, tz, first)

    @Test
    fun `islands count only the last 28 days and sort by share`() {
        val plays = listOf(
            play(1, 10, day), play(2, 10, 2 * day), play(3, 11, 3 * day), play(4, 12, 40 * day), play(5, 13, day),
        )
        val islandOf = { rid: Long -> if (rid <= 2L) 7 else if (rid == 3L) 8 else if (rid == 4L) 9 else -1 }
        val result = YourSound.islands(plays, islandOf, now)
        assertEquals(listOf(7, 8), result.map { it.islandId })
        assertEquals(667, result[0].permille)
        assertEquals(333, result[1].permille)
        assertEquals(10L, result[0].topAids[0])
        assertTrue(YourSound.islands(emptyList(), islandOf, now).isEmpty())
    }

    @Test
    fun `the first play of a recording is new, a known one is not`() {
        val plays = YourSound.withFirsts(longArrayOf(1, 2, 1, 3), longArrayOf(0, 0, 0, 0), longArrayOf(now - 4, now - 3, now - 2, now - 1), IntArray(4), setOf(3L))
        assertEquals(listOf(true, true, false, false), plays.map { it.first })
        val n = YourSound.novelty(plays, now)
        assertEquals(4, n.plays)
        assertEquals(2, n.novel)
        assertEquals(500, n.permille)
    }

    @Test
    fun `dayparts and months`() {
        val morning = now - (now % day) + 8 * 3_600_000L
        val night = now - (now % day) + 23 * 3_600_000L
        val plays = listOf(
            YourSound.Play(1, 0, morning, 0, false), YourSound.Play(1, 0, morning + 1, 0, false), YourSound.Play(2, 0, night, 0, false),
        )
        val dp = YourSound.dayparts(plays) { if (it == 1L) 5 else 6 }
        assertEquals(2, dp[HomePlanner.DAYPART_MORNING].plays)
        assertEquals(5, dp[HomePlanner.DAYPART_MORNING].leadingIslandId)
        assertEquals(6, dp[HomePlanner.DAYPART_NIGHT].leadingIslandId)
        assertEquals(-1, dp[HomePlanner.DAYPART_AFTERNOON].leadingIslandId)
        assertEquals(1970 * 12, YourSound.monthOfDay(0L))
        assertEquals(2024 * 12 + 1, YourSound.monthOfDay(19_782L)) // 2024-02-29
        assertEquals(2025 * 12 + 9, YourSound.monthOfDay(20_370L)) // 2025-10-09
        val months = YourSound.months(plays) { if (it == 1L) 5 else 6 }
        assertEquals(1, months.size)
        assertEquals(3, months[0].plays)
        assertEquals(listOf(5 to 667, 6 to 333), months[0].islandPermille)
    }

    @Test
    fun `months are capped at 24`() {
        val plays = (0 until 40).map { YourSound.Play(1, 0, it * 31L * day, 0, false) }
        assertEquals(YourSound.MAX_MONTHS, YourSound.months(plays) { -1 }.size)
    }

    @Test
    fun `eras are hidden until a year is known for most plays`() {
        val plays = (1L..10L).map { play(it, 0, day) }
        assertTrue(YourSound.decades(plays) { if (it <= 5L) 1994 else 0 }.isEmpty(), "50% known is not enough")
        val shown = YourSound.decades(plays) { if (it <= 7L) 1994 else if (it <= 8L) 2003 else 0 }
        assertEquals(listOf(1990, 2000), shown.map { it.decade })
        assertEquals(875, shown[0].permille)
    }

    @Test
    fun `unknown genres are absent, not guessed`() {
        val plays = (1L..4L).map { play(it, 0, day) }
        val g = YourSound.genres(plays) { if (it <= 2L) 12 else GenreAtlas.NONE }
        assertEquals(listOf(12 to 1000), g)
        assertTrue(YourSound.genres(plays) { GenreAtlas.NONE }.isEmpty())
    }

    private fun summary(total: Int, accLearned: Double, accPrior: Double, before: Double?) =
        Gate.Summary(minOf(total, Gate.WINDOW), total, 0.5, 0.55, accLearned, accPrior, before, null)

    @Test
    fun `the model voice follows the model's real state`() {
        assertEquals(ModelVoice.GETTING_TO_KNOW, ModelVoice.of(null, false, 0L).kind)
        val few = ModelVoice.of(summary(120, 0.7, 0.6, null), true, 120L)
        assertEquals(ModelVoice.GETTING_TO_KNOW, few.kind, "under 300 graded plays, whatever the gate says")
        assertEquals(120, few.gradedPlays)
        assertNull(few.accNowPercent)
        val prior = ModelVoice.of(summary(900, 0.7, 0.61, 0.6), false, 0L)
        assertEquals(ModelVoice.STANDARD_RECIPE, prior.kind)
        assertNull(prior.accNowPercent, "no learned figure while the prior is in use")
        assertEquals(61, prior.accPriorPercent)
        val learning = ModelVoice.of(summary(2500, 0.684, 0.61, 0.612), true, 2400L)
        assertEquals(ModelVoice.LEARNING, learning.kind)
        assertEquals(2400L, learning.learnedFrom)
        assertEquals(68, learning.accNowPercent)
        assertEquals(61, learning.accBeforePercent)
        assertEquals(61, learning.accPriorPercent)
    }
}
