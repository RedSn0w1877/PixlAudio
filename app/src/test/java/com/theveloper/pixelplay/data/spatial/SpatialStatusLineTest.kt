package com.theveloper.pixelplay.data.spatial

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The player sheet's status line (cloud-audio-spec §6.5, "Every state has one"): one line per row of the spec's
 * table, and the shown status of §6.1 it is built from.
 */
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

    private fun line(
        audible: SpatialAudible,
        status: SpatialRenditionStatus,
        cloudRendersOn: Boolean = true,
        settings: SpatialSettings = on,
    ): SpatialStatusLine = SpatialStatusLine.of(settings, audible, status, cloudRendersOn)

    @Test
    fun `off or nothing loaded says nothing`() {
        val off = SpatialSettings(profile = null, cloud = true)
        assertEquals(
            SpatialStatusLine.NONE,
            line(heard(SpatialRendition.ON_DEVICE), SpatialRenditionStatus.QUEUED, settings = off)
        )
        assertEquals(SpatialStatusLine.NONE, line(SpatialAudible.NOTHING, SpatialRenditionStatus.NONE))
    }

    @Test
    fun `the on-device stage with the cloud render wanted is on the phone for now`() {
        val wanted = listOf(
            SpatialRenditionStatus.NONE,
            SpatialRenditionStatus.PREVIEW,
            SpatialRenditionStatus.QUEUED,
            SpatialRenditionStatus.PROCESSING,
        )
        for (status in wanted) {
            assertEquals(
                SpatialStatusLine.ON_PHONE_FOR_NOW,
                line(heard(SpatialRendition.ON_DEVICE), status),
                status.name
            )
        }
    }

    @Test
    fun `a render that is coming down, or is there and not faded to yet, is getting the cloud version`() {
        for (status in listOf(SpatialRenditionStatus.DOWNLOADING, SpatialRenditionStatus.READY)) {
            assertEquals(
                SpatialStatusLine.GETTING_CLOUD_VERSION,
                line(heard(SpatialRendition.ON_DEVICE), status),
                status.name
            )
        }
    }

    @Test
    fun `a failed render says so while the phone's version plays on`() {
        assertEquals(
            SpatialStatusLine.CLOUD_FAILED,
            line(heard(SpatialRendition.ON_DEVICE), SpatialRenditionStatus.FAILED)
        )
    }

    @Test
    fun `with cloud renders off the on-device stage is on the phone only, whatever the status`() {
        for (status in SpatialRenditionStatus.entries) {
            assertEquals(
                SpatialStatusLine.ON_PHONE_ONLY,
                line(heard(SpatialRendition.ON_DEVICE), status, cloudRendersOn = false),
                status.name
            )
        }
    }

    @Test
    fun `a cloud render that is audible is the cloud version`() {
        assertEquals(
            SpatialStatusLine.CLOUD_VERSION,
            line(heard(SpatialRendition.CLOUD_RENDER), SpatialRenditionStatus.READY)
        )
        // With cloud renders switched off a render already on the phone still plays.
        assertEquals(
            SpatialStatusLine.CLOUD_VERSION,
            line(heard(SpatialRendition.CLOUD_RENDER), SpatialRenditionStatus.READY, cloudRendersOn = false)
        )
    }

    @Test
    fun `a route the rule rejects is headphones only, whatever else is true`() {
        for (status in SpatialRenditionStatus.entries) {
            for (cloudRendersOn in listOf(true, false)) {
                assertEquals(
                    SpatialStatusLine.HEADPHONES_ONLY,
                    line(heard(blockedBy = SpatialBlock.ROUTE), status, cloudRendersOn),
                    "${status.name} $cloudRendersOn"
                )
            }
        }
    }

    @Test
    fun `Sing's instrumental mode pauses it`() {
        for (status in SpatialRenditionStatus.entries) {
            assertEquals(
                SpatialStatusLine.PAUSED_FOR_SING,
                line(heard(blockedBy = SpatialBlock.SING), status),
                status.name
            )
        }
    }

    @Test
    fun `another device and an unsupported format say nothing`() {
        for (block in listOf(SpatialBlock.REMOTE, SpatialBlock.FORMAT)) {
            assertEquals(
                SpatialStatusLine.NONE,
                line(heard(blockedBy = block), SpatialRenditionStatus.QUEUED),
                block.name
            )
        }
        // Plain stereo with a profile selected and nothing holding it back (the player has not caught up yet).
        assertEquals(SpatialStatusLine.NONE, line(heard(), SpatialRenditionStatus.NONE))
    }

    @Test
    fun `every line can be reached`() {
        val reached = HashSet<SpatialStatusLine>()
        val blocks: List<SpatialBlock?> = listOf<SpatialBlock?>(null) + SpatialBlock.entries
        for (settings in listOf(on, on.copy(profile = null))) {
            for (rendition in SpatialRendition.entries) {
                for (block in blocks) {
                    for (status in SpatialRenditionStatus.entries) {
                        for (cloudRendersOn in listOf(true, false)) {
                            reached += line(heard(rendition, block), status, cloudRendersOn, settings)
                        }
                    }
                }
            }
        }
        assertEquals(SpatialStatusLine.entries.toSet(), reached)
    }

    @Test
    fun `the shown status is the library's own, or preview while the on-device stage stands in`() {
        val cloudSide = listOf(
            SpatialRenditionStatus.QUEUED,
            SpatialRenditionStatus.PROCESSING,
            SpatialRenditionStatus.DOWNLOADING,
            SpatialRenditionStatus.READY,
            SpatialRenditionStatus.FAILED,
        )
        for (status in cloudSide) {
            assertEquals(status, SpatialRenditionStatus.shown(status, onDeviceAudible = true), status.name)
            assertEquals(status, SpatialRenditionStatus.shown(status, onDeviceAudible = false), status.name)
        }
        for (status in listOf(SpatialRenditionStatus.NONE, SpatialRenditionStatus.PREVIEW)) {
            assertEquals(
                SpatialRenditionStatus.PREVIEW,
                SpatialRenditionStatus.shown(status, onDeviceAudible = true),
                status.name
            )
            assertEquals(
                SpatialRenditionStatus.NONE,
                SpatialRenditionStatus.shown(status, onDeviceAudible = false),
                status.name
            )
        }
        assertEquals(
            listOf(SpatialRenditionStatus.QUEUED, SpatialRenditionStatus.PROCESSING, SpatialRenditionStatus.DOWNLOADING),
            SpatialRenditionStatus.entries.filter { it.isInFlight }
        )
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
        NoOpSpatialLibrary.markPlayed(video, null)
        NoOpSpatialLibrary.removeAll()
        assertEquals(SpatialRenditionStatus.NONE, NoOpSpatialLibrary.status(video, SpatialProfile.STUDIO).first())
        assertEquals(
            SpatialLibraryStats(songsReady = 0, bytesOnPhone = 0L, spentThisMonthMicroUsd = 0L, sentToday = 0),
            NoOpSpatialLibrary.stats.value
        )
        assertFalse(NoOpSpatialLibrary.cloudRendersOn.value)

        NoOpSpatialPlayback.apply(on)
        assertEquals(SpatialAudible.NOTHING, NoOpSpatialPlayback.audible.value)
    }
}
