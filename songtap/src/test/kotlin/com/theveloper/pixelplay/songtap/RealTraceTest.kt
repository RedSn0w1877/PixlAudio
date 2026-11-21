package com.theveloper.pixelplay.songtap

import com.theveloper.pixelplay.songtap.RealTraces.PIXEL_IDLE
import com.theveloper.pixelplay.songtap.RealTraces.PIXEL_IDLE_CONSTANT
import com.theveloper.pixelplay.songtap.RealTraces.PIXEL_SESSION
import com.theveloper.pixelplay.songtap.RealTraces.PIXEL_TOUCH_1_FIRST_RISE_MS
import com.theveloper.pixelplay.songtap.RealTraces.PIXEL_TOUCH_2_FIRST_RISE_MS
import com.theveloper.pixelplay.songtap.RealTraces.Pt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The owner's two real traces (2026-11-20) through the pipeline of the app: raw reading + rssiOffsetDb, then the detector with a profile.
 * What is real and what is filled in is written at the top of [RealTraces].
 */
class RealTraceTest {

    private val normal = SongTapSensitivity.NORMAL
    private val high = SongTapSensitivity.HIGH

    @Test
    fun `iphone trace, normal profile, no fire at rest and a fire at the touch within 600 ms of the jump`() {
        val run = RealTraces.run(RealTraces.IPHONE_FIRST_TAP, offsetDb = 0, profile = normal)
        assertEquals(1, run.fires.size, "fires: ${run.fires.map { it.tMs to it.reason }}")
        val fire = run.fires[0]
        // Nothing before the jump (the rest at about -64 and the approach to -48 do not fire).
        assertTrue(fire.tMs >= RealTraces.IPHONE_TAP_1_JUMP_MS, "fired at ${fire.tMs}, before the jump")
        assertTrue(fire.tMs - RealTraces.IPHONE_TAP_1_JUMP_MS <= 600L, "fired ${fire.tMs - RealTraces.IPHONE_TAP_1_JUMP_MS} ms after the jump")
        // The real phone fired at 20947990 (dwell); the hold or the dwell may be first here, never later than that plus a reading.
        assertTrue(fire.tMs <= RealTraces.IPHONE_TAP_1_FIRED_MS + RealTraces.CADENCE_MS, "fired at ${fire.tMs}")
    }

    @Test
    fun `iphone idle near, flat around minus 60, never fires with either profile`() {
        for (p in SongTapSensitivity.entries) {
            val run = RealTraces.run(RealTraces.IPHONE_IDLE, offsetDb = 0, profile = p)
            assertTrue(run.fires.isEmpty(), "${p.code}: ${run.fires.map { it.tMs to it.reason }}")
            assertTrue(run.near.all { it.second }, "resting 20 to 30 cm is inside the near gate")
        }
    }

    @Test
    fun `iphone rest, the hold rule never fires at rest`() {
        // The hold level is -45, the iPhone rests at -55 to -65.
        for (p in SongTapSensitivity.entries) {
            val run = RealTraces.run(RealTraces.IPHONE_IDLE, offsetDb = 0, profile = p)
            assertTrue(run.fires.none { it.reason == FireReason.HOLD })
        }
    }

