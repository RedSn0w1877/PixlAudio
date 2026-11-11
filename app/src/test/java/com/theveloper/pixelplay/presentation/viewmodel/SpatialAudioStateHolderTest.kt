package com.theveloper.pixelplay.presentation.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.theveloper.pixelplay.data.cloudstudio.NoBuiltInCloudConfig
import com.theveloper.pixelplay.data.spatial.SpatialAudible
import com.theveloper.pixelplay.data.spatial.SpatialBlock
import com.theveloper.pixelplay.data.spatial.SpatialLibrary
import com.theveloper.pixelplay.data.spatial.SpatialLibraryEvent
import com.theveloper.pixelplay.data.spatial.SpatialLibraryStats
import com.theveloper.pixelplay.data.spatial.SpatialPlayback
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialRendition
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus
import com.theveloper.pixelplay.data.spatial.SpatialRouteMode
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import com.theveloper.pixelplay.data.spatial.SpatialSettingsRepository
import com.theveloper.pixelplay.data.spatial.SpatialStatusLine
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [SpatialAudioStateHolder] is what the UI of Spatial audio reads and calls (cloud-audio-spec §6.5): it stores what
 * the person picks and joins the settings, what is audible and the library's status, with no rule of its own.
 */
class SpatialAudioStateHolderTest {

    private val video = "J7p4bzqLvCw"

    private class FakeLibrary : SpatialLibrary {
        val statuses = MutableStateFlow<Map<Pair<String, SpatialProfile>, SpatialRenditionStatus>>(emptyMap())
        val renders = HashMap<Pair<String, SpatialProfile>, SpatialRender>()
        var removeAllCalls = 0

        /** Both switches of §6.1 on, as in a build with built-in keys. */
        override val cloudRendersOn = MutableStateFlow(true)

        override suspend fun localRender(videoId: String, profile: SpatialProfile): SpatialRender? = renders[videoId to profile]
        override fun request(songId: String, videoId: String, profile: SpatialProfile) = Unit
        override fun status(videoId: String, profile: SpatialProfile): Flow<SpatialRenditionStatus> =
            statuses.map { it[videoId to profile] ?: SpatialRenditionStatus.NONE }.distinctUntilChanged()
        override val events = MutableSharedFlow<SpatialLibraryEvent>()
        override val stats = MutableStateFlow(SpatialLibraryStats())
        override fun markPlayed(videoId: String, profile: SpatialProfile) = Unit
        override suspend fun removeAll() {
            removeAllCalls++
        }
    }

    private class FakePlayback : SpatialPlayback {
        override val audible = MutableStateFlow(SpatialAudible.NOTHING)
        override fun apply(settings: SpatialSettings) = Unit
    }

    private class Fixture(
        val holder: SpatialAudioStateHolder,
        val repository: SpatialSettingsRepository,
        val library: FakeLibrary,
        val playback: FakePlayback,
    )

    private inline fun TestScope.withHolder(block: (Fixture) -> Unit) {
        val dir = Files.createTempDirectory("spatial-holder-test")
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { dir.resolve("settings.preferences_pb").toFile() }
            )
            val repository = SpatialSettingsRepository(store, NoBuiltInCloudConfig)
            val library = FakeLibrary()
            val playback = FakePlayback()
            block(Fixture(SpatialAudioStateHolder(repository, library, playback, backgroundScope), repository, library, playback))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun heard(rendition: SpatialRendition, blockedBy: SpatialBlock? = null) = SpatialAudible(
        songId = "42",
        videoId = video,
        rendition = rendition,
        profile = SpatialProfile.WIDE.takeIf { rendition != SpatialRendition.STEREO },
        blockedBy = blockedBy,
    )

    @Test
    fun `it starts on the defaults and stores what the person picks`() = runTest {
        withHolder { fixture ->
            val holder = fixture.holder
            assertEquals(SpatialSettings.defaults(builtInCloudKeys = false), holder.settings.value)

            holder.selectProfile(SpatialProfile.WIDE)
            assertEquals(SpatialProfile.WIDE, holder.settings.first { it.profile != null }.profile)
            assertEquals(SpatialProfile.WIDE, fixture.repository.current().profile)

            holder.setCloud(true)
            holder.setPrepareAhead(false)
            holder.setRouteMode(SpatialRouteMode.ALWAYS)
            holder.setStorageMb(2048)
            holder.setMonthlyCapUsd(null)
            holder.setDailyAhead(5)
            val expected = SpatialSettings(
                profile = SpatialProfile.WIDE,
                routeMode = SpatialRouteMode.ALWAYS,
                cloud = true,
                prepareAhead = false,
                storageMb = 2048,
                monthlyCapUsd = null,
                dailyAhead = 5,
            )
            assertEquals(expected, holder.settings.first { it == expected })
            assertEquals(expected, fixture.repository.current())

            holder.selectProfile(null)
            assertNull(holder.settings.first { it.profile == null }.profile)
        }
    }

