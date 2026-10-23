package com.theveloper.pixelplay.data.service.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The timing maths of the Sing crossfade, run on virtual time: the ramp the two players follow, the wait for
 * the planned start, and the seek lead that lines the instrumental up with the song.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeRampTest {

    // ---- the request ---------------------------------------------------------------------------

    @Test
    fun `a request defaults to an immediate start with the longest fade`() {
        val request = CrossfadeRequest()

        assertNull(request.startAtPositionMs)
        assertEquals(CrossfadeRamp.DEFAULT_FADE_MS, request.fadeMs)
        assertEquals(CrossfadeRamp.DEFAULT_MAX_WAIT_MS, request.maxWaitMs)
    }

    @Test
    fun `the fade is held between the shortest and the longest`() {
        assertEquals(300L, CrossfadeRamp.clampFadeMs(0L))
        assertEquals(300L, CrossfadeRamp.clampFadeMs(299L))
        assertEquals(450L, CrossfadeRamp.clampFadeMs(450L))
        assertEquals(700L, CrossfadeRamp.clampFadeMs(701L))
        assertEquals(700L, CrossfadeRamp.clampFadeMs(60_000L))
    }

    // ---- the ramp ------------------------------------------------------------------------------

    private class Volumes(var out: Float, var inn: Float) {
        /** Out plus in, after each pair of writes: equal gain keeps it at the base level. */
        val sums = mutableListOf<Float>()
        val outs = mutableListOf<Float>()
        val ins = mutableListOf<Float>()
        fun setOut(v: Float) {
            out = v
            outs += v
        }

        fun setIn(v: Float) {
            inn = v
            ins += v
            sums += out + inn
        }
    }

    @Test
    fun `the instrumental rises to the level the song had, not to full volume`() = runTest {
        // ReplayGain had the song at 0.6: the instrumental must end at 0.6, the song at silence.
        val volumes = Volumes(out = 0.6f, inn = 0f)

        val completed = CrossfadeRamp.ramp(
            durationMs = 500, outFrom = 0.6f, outTo = 0f, inFrom = 0f, inTo = 0.6f,
            setOut = volumes::setOut, setIn = volumes::setIn,
            stillValid = { true }, clock = { currentTime },
        )

        assertTrue(completed)
        assertEquals(0f, volumes.out)
        assertEquals(0.6f, volumes.inn)
        // The fade takes its length (to the next 16 ms step), no more.
        assertTrue(currentTime in 500L..(500L + CrossfadeRamp.STEP_MS), "took $currentTime ms")
    }

    @Test
    fun `the two volumes always add up to the base level`() = runTest {
        val volumes = Volumes(out = 0.6f, inn = 0f)

        CrossfadeRamp.ramp(
            durationMs = 700, outFrom = 0.6f, outTo = 0f, inFrom = 0f, inTo = 0.6f,
            setOut = volumes::setOut, setIn = volumes::setIn,
            stillValid = { true }, clock = { currentTime },
        )

        assertTrue(volumes.sums.size > 10, "a 700 ms fade takes many small steps")
        volumes.sums.forEach { assertEquals(0.6f, it, 0.0001f) }
    }

    @Test
    fun `the song only ever gets quieter and the instrumental louder`() = runTest {
        val volumes = Volumes(out = 1f, inn = 0f)

        CrossfadeRamp.ramp(
            durationMs = 300, outFrom = 1f, outTo = 0f, inFrom = 0f, inTo = 1f,
            setOut = volumes::setOut, setIn = volumes::setIn,
            stillValid = { true }, clock = { currentTime },
        )

        assertEquals(volumes.outs.sortedDescending(), volumes.outs)
        assertEquals(volumes.ins.sorted(), volumes.ins)
    }

    @Test
    fun `a fade that starts part way still ends exactly on its targets`() = runTest {
        // Back to the original while the instrumental had not quite reached the base level.
        val volumes = Volumes(out = 0.5f, inn = 0.2f)

        val completed = CrossfadeRamp.ramp(
            durationMs = 400, outFrom = 0.5f, outTo = 0f, inFrom = 0.2f, inTo = 0.6f,
            setOut = volumes::setOut, setIn = volumes::setIn,
            stillValid = { true }, clock = { currentTime },
        )

        assertTrue(completed)
        assertEquals(0.6f, volumes.inn)
        assertEquals(0f, volumes.out)
    }

    @Test
    fun `a fade that stops being valid leaves the volumes where they were`() = runTest {
        val volumes = Volumes(out = 1f, inn = 0f)

        val completed = CrossfadeRamp.ramp(
            durationMs = 700, outFrom = 1f, outTo = 0f, inFrom = 0f, inTo = 1f,
            setOut = volumes::setOut, setIn = volumes::setIn,
            // The song changes 200 ms in.
            stillValid = { currentTime < 200 }, clock = { currentTime },
        )

        assertFalse(completed)
        // Stopped half way: neither target was written, so the caller restores the volumes itself.
        assertTrue(volumes.out > 0f)
        assertTrue(volumes.inn < 1f)
    }

    @Test
    fun `a fade that is invalid from the start writes nothing`() = runTest {
        val volumes = Volumes(out = 1f, inn = 0f)

        val completed = CrossfadeRamp.ramp(
            durationMs = 700, outFrom = 1f, outTo = 0f, inFrom = 0f, inTo = 1f,
            setOut = volumes::setOut, setIn = volumes::setIn,
            stillValid = { false }, clock = { currentTime },
        )

        assertFalse(completed)
        assertTrue(volumes.outs.isEmpty())
        assertTrue(volumes.ins.isEmpty())
    }

    @Test
    fun `a slow frame does not stretch the fade`() = runTest {
        // The clock jumps 400 ms between two looks (a busy main thread): the fade still ends on time.
        var now = 0L
        val volumes = Volumes(out = 1f, inn = 0f)
        var looks = 0

        val completed = CrossfadeRamp.ramp(
            durationMs = 700, outFrom = 1f, outTo = 0f, inFrom = 0f, inTo = 1f,
            setOut = volumes::setOut, setIn = volumes::setIn,
            stillValid = { true },
            clock = {
                looks++
                if (looks > 2) now += 400
                now
            },
        )

        assertTrue(completed)
        assertEquals(0f, volumes.out)
        assertEquals(1f, volumes.inn)
        assertTrue(looks <= 5, "progress comes from the clock, so a few looks are enough")
    }

    // ---- the wait for the planned start --------------------------------------------------------

    @Test
    fun `the start waits until the song reaches the planned position`() = runTest {
        val songStart = 10_000L

        val reached = CrossfadeRamp.awaitPosition(
            position = { songStart + currentTime },
            targetMs = 10_250L,
            maxWaitMs = 700L,
            stillValid = { true },
            clock = { currentTime },
        )

        assertTrue(reached)
        assertTrue(currentTime >= 250L, "waited for the position, not less (was $currentTime)")
        assertTrue(currentTime < 270L, "and not much more (was $currentTime)")
    }

    @Test
    fun `a position already passed starts at once`() = runTest {
        val reached = CrossfadeRamp.awaitPosition(
            position = { 12_000L },
            targetMs = 10_000L,
            maxWaitMs = 700L,
            stillValid = { true },
            clock = { currentTime },
        )

        assertTrue(reached)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a song that never gets there starts anyway at the hard stop`() = runTest {
        // Paused or seeking: the position stands still.
        val reached = CrossfadeRamp.awaitPosition(
            position = { 5_000L },
            targetMs = 9_000L,
            maxWaitMs = 700L,
            stillValid = { true },
            clock = { currentTime },
        )

        assertTrue(reached)
        assertTrue(currentTime >= 700L, "gave the plan its whole wait (was $currentTime)")
        assertTrue(currentTime < 720L, "and no more (was $currentTime)")
    }

    @Test
    fun `waiting for the start gives up when the switch is no longer valid`() = runTest {
        val reached = CrossfadeRamp.awaitPosition(
            position = { 5_000L },
            targetMs = 9_000L,
            maxWaitMs = 700L,
            stillValid = { currentTime < 100 },
            clock = { currentTime },
        )

        assertFalse(reached)
        assertTrue(currentTime < 120L)
    }

    // ---- lining the instrumental up ------------------------------------------------------------

    @Test
    fun `a seek that landed behind is aimed further ahead next time`() {
        // The instrumental ended 80 ms behind the song: aim 80 ms ahead.
        assertEquals(80L, CrossfadeRamp.nextSeekLead(previousLeadMs = 0L, driftMs = -80L))
        // Aimed 80 ahead and still 10 behind: aim 10 further.
        assertEquals(90L, CrossfadeRamp.nextSeekLead(previousLeadMs = 80L, driftMs = -10L))
    }

    @Test
    fun `a seek that overshot is aimed back`() {
        assertEquals(50L, CrossfadeRamp.nextSeekLead(previousLeadMs = 80L, driftMs = 30L))
    }

    @Test
    fun `the lead never goes negative or past the cap`() {
        assertEquals(0L, CrossfadeRamp.nextSeekLead(previousLeadMs = 0L, driftMs = 50L))
        assertEquals(CrossfadeRamp.MAX_SEEK_LEAD_MS, CrossfadeRamp.nextSeekLead(previousLeadMs = 390L, driftMs = -100L))
    }
}