    @Test
    fun `pixel trace, offset -15, high profile, no fire at rest, a fire within 400 ms of the jump, none inside the cooldown`() {
        val run = RealTraces.run(PIXEL_SESSION, offsetDb = -15, profile = high)
        val fires = run.fires
        assertEquals(2, fires.size, "fires: ${fires.map { it.tMs to it.reason }}")
        // Nothing during the ten seconds of rest or the approach before the first rise.
        assertTrue(fires[0].tMs >= PIXEL_TOUCH_1_FIRST_RISE_MS, "first fire ${fires[0].tMs}")
        assertTrue(fires[0].tMs - PIXEL_TOUCH_1_FIRST_RISE_MS <= 400L, "first touch: fired ${fires[0].tMs - PIXEL_TOUCH_1_FIRST_RISE_MS} ms after the first rise")
        assertTrue(fires[1].tMs >= PIXEL_TOUCH_2_FIRST_RISE_MS, "second fire ${fires[1].tMs}")
        assertTrue(fires[1].tMs - PIXEL_TOUCH_2_FIRST_RISE_MS <= 400L, "second touch: fired ${fires[1].tMs - PIXEL_TOUCH_2_FIRST_RISE_MS} ms after the first rise")
        // No re-fire inside the 1.2 s cooldown after the first fire (the phones stay touching for another half second or so).
        assertTrue(fires.drop(1).none { it.tMs - fires[0].tMs < high.cooldownMs })
        // Resting at -40..-48 raw is -55..-63 normalised: inside the near gate (-70) but never a tap.
        val rest = RealTraces.run(PIXEL_IDLE, offsetDb = -15, profile = high)
        assertTrue(rest.fires.isEmpty())
        assertTrue(rest.near.all { it.second })
    }

    @Test
    fun `pixel trace, offset -15, normal profile also fires at both touches and not at rest`() {
        val run = RealTraces.run(PIXEL_SESSION, offsetDb = -15, profile = normal)
        assertEquals(2, run.fires.size, "fires: ${run.fires.map { it.tMs to it.reason }}")
    }

    @Test
    fun `pixel resting never fires the hold once it is normalised`() {
        for (p in SongTapSensitivity.entries) {
            assertTrue(RealTraces.run(PIXEL_IDLE, -15, p).fires.isEmpty())
            assertTrue(RealTraces.run(PIXEL_IDLE_CONSTANT, -15, p).fires.isEmpty())
        }
    }

    @Test
    fun `pixel trace with no offset shows the old problem, the hold fires at rest and the gate is crossed far away`() {
        // The owner's Pixel smoothed -44 at rest, a bit above the -45 hold level: unnormalised, it would fire at rest.
        val run = RealTraces.run(PIXEL_IDLE_CONSTANT, offsetDb = 0, profile = high)
        assertEquals(1, run.fires.size)
        assertEquals(FireReason.HOLD, run.fires[0].reason)
        // On the cyclic rest the hold also fires (the smoothed signal sits around -44), repeatedly over a long rest.
        val cyc = RealTraces.run(PIXEL_IDLE, offsetDb = 0, profile = high)
        assertTrue(cyc.fires.isNotEmpty(), "unnormalised resting Pixel fires")
        // Near while far: the same pose that reads -76 on the iPhone (the lowest of its log, phones apart) reads about -61 raw on the Pixel,
        // which a -70 gate counts as near with no offset, and not near with -15.
        val apartRaw = -76 + 15
        fun nearAfter(offset: Int): Boolean {
            var s = DetectorState.INITIAL
            for (i in 0 until 12) s = SongTapDetector.step(s, RssiSample(i * 125L, apartRaw + offset), -1L, high).state
            return s.near
        }
        assertTrue(nearAfter(0), "without the offset the Pixel counts a phone far away as near")
        assertFalse(nearAfter(-15), "with the offset it is the iPhone's -76: outside the gate")
        // And the same gate on the list: -76 is listed on the iPhone scale (list gate -85) but a raw -61 is the same phone and the offset keeps both consistent.
        assertEquals(apartRaw - 15, -76)
    }

    @Test
    fun `densify keeps every listed point and only adds readings between far apart ones`() {
        val listed = RealTraces.IPHONE_TAP_1_LISTED
        val dense = RealTraces.IPHONE_TAP_1
        assertTrue(dense.containsAll(listed))
        assertTrue(dense.size > listed.size)
        assertEquals(dense.sortedBy { it.t }, dense)
        // The rest of the first touch of the Pixel is reproduced exactly where the log lists it.
        assertTrue(RealTraces.PIXEL_TOUCH_1.containsAll(RealTraces.PIXEL_TOUCH_1_LISTED))
        val p = Pt(0, 0)
        assertEquals(listOf(p), RealTraces.densify(listOf(p)))
    }
}
