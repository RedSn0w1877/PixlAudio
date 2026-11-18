package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.data.model.PlaybackQueueItemSnapshot
import com.theveloper.pixelplay.data.model.PlaybackQueueSnapshot
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The five brain fields of the saved queue (docs/brain-spec.md, 4.2): an engine item must come back
 * from a process restart as an engine item, and a queue saved by an earlier version must still load.
 */
class PlaybackQueueSnapshotBrainFieldsTest {

    // The app's own Json (di/AppModule.provideJson).
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val engineTag = BrainItemTag(
        surface = Surface.nameOf(Surface.AUTOPLAY),
        surfaceId = "-3000123456789012",
        recId = 446_464_000_001L,
        slot = 3,
        arm = Arm.nameOf(Arm.RADIO),
    )

    private fun item(id: String) = PlaybackQueueItemSnapshot(
        mediaId = id,
        uri = "spotify://0123456789abcdef012345",
        title = "Title $id",
        artist = "Artist",
        albumTitle = "Album",
        artworkUri = "https://example.invalid/a.jpg",
        durationMs = 200_000L,
    )

    @Test
    fun `save and restore keeps the five brain fields of an engine item`() {
        val saved = PlaybackQueueSnapshot(
            items = listOf(item("user"), item("engine").withBrainTag(engineTag)),
            currentMediaId = "user",
            currentIndex = 0,
            savedAtEpochMs = 1L,
        )
        val restored = json.decodeFromString<PlaybackQueueSnapshot>(json.encodeToString(PlaybackQueueSnapshot.serializer(), saved))
        assertEquals(saved, restored)

        val engine = restored.items[1]
        assertEquals("autoplay", engine.brainSurface)
        assertEquals("-3000123456789012", engine.brainSurfaceId)
        assertEquals(446_464_000_001L, engine.brainRecId)
        assertEquals(3, engine.brainSlot)
        assertEquals("RADIO", engine.brainArm)
        assertEquals(engineTag, engine.brainTag())
        assertTrue(engine.brainTag()!!.isEngineOwned)

        // The listener's own track has no tag and is not engine-owned after the restore either.
        assertNull(restored.items[0].brainTag())
    }

    @Test
    fun `a queue saved by an earlier version decodes unchanged`() {
        val old = """
            {"items":[{"mediaId":"-3000000000001","uri":"spotify://4uLU6hMCjMI75M1A2tKUQC","title":"Song",
            "artist":"Artist","albumTitle":"Album","artworkUri":null,"durationMs":201000}],
            "currentMediaId":"-3000000000001","currentIndex":0,"currentPositionMs":42000,"playWhenReady":true,
            "repeatMode":0,"shuffleEnabled":false,"savedAtEpochMs":1700000000000}
        """.trimIndent()
        val snapshot = json.decodeFromString<PlaybackQueueSnapshot>(old)
        val item = snapshot.items.single()
        assertEquals("-3000000000001", item.mediaId)
        assertEquals(201_000L, item.durationMs)
        assertNull(item.brainSurface)
        assertNull(item.brainSurfaceId)
        assertNull(item.brainRecId)
        assertNull(item.brainSlot)
        assertNull(item.brainArm)
        assertNull(item.brainTag())
        assertEquals(42_000L, snapshot.currentPositionMs)
    }

    @Test
    fun `a queue without engine items is written exactly as before`() {
        val encoded = json.encodeToString(PlaybackQueueItemSnapshot.serializer(), item("user"))
        assertFalse(encoded.contains("brain"), encoded)
        // An untagged item stays untagged.
        assertEquals(item("user"), item("user").withBrainTag(null))
        assertEquals(item("user"), item("user").withBrainTag(BrainItemTag()))
    }

    @Test
    fun `a play the listener started keeps its surface without becoming engine-owned`() {
        // Stage 1b tags plays the UI starts ("playlist", "album", ...) with a surface and no arm.
        val tag = BrainItemTag(surface = Surface.nameOf(Surface.PLAYLIST), surfaceId = "pl_abc")
        val restored = json.decodeFromString<PlaybackQueueItemSnapshot>(
            json.encodeToString(PlaybackQueueItemSnapshot.serializer(), item("user").withBrainTag(tag))
        )
        assertEquals(tag, restored.brainTag())
        assertFalse(restored.brainTag()!!.isEngineOwned)
        assertNull(restored.brainRecId, "0 is not written")
        assertNull(restored.brainSlot, "-1 is not written")
        assertNull(restored.brainArm)
    }

    @Test
    fun `the reason line and the rest of a pick's context survive a restart`() {
        // Stage 1c: "kill the app mid-autoplay and reopen: the reasons are still there".
        val tag = engineTag.copy(
            whyKey = "radio_fans", whyArg = "d4vd", primaryArtist = "Clovis Reyes", retrQ = 640, radioRank = 7,
        )
        val restored = json.decodeFromString<PlaybackQueueItemSnapshot>(
            json.encodeToString(PlaybackQueueItemSnapshot.serializer(), item("engine").withBrainTag(tag))
        )
        assertEquals(tag, restored.brainTag())
        assertEquals("radio_fans", restored.brainWhyKey)
        assertEquals("d4vd", restored.brainWhyArg)
        assertEquals("Clovis Reyes", restored.brainPrimaryArtist)
        assertEquals(640, restored.brainRetrQ)
        assertEquals(7, restored.brainRadioRank)
        assertNull(restored.brainChosen, "false is not written")
        assertTrue(restored.brainTag()!!.isEngineOwned)
    }

    @Test
    fun `a Home row's card the listener played is a pick, but theirs`() {
        // It carries an arm (it was a recommendation, and is logged as one) yet it does not sit under
        // the Autoplay line and the engine may not reorder it: the listener chose to play the row.
        val tag = BrainItemTag(
            surface = Surface.nameOf(Surface.HOME_SHELF), surfaceId = "because_1f", recId = 12L, slot = 0,
            arm = Arm.nameOf(Arm.RADIO), whyKey = "because_listened", whyArg = "Espresso", chosen = true,
        )
        assertFalse(tag.isEngineOwned)
        val restored = json.decodeFromString<PlaybackQueueItemSnapshot>(
            json.encodeToString(PlaybackQueueItemSnapshot.serializer(), item("card").withBrainTag(tag))
        )
        assertEquals(true, restored.brainChosen)
        assertEquals(tag, restored.brainTag())
        assertFalse(restored.brainTag()!!.isEngineOwned)
    }

    @Test
    fun `an arm of NONE is not engine-owned`() {
        assertFalse(BrainItemTag(arm = Arm.nameOf(Arm.NONE)).isEngineOwned)
        assertFalse(BrainItemTag(arm = "nonsense").isEngineOwned)
        assertTrue(BrainItemTag(arm = "FAV").isEngineOwned)
        assertTrue(BrainItemTag().isEmpty)
    }
}