    @Test
    fun `storedSettings is null until the store has been read, then follows it`() = runTest {
        withHolder { fixture ->
            val holder = fixture.holder
            // The full player's button waits on this instead of showing the defaults for a frame.
            assertNull(holder.storedSettings.value)
            assertEquals(SpatialSettings.defaults(builtInCloudKeys = false), holder.storedSettings.first { it != null })

            holder.selectProfile(SpatialProfile.NIGHT)
            assertEquals(SpatialProfile.NIGHT, holder.storedSettings.first { it?.profile != null }?.profile)
            assertEquals(holder.settings.first { it.profile == SpatialProfile.NIGHT }, holder.storedSettings.value)
        }
    }

    @Test
    fun `the status and the status line follow the playing song, the profile and the library`() = runTest {
        withHolder { fixture ->
            val holder = fixture.holder
            fixture.repository.setProfile(SpatialProfile.WIDE)
            fixture.repository.setCloud(true)
            // Another profile's render does not count for the selected one.
            fixture.library.statuses.value = mapOf(
                (video to SpatialProfile.WIDE) to SpatialRenditionStatus.QUEUED,
                (video to SpatialProfile.NIGHT) to SpatialRenditionStatus.READY,
            )
            fixture.playback.audible.value = heard(SpatialRendition.ON_DEVICE)

            assertEquals(SpatialRenditionStatus.QUEUED, holder.currentStatus.first { it != SpatialRenditionStatus.NONE })
            assertEquals(SpatialStatusLine.ON_PHONE_FOR_NOW, holder.statusLine.first { it != SpatialStatusLine.NONE })

            // The render arrives and the player fades to it.
            fixture.library.statuses.value = mapOf((video to SpatialProfile.WIDE) to SpatialRenditionStatus.READY)
            fixture.playback.audible.value = heard(SpatialRendition.CLOUD_RENDER)
            assertEquals(SpatialRenditionStatus.READY, holder.currentStatus.first { it == SpatialRenditionStatus.READY })
            assertEquals(SpatialStatusLine.CLOUD_VERSION, holder.statusLine.first { it == SpatialStatusLine.CLOUD_VERSION })

            // The headphones are unplugged.
            fixture.playback.audible.value = heard(SpatialRendition.STEREO, blockedBy = SpatialBlock.ROUTE)
            assertEquals(SpatialStatusLine.HEADPHONES_ONLY, holder.statusLine.first { it == SpatialStatusLine.HEADPHONES_ONLY })

            // The person picks another profile: that profile's own status shows, not the old one's.
            holder.selectProfile(SpatialProfile.NIGHT)
            fixture.library.statuses.value = mapOf(
                (video to SpatialProfile.WIDE) to SpatialRenditionStatus.FAILED,
                (video to SpatialProfile.NIGHT) to SpatialRenditionStatus.PROCESSING,
            )
            assertEquals(
                SpatialRenditionStatus.PROCESSING,
                holder.currentStatus.first { it == SpatialRenditionStatus.PROCESSING }
            )

            // Off: nothing to say, whatever plays.
            holder.selectProfile(null)
            assertEquals(SpatialStatusLine.NONE, holder.statusLine.first { it == SpatialStatusLine.NONE })
            assertEquals(SpatialRenditionStatus.NONE, holder.currentStatus.first { it == SpatialRenditionStatus.NONE })
        }
    }

