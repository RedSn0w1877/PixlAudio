package com.theveloper.pixelplay.data.spatial

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The profile ids are a contract with the worker and the iOS app (cloud-audio-spec §2): they never drift. */
class SpatialProfileTest {

    private val specIds = listOf("studio", "wide", "intimate", "hall", "surround", "night")

    @Test
    fun `the profile ids are the spec's, in the spec's order`() {
        assertEquals(specIds, SpatialProfile.entries.map { it.id })
    }

    @Test
    fun `every profile is found again by its id`() {
        for (profile in SpatialProfile.entries) {
            assertEquals(profile, SpatialProfile.fromId(profile.id))
        }
    }

    @Test
    fun `off, nothing and unknown ids are not a profile`() {
        assertEquals("off", SpatialProfile.OFF_ID)
        assertNull(SpatialProfile.fromId(SpatialProfile.OFF_ID))
        assertNull(SpatialProfile.fromId(null))
        assertNull(SpatialProfile.fromId(""))
        assertNull(SpatialProfile.fromId("Studio"))
        assertNull(SpatialProfile.fromId("cinema"))
    }

    @Test
    fun `studio is the default profile`() {
        assertEquals(SpatialProfile.STUDIO, SpatialProfile.DEFAULT)
    }

    @Test
    fun `the library version, the engine id and the table's name are the spec's`() {
        assertEquals(1, SpatialContract.LIB_V)
        assertEquals("pixl-spatial-1", SpatialContract.ENGINE_ID)
        assertEquals("spatial_preview_v1.json", SpatialContract.PREVIEW_ASSET)
        assertEquals("pixl.spatial.preview", SpatialContract.PREVIEW_SCHEMA)
        assertEquals(1, SpatialContract.PREVIEW_VERSION)
    }

    @Test
    fun `the shipped table and the golden file have exactly these profiles`() {
        val table = Json.parseToJsonElement(SpatialFixtures.previewTable()).jsonObject
        assertEquals(specIds, table.getValue("profiles").jsonObject.keys.toList())

        val golden = Json.parseToJsonElement(SpatialFixtures.golden()).jsonObject
        assertEquals(specIds, golden.getValue("output").jsonObject.keys.toList())
    }
}
