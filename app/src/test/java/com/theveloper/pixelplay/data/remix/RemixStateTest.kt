package com.theveloper.pixelplay.data.remix

import com.theveloper.pixelplay.data.remix.model.FilterState
import com.theveloper.pixelplay.data.remix.model.LoopState
import com.theveloper.pixelplay.data.remix.model.RemixState
import com.theveloper.pixelplay.data.remix.model.ReverbState
import com.theveloper.pixelplay.data.remix.model.StemKind
import com.theveloper.pixelplay.data.remix.model.StemState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `sanitized()` is the boundary between untrusted JSON — a saved preset, or whatever the AI
 * endpoint returns — and a DSP thread where a NaN in a feedback path is permanent. These tests
 * are the reason that function exists.
 */
class RemixStateTest {

    @Test
    fun `round trips through json`() {
        val original = RemixState(
            songId = "song-1",
            loop = LoopState(startMs = 1_000, endMs = 9_000),
            rate = 0.85f,
            stems = listOf(
                StemState(kind = StemKind.VOCALS, x = 1.2f, y = 0f, z = -2f),
                StemState(kind = StemKind.DRUMS, x = -1f, y = 0.5f, z = -1f),
            ),
            filter = FilterState(mode = "bp", cutoffHz = 900f, q = 4f),
            reverb = ReverbState(mix = 0.4f, rt60 = 3f),
        )

        val decoded = RemixState.decode(RemixState.encode(original))

        assertNotNull(decoded)
        assertEquals(original.songId, decoded!!.songId)
        assertEquals(original.rate, decoded.rate)
        assertEquals(2, decoded.stems.size)
        assertEquals(StemKind.VOCALS, decoded.stems[0].kind)
        assertEquals("bp", decoded.filter.mode)
    }

    @Test
    fun `unknown fields from a newer version are ignored`() {
        val json = """{"v":1,"songId":"x","rate":1.0,"somethingNew":{"a":1},"stems":[]}"""
        assertNotNull(RemixState.decode(json))
    }

    @Test
    fun `malformed json yields null instead of throwing`() {
        assertNull(RemixState.decode("not json at all"))
        assertNull(RemixState.decode(""))
    }

    @Test
    fun `hostile numbers are clamped before they can reach the dsp`() {
        val hostile = RemixState(
            songId = "x",
            rate = 50f,
            stems = listOf(
                StemState(kind = "vocals", x = 1e9f, y = Float.NaN, z = Float.NEGATIVE_INFINITY, gainDb = 400f),
            ),
            filter = FilterState(mode = "sudo-rm", cutoffHz = -5f, q = 1e9f, bits = 99, decim = -3),
            reverb = ReverbState(mix = 17f, rt60 = 1e9f, damp = -2f, preDelayMs = 99_999f),
            masterGain = Float.NaN,
        ).sanitized()

        assertEquals(RemixState.MAX_RATE, hostile.rate)
        val stem = hostile.stems.single()
        assertEquals(RemixState.MAX_DISTANCE_M, stem.x)
        // NaN and ±infinity fall back to the field's default rather than saturating at the limit:
        // a garbage value should not be interpreted as "the user meant the extreme".
        assertEquals(0f, stem.y)
        assertEquals(-1f, stem.z)
        assertEquals(12f, stem.gainDb)
        assertEquals("lp", hostile.filter.mode)
        assertTrue(hostile.filter.cutoffHz >= 200f)
        assertTrue(hostile.filter.q <= 12f)
        assertEquals(16, hostile.filter.bits)
        assertEquals(1, hostile.filter.decim)
        assertEquals(1f, hostile.reverb.mix)
        assertEquals(6f, hostile.reverb.rt60)
        assertEquals(0f, hostile.reverb.damp)
        assertEquals(120f, hostile.reverb.preDelayMs)
        assertEquals(1f, hostile.masterGain)
    }

    @Test
    fun `loop region is clamped to something playable`() {
        val tooLong = LoopState(startMs = 0, endMs = 10 * 60 * 1000).sanitized()
        assertEquals(RemixState.MAX_LOOP_MS, tooLong.lengthMs)

        val inverted = LoopState(startMs = 5_000, endMs = 1_000).sanitized()
        assertTrue(inverted.lengthMs >= RemixState.MIN_LOOP_MS) { "inverted region must be repaired" }

        val shortFade = LoopState(startMs = 0, endMs = 4_000, xfadeMs = 1).sanitized()
        assertEquals(RemixState.MIN_XFADE_MS, shortFade.xfadeMs)
    }

    @Test
    fun `more stems than the engine has voices are dropped`() {
        val many = RemixState(stems = List(12) { StemState(kind = "vocals") }).sanitized()
        assertEquals(RemixState.MAX_STEMS, many.stems.size)
    }
}