    @Test
    fun `the playing song is a preview until the cloud has something, and the line follows both switches`() = runTest {
        withHolder { fixture ->
            val holder = fixture.holder
            fixture.repository.setProfile(SpatialProfile.WIDE)
            fixture.playback.audible.value = heard(SpatialRendition.ON_DEVICE)

            // The library has nothing under way: the on-device stage stands in, and the render is wanted.
            assertEquals(SpatialRenditionStatus.PREVIEW, holder.currentStatus.first { it != SpatialRenditionStatus.NONE })
            assertEquals(SpatialStatusLine.ON_PHONE_FOR_NOW, holder.statusLine.first { it != SpatialStatusLine.NONE })

            // One of the two switches goes off (the library knows both): no cloud version is coming.
            fixture.library.cloudRendersOn.value = false
            assertFalse(holder.cloudRendersOn.value)
            assertEquals(SpatialStatusLine.ON_PHONE_ONLY, holder.statusLine.first { it == SpatialStatusLine.ON_PHONE_ONLY })

            // Back on, and the shared library has the render: it comes down.
            fixture.library.cloudRendersOn.value = true
            fixture.library.statuses.value = mapOf((video to SpatialProfile.WIDE) to SpatialRenditionStatus.DOWNLOADING)
            assertEquals(
                SpatialRenditionStatus.DOWNLOADING,
                holder.currentStatus.first { it == SpatialRenditionStatus.DOWNLOADING }
            )
            assertEquals(
                SpatialStatusLine.GETTING_CLOUD_VERSION,
                holder.statusLine.first { it == SpatialStatusLine.GETTING_CLOUD_VERSION }
            )

            // The library reports that the render cannot start: the month's limit is reached. The line says so.
            fixture.library.statuses.value = mapOf((video to SpatialProfile.WIDE) to SpatialRenditionStatus.CAP_REACHED)
            assertEquals(
                SpatialStatusLine.CLOUD_LIMIT_REACHED,
                holder.statusLine.first { it == SpatialStatusLine.CLOUD_LIMIT_REACHED }
            )

            // It fails: the phone's version plays on.
            fixture.library.statuses.value = mapOf((video to SpatialProfile.WIDE) to SpatialRenditionStatus.FAILED)
            assertEquals(SpatialStatusLine.CLOUD_FAILED, holder.statusLine.first { it == SpatialStatusLine.CLOUD_FAILED })

            // Sing's instrumental mode comes on.
            fixture.playback.audible.value = heard(SpatialRendition.STEREO, blockedBy = SpatialBlock.SING)
            assertEquals(SpatialStatusLine.PAUSED_FOR_SING, holder.statusLine.first { it == SpatialStatusLine.PAUSED_FOR_SING })
        }
    }

    @Test
    fun `the current render shows once it is ready, for Developer mode's diagnostics`() = runTest {
        withHolder { fixture ->
            val holder = fixture.holder
            val render = SpatialRender(
                videoId = video,
                profile = SpatialProfile.WIDE,
                file = File("sp-wide.flac"),
                sampleRate = 48_000,
                samples = 9_696_000,
                durationMs = 202_000,
                headroomDb = 4f,
                sourceCodec = "opus",
                stemMode = "stems6",
                integratedLufs = -13.8f,
                sourceLufs = -9.8f,
            )
            fixture.library.renders[video to SpatialProfile.WIDE] = render
            fixture.repository.setProfile(SpatialProfile.WIDE)
            fixture.playback.audible.value = heard(SpatialRendition.ON_DEVICE)
            assertNull(holder.currentRender.value)

            fixture.library.statuses.value = mapOf((video to SpatialProfile.WIDE) to SpatialRenditionStatus.READY)
            assertEquals(render, holder.currentRender.first { it != null })
        }
    }

    @Test
    fun `stats and what is audible come straight from the two sides, and removing asks the library`() = runTest {
        withHolder { fixture ->
            val holder = fixture.holder
            fixture.library.stats.value = SpatialLibraryStats(songsReady = 12, bytesOnPhone = 345_000_000L)
            assertEquals(SpatialLibraryStats(songsReady = 12, bytesOnPhone = 345_000_000L), holder.stats.value)

            fixture.playback.audible.value = heard(SpatialRendition.ON_DEVICE)
            assertTrue(holder.audible.value.isSpatial)

            assertEquals(SpatialRenditionStatus.NONE, holder.statusOf(null, SpatialProfile.WIDE).first())
            assertEquals(SpatialRenditionStatus.NONE, holder.statusOf(video, null).first())
            fixture.library.statuses.value = mapOf((video to SpatialProfile.HALL) to SpatialRenditionStatus.PROCESSING)
            assertEquals(SpatialRenditionStatus.PROCESSING, holder.statusOf(video, SpatialProfile.HALL).first())

            holder.removeDownloadedRenders()
            // The removal runs on the holder's own scope: wait for it through a setting written after it.
            holder.setDailyAhead(7)
            holder.settings.first { it.dailyAhead == 7 }
            assertEquals(1, fixture.library.removeAllCalls)
        }
    }
}
