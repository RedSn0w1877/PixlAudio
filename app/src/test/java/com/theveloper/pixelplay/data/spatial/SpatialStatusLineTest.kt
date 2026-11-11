package com.theveloper.pixelplay.data.spatial

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The player sheet's status line (cloud-audio-spec §6.5) only ever says something that is true. */
class SpatialStatusLineTest {

    private val video = "J7p4bzqLvCw"
    private val on = SpatialSettings(profile = SpatialProfile.STUDIO, cloud = true)

    private fun heard(
        rendition: SpatialRendition = SpatialRendition.STEREO,
        blockedBy: SpatialBlock? = null,
    ) = SpatialAudible(
        songId = "42",
        videoId = video,
        rendition = rendition,
        profile = SpatialProfile.STUDIO.takeIf { rendition != SpatialRendition.STEREO },
        blockedBy = blockedBy,
    )

    @Test
    fun `off or nothing loaded says nothing`() {
        val off = SpatialSettings(profile = null, cloud = true)
        assertEquals(
            SpatialStatusLine.NONE,
            SpatialStatusLine.of(off, heard(SpatialRendition.ON_DEVICE), SpatialRenditionStatus.QUEUED)
        )
        assertEquals(
            SpatialStatusLine.NONE,
            SpatialStatusLine.of(on, SpatialAudible.NOTHING, SpatialRenditionStatus.NONE)
        )
    }

    @Test
    fun `the on-device stage with the cloud render on its way is on the phone for now`() {
        for (status in listOf(SpatialRenditionStatus.PREVIEW, SpatialRenditionStatus.QUEUED, SpatialRenditionStatus.PROCESSING)) {
            assertEquals(
                SpatialStatusLine.ON_PHONE_FOR_NOW,
                SpatialStatusLine.of(on, heard(SpatialRendition.ON_DEVICE), status),
                status.name
            )
        }
    }

    @Test
    fun `no cloud version is promised when none is coming`() {
        val phoneOnly = on.copy(cloud = false)
        assertEquals(
            SpatialStatusLine.NONE,
            SpatialStatusLine.of(phoneOnly, heard(SpatialRendition.ON_DEVICE), SpatialRenditionStatus.PREVIEW)
        )
        for (status in listOf(SpatialRenditionStatus.NONE, SpatialRenditionStatus.FAILED, SpatialRenditionStatus.READY)) {
            assertEquals(
                SpatialStatusLine.NONE,
                SpatialStatusLine.of(on, heard(SpatialRendition.ON_DEVICE), status),
                status.name
            )
        }
    }

    @Test
    fun `a cloud render that is audible is the cloud version`() {
        assertEquals(
            SpatialStatusLine.CLOUD_VERSION,
            SpatialStatusLine.of(on, heard(SpatialRendition.CLOUD_RENDER), SpatialRenditionStatus.READY)
        )
        // With the cloud switched off a render already on the phone still plays.
        assertEquals(
            SpatialStatusLine.CLOUD_VERSION,
            SpatialStatusLine.of(on.copy(cloud = false), heard(SpatialRendition.CLOUD_RENDER), SpatialRenditionStatus.READY)
        )
    }

    @Test
    fun `a route the rule rejects is headphones only, whatever the render's state`() {
        for (status in SpatialRenditionStatus.entries) {
            assertEquals(
                SpatialStatusLine.HEADPHONES_ONLY,
                SpatialStatusLine.of(on, heard(blockedBy = SpatialBlock.ROUTE), status),
                status.name
            )
        }
    }

    @Test
    fun `Sing, another device and an unsupported format say nothing`() {
        for (block in listOf(SpatialBlock.SING, SpatialBlock.REMOTE, SpatialBlock.FORMAT)) {
            assertEquals(
                SpatialStatusLine.NONE,
                SpatialStatusLine.of(on, heard(blockedBy = block), SpatialRenditionStatus.QUEUED),
                block.name
            )
        }
    }

    @Test
    fun `what is audible knows whether it is spatial`() {
        assertFalse(SpatialAudible.NOTHING.isSpatial)
        assertFalse(heard(SpatialRendition.STEREO).isSpatial)
        assertTrue(heard(SpatialRendition.ON_DEVICE).isSpatial)
        assertTrue(heard(SpatialRendition.CLOUD_RENDER).isSpatial)
    }

    @Test
    fun `before the cloud and player sides exist nothing is spatial and nothing is stored`() = runTest {
        assertNull(NoOpSpatialLibrary.localRender(video, SpatialProfile.STUDIO))
        assertNull(NoOpSpatialLibrary.localEnhanced(video))
        NoOpSpatialLibrary.request("42", video, SpatialProfile.STUDIO)
        NoOpSpatialLibrary.markPlayed(video, SpatialProfile.STUDIO)
        NoOpSpatialLibrary.removeAll()
        assertEquals(SpatialRenditionStatus.NONE, NoOpSpatialLibrary.status(video, SpatialProfile.STUDIO).first())
        assertEquals(SpatialLibraryStats(songsReady = 0, bytesOnPhone = 0L), NoOpSpatialLibrary.stats.value)

        NoOpSpatialPlayback.apply(on)
        assertEquals(SpatialAudible.NOTHING, NoOpSpatialPlayback.audible.value)
    }
}
