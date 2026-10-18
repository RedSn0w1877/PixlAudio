package com.theveloper.pixelplay.data.haptics

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.preferences.MusicHapticsFollows
import org.junit.jupiter.api.Test

class HapticPlannerTest {

    private class Recorder : HapticPlanner.Output {
        val taps = mutableListOf<Pair<Float, Boolean>>()
        val rumbles = mutableListOf<Float>()
        override fun tap(scale: Float, sharp: Boolean) { taps += scale to sharp }
        override fun rumble(scale: Float) { rumbles += scale }
    }

    private var now = 0L
    private val out = Recorder()
    private val planner = HapticPlanner(out) { now }

    @Test
    fun everythingFollowsKickSnareAndBass() {
        planner.follows = MusicHapticsFollows.EVERYTHING
        planner.onKick(0.8f)
        now += 100
        planner.onSnare(0.8f)
        now += 100
        planner.onBass(0.9f)
        assertThat(out.taps).hasSize(2)
        assertThat(out.taps[0].second).isTrue()
        assertThat(out.taps[1].second).isFalse()
        assertThat(out.rumbles).hasSize(1)
    }

    @Test
    fun beatIsTapsOnly() {
        planner.follows = MusicHapticsFollows.BEAT
        planner.onKick(0.8f)
        now += 100
        planner.onSnare(0.8f)
        now += 100
        planner.onBass(0.9f)
        assertThat(out.taps).hasSize(2)
        assertThat(out.rumbles).isEmpty()
    }

    @Test
    fun bassIsKickTapsAndRumble() {
        planner.follows = MusicHapticsFollows.BASS
        planner.onKick(0.8f)
        now += 100
        planner.onSnare(0.8f)
        now += 100
        planner.onBass(0.9f)
        assertThat(out.taps).hasSize(1)
        assertThat(out.rumbles).hasSize(1)
    }

    @Test
    fun capsAtTwentyFiveEventsPerSecond() {
        repeat(1000) {
            planner.onKick(1f)
            now += 1
        }
        // 1000 ms at one event per 40 ms.
        assertThat(out.taps.size).isAtMost(25)
        assertThat(out.taps.size).isAtLeast(20)
    }

    @Test
    fun strengthScalesAndClamps() {
        planner.strengthPercent = 200
        planner.onKick(0.8f)
        assertThat(out.taps.last().first).isEqualTo(1f)
        now += 100
        planner.strengthPercent = 20
        planner.onKick(0.5f)
        assertThat(out.taps.last().first).isWithin(0.001f).of(0.1f)
    }

    @Test
    fun quietBassStaysSilent() {
        planner.onBass(0.2f)
        assertThat(out.rumbles).isEmpty()
    }

    @Test
    fun resetClearsTheLimiter() {
        planner.onKick(1f)
        planner.reset()
        planner.onKick(1f)
        assertThat(out.taps).hasSize(2)
    }
}
