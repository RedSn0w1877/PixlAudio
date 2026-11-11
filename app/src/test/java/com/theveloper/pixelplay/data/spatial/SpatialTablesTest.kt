package com.theveloper.pixelplay.data.spatial

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
 *
 * The worker regenerates both files whenever its renderer is retuned (it did on 2026-11-11). Nothing here pins a
 * generated number, so taking a new pair needs no change to a test: copy both files to all three places
 * (`handoff/2026-11-10-plans/spatial/`, the app's assets, the unit-test resources). The stage's own test against the
 * golden file is what proves a table and a golden file belong together.
 */
class SpatialTablesTest {

    private val rates = listOf("44100", "48000")

    @Test
    fun `the shipped copies are the handoff copies byte for byte, with no carriage return`() {
        val table = SpatialFixtures.previewTableBytes()
        val golden = SpatialFixtures.goldenBytes()
        assertTrue(table.isNotEmpty() && golden.isNotEmpty())
        // Git on Windows would turn LF into CRLF and change every byte count: .gitattributes keeps these files LF.
        assertFalse(table.contains(CARRIAGE_RETURN), "the table in assets has a CR: .gitattributes must keep it LF")
        assertFalse(golden.contains(CARRIAGE_RETURN), "the golden file has a CR: .gitattributes must keep it LF")
        // The handoff folder holds the copies taken from the worker; a refresh that misses one place fails here.
        // (The folder is documentation: the comparison only runs where it exists.)
        SpatialFixtures.handoffPreviewTableBytes()?.let { assertArrayEquals(it, table, "assets table != handoff table") }
        SpatialFixtures.handoffGoldenBytes()?.let { assertArrayEquals(it, golden, "test golden != handoff golden") }
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

    private companion object {
        const val CARRIAGE_RETURN: Byte = 13
    }
}
