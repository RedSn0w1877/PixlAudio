package com.theveloper.pixelplay.presentation.components.redesign

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeWordplayTest {

    @Test
    fun `time buckets follow the clock`() {
        assertThat(HomeWordplay.timeBucket(5)).isEqualTo(HomeWordplay.TimeBucket.MORNING)
        assertThat(HomeWordplay.timeBucket(11)).isEqualTo(HomeWordplay.TimeBucket.MORNING)
        assertThat(HomeWordplay.timeBucket(12)).isEqualTo(HomeWordplay.TimeBucket.AFTERNOON)
        assertThat(HomeWordplay.timeBucket(16)).isEqualTo(HomeWordplay.TimeBucket.AFTERNOON)
        assertThat(HomeWordplay.timeBucket(17)).isEqualTo(HomeWordplay.TimeBucket.EVENING)
        assertThat(HomeWordplay.timeBucket(21)).isEqualTo(HomeWordplay.TimeBucket.EVENING)
        assertThat(HomeWordplay.timeBucket(22)).isEqualTo(HomeWordplay.TimeBucket.LATE_NIGHT)
        assertThat(HomeWordplay.timeBucket(0)).isEqualTo(HomeWordplay.TimeBucket.LATE_NIGHT)
        assertThat(HomeWordplay.timeBucket(4)).isEqualTo(HomeWordplay.TimeBucket.LATE_NIGHT)
    }

    @Test
    fun `a real streak wins over the time of day`() {
        assertThat(HomeWordplay.greetingSet(9, HomeWordplay.STREAK_MIN_PLAYS)).isEqualTo(HomeWordplay.GreetingSet.STREAK)
        assertThat(HomeWordplay.greetingSet(9, HomeWordplay.STREAK_MIN_PLAYS - 1)).isEqualTo(HomeWordplay.GreetingSet.MORNING)
        assertThat(HomeWordplay.greetingSet(23, 0)).isEqualTo(HomeWordplay.GreetingSet.LATE_NIGHT)
    }

    @Test
    fun `the pick is deterministic for one day and bucket`() {
        val a = HomeWordplay.greeting(20_000L, 13, 0) { 3 }
        val b = HomeWordplay.greeting(20_000L, 15, 2) { 3 }
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `no line repeats on two days in a row, for every set size`() {
        for (size in 2..9) {
            for (salt in 0..60) {
                var previous = HomeWordplay.rotate(19_000L, size, salt)
                for (day in 19_001L..19_400L) {
                    val index = HomeWordplay.rotate(day, size, salt)
                    assertThat(index).isIn(0 until size)
                    assertThat(index).isNotEqualTo(previous)
                    previous = index
                }
            }
        }
    }

    @Test
    fun `every line comes round`() {
        for (size in 2..7) {
            val seen = (0L until size.toLong()).map { HomeWordplay.rotate(20_100L + it, size, salt = 5) }.toSet()
            assertThat(seen).hasSize(size)
        }
    }

    @Test
    fun `a set of one line or none is index zero`() {
        assertThat(HomeWordplay.rotate(123L, 1, 4)).isEqualTo(0)
        assertThat(HomeWordplay.rotate(123L, 0, 4)).isEqualTo(0)
        assertThat(HomeWordplay.section(-5L, HomeWordplay.Section.FRESH, 1)).isEqualTo(0)
    }

    @Test
    fun `negative days still land in range`() {
        assertThat(HomeWordplay.rotate(-1L, 3, 2)).isIn(0 until 3)
        assertThat(HomeWordplay.subLine(-400L, 3)).isIn(0 until 3)
    }
}
