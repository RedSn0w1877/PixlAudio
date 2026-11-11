package com.theveloper.pixelplay.data.spatial

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The on-device table in the app's assets and its golden file are the worker's own bytes (cloud-audio-spec §7: "the
 * apps carry byte-identical copies", "never hand-edit it"), and have the shape the on-device stage reads.
 */
class SpatialTablesTest {

    private val rates = listOf("44100", "48000")

    @Test
    fun `the shipped table and the golden file are the worker's bytes`() {
        // The SHA-256 and size of cloud/runpod-worker/schema/v1/spatial/ in the PixlAudio-iOS repo (audio-hq,
        // 2026-11-10; git blobs ddc4f23d and 18a855bd). When the worker regenerates the tables, copy both files again
        // (assets, test resources, handoff) and update these four values in the same change.
        val table = SpatialFixtures.previewTableBytes()
        assertEquals(73_284, table.size, "spatial_preview_v1.json changed size: was it edited, or checked out with CRLF?")
        assertEquals("37867463f2728a1d5991db62a175a1c6eec1eaf7ea99c374e51b5c39148c3d05", sha256(table))

        val golden = SpatialFixtures.goldenBytes()
        assertEquals(268_346, golden.size, "spatial_preview_golden_v1.json changed size: was it edited, or checked out with CRLF?")
        assertEquals("253b684abfe3fb1f3373f9c89e27488403b77ddf3e1326aae1c0f20a4822d37d", sha256(golden))
    }

    @Test
    fun `no copy carries a carriage return, and the handoff copies are the same bytes`() {
        val table = SpatialFixtures.previewTableBytes()
        val golden = SpatialFixtures.goldenBytes()
        assertFalse(table.contains(CARRIAGE_RETURN), "the table in assets has a CR: .gitattributes must keep it LF")
        assertFalse(golden.contains(CARRIAGE_RETURN), "the golden file has a CR: .gitattributes must keep it LF")
        // The handoff folder is documentation; the check only runs where it exists.
        SpatialFixtures.handoffPreviewTableBytes()?.let { assertArrayEquals(it, table) }
        SpatialFixtures.handoffGoldenBytes()?.let { assertArrayEquals(it, golden) }
    }

    @Test
    fun `the shipped table has every profile at both rates with the fields of the spec`() {
        val table = Json.parseToJsonElement(SpatialFixtures.previewTable()).jsonObject
        assertEquals(SpatialContract.PREVIEW_SCHEMA, table.getValue("schema").jsonPrimitive.content)
        assertEquals(SpatialContract.PREVIEW_VERSION, table.getValue("v").jsonPrimitive.int)
        assertEquals(SpatialContract.ENGINE_ID, table.getValue("engine").jsonPrimitive.content)
        assertEquals(4.0, table.getValue("headroomDb").jsonPrimitive.double, 1e-9)

        val profiles = table.getValue("profiles").jsonObject
        var largestDelay = 0
        for (profile in SpatialProfile.entries) {
            val byRate = profiles.getValue(profile.id).jsonObject
            assertEquals(rates, byRate.keys.toList(), profile.id)
            for (rate in rates) {
                val where = "${profile.id} @ $rate"
                val preset = byRate.getValue(rate).jsonObject
                val taps = preset.getValue("taps").jsonPrimitive.int
                assertTrue(taps > 0, where)
                assertEquals(taps, preset.getValue("direct").jsonArray.size, where)
                assertEquals(taps, preset.getValue("cross").jsonArray.size, where)
                assertTrue(preset.getValue("xoverHz").jsonPrimitive.double > 0.0, where)
                assertTrue(preset.getValue("lowDelay").jsonPrimitive.int >= 0, where)
                val gain = preset.getValue("gain").jsonPrimitive.double
                assertTrue(gain > 0.0 && gain < 1.0, where)
                for (ear in listOf("sparseL", "sparseR")) {
                    val sparse = preset.getValue(ear).jsonArray
                    assertTrue(sparse.isNotEmpty(), "$where $ear")
                    for (tap in sparse) {
                        // [delaySamples, gainFromLeft, gainFromRight]
                        val values = tap.jsonArray
                        assertEquals(3, values.size, "$where $ear")
                        val delay = values[0].jsonPrimitive.int
                        assertTrue(delay >= 0, "$where $ear")
                        largestDelay = maxOf(largestDelay, delay)
                    }
                }
            }
        }
        // §7: the delay ring is a power of two of at least the largest sparse delay + 1 ("about 8600 samples").
        assertTrue(largestDelay in 1 until 16_384, "largest sparse delay $largestDelay")
    }

    @Test
    fun `the golden file has the input and an output for every profile at both rates`() {
        val golden = Json.parseToJsonElement(SpatialFixtures.golden()).jsonObject
        val frames = golden.getValue("frames").jsonPrimitive.int
        assertEquals(1024, frames)
        assertEquals(1e-4, golden.getValue("tolerance").jsonPrimitive.double, 1e-12)

        val input = golden.getValue("input").jsonObject
        assertEquals(frames, input.getValue("left").jsonArray.size)
        assertEquals(frames, input.getValue("right").jsonArray.size)

        val output = golden.getValue("output").jsonObject
        for (profile in SpatialProfile.entries) {
            val byRate = output.getValue(profile.id).jsonObject
            assertEquals(rates, byRate.keys.toList(), profile.id)
            for (rate in rates) {
                val channels = byRate.getValue(rate).jsonObject
                assertEquals(frames, channels.getValue("left").jsonArray.size, "${profile.id} @ $rate")
                assertEquals(frames, channels.getValue("right").jsonArray.size, "${profile.id} @ $rate")
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CARRIAGE_RETURN: Byte = 13
    }
}
