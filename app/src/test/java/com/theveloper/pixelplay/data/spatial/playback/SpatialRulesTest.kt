package com.theveloper.pixelplay.data.spatial.playback

import android.media.AudioDeviceInfo
import com.theveloper.pixelplay.data.spatial.SpatialAudible
import com.theveloper.pixelplay.data.spatial.SpatialBlock
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialRendition
import com.theveloper.pixelplay.data.spatial.SpatialRouteMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The pure rules of cloud-audio-spec §6.1 and §6.3 the player applies (§8: route rule, bypass-for-render rule). */
class SpatialRulesTest {

    private fun render(
        profile: SpatialProfile = SpatialProfile.STUDIO,
        videoId: String = "J7p4bzqLvCw",
        sampleRate: Int = 48_000,
        seconds: Long = 202,
        headroomDb: Float = 4f,
    ) = SpatialRender(
        videoId = videoId,
        profile = profile,
        file = File("cloud_audio/$videoId/sp-${profile.id}.flac"),
        sampleRate = sampleRate,
        samples = sampleRate * seconds,
        durationMs = seconds * 1_000,
        headroomDb = headroomDb,
    )

    // ── Route (§6.1) ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `headphones are wired, Bluetooth A2DP or LE, and USB headsets`() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, // a Bluetooth speaker counts
            AudioDeviceInfo.TYPE_USB_HEADSET,
        ).forEach { assertTrue(SpatialRouteRule.isHeadphones(it), "type $it") }
        listOf(
            null, // the phone itself
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        ).forEach { assertFalse(SpatialRouteRule.isHeadphones(it), "type $it") }
    }

    @Test
    fun `always passes every route, headphones mode needs headphones on this phone`() {
        assertTrue(SpatialRouteRule.passes(SpatialRouteMode.ALWAYS, null, remote = false))
        assertTrue(SpatialRouteRule.passes(SpatialRouteMode.ALWAYS, null, remote = true))
        assertTrue(SpatialRouteRule.passes(SpatialRouteMode.HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, remote = false))
        assertFalse(SpatialRouteRule.passes(SpatialRouteMode.HEADPHONES, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, remote = false))
        assertFalse(SpatialRouteRule.passes(SpatialRouteMode.HEADPHONES, null, remote = false))
        // Cast or Spotify Connect: not this phone's headphones.
        assertFalse(SpatialRouteRule.passes(SpatialRouteMode.HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, remote = true))
    }

    // ── Level (§6.3) ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the lift is plus 4 dB capped at unity`() {
        val lift = SpatialLevelRule.liftFactor(4f)
        assertEquals(1.585f, lift, 0.001f)
        assertEquals(0.5f * 1.585f, SpatialLevelRule.lifted(0.5f, lift), 0.001f)
        assertEquals(1f, SpatialLevelRule.lifted(0.7f, lift)) // 0.7 * 1.585 > 1: capped
        assertEquals(1f, SpatialLevelRule.lifted(1f, lift))
        assertEquals(0.4f, SpatialLevelRule.lifted(0.4f, SpatialLevelRule.liftFactor(0f)))
        assertEquals(1f, SpatialLevelRule.liftFactor(-3f), "never a cut")
    }

    @Test
    fun `the lift follows what is heard and whose headroom applies`() {
        val stereo = SpatialAudible(songId = "s", rendition = SpatialRendition.STEREO)
        val onDevice = SpatialAudible(songId = "s", rendition = SpatialRendition.ON_DEVICE, profile = SpatialProfile.STUDIO)
        val cloud = SpatialAudible(songId = "s", rendition = SpatialRendition.CLOUD_RENDER, profile = SpatialProfile.STUDIO)
        assertEquals(1f, SpatialLevelRule.liftFor(stereo, renderHeadroomDb = 4f, tableHeadroomDb = 4f))
        assertEquals(SpatialLevelRule.liftFactor(4f), SpatialLevelRule.liftFor(onDevice, renderHeadroomDb = 6f, tableHeadroomDb = 4f))
        assertEquals(SpatialLevelRule.liftFactor(6f), SpatialLevelRule.liftFor(cloud, renderHeadroomDb = 6f, tableHeadroomDb = 4f))
    }

    @Test
    fun `a level change ramps in equal steps and ends exactly on the target`() {
        assertArrayEquals(floatArrayOf(0.6f, 0.7f, 0.8f, 0.9f, 1f), SpatialLevelRule.ramp(0.5f, 1f, 5), 1e-6f)
        assertArrayEquals(floatArrayOf(0.3f), SpatialLevelRule.ramp(1f, 0.3f, 0))
    }

    // ── Which file a song starts on (§6.3) ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a song starts on its render only with Spatial active and the profile's render there`() {
        val studio = render()
        assertTrue(SpatialRenditionRule.startsOnRender(true, SpatialProfile.STUDIO, "J7p4bzqLvCw", studio, fileExists = true))
        assertFalse(SpatialRenditionRule.startsOnRender(false, SpatialProfile.STUDIO, "J7p4bzqLvCw", studio, fileExists = true))
        assertFalse(SpatialRenditionRule.startsOnRender(true, SpatialProfile.HALL, "J7p4bzqLvCw", studio, fileExists = true))
        assertFalse(SpatialRenditionRule.startsOnRender(true, SpatialProfile.STUDIO, "otherVideo1", studio, fileExists = true))
        assertFalse(SpatialRenditionRule.startsOnRender(true, SpatialProfile.STUDIO, "J7p4bzqLvCw", studio, fileExists = false))
        assertFalse(SpatialRenditionRule.startsOnRender(true, SpatialProfile.STUDIO, "J7p4bzqLvCw", null, fileExists = false))
        assertFalse(SpatialRenditionRule.startsOnRender(true, null, "J7p4bzqLvCw", studio, fileExists = true))
    }

    @Test
    fun `the stage bypasses exactly the items that play a render`() {
        val studio = render()
        val bySpotifyId = mapOf("4uLU6hMCjMI75M1A2tKUQC" to studio)
        val byUri = mapOf("file:///data/cloud_audio/J7p4bzqLvCw/sp-studio.flac" to studio)
        assertEquals(studio, SpatialItemRendition.of("spotify://4uLU6hMCjMI75M1A2tKUQC", bySpotifyId, byUri))
        assertEquals(studio, SpatialItemRendition.of("spotify://4uLU6hMCjMI75M1A2tKUQC?x=1", bySpotifyId, byUri))
        assertNull(SpatialItemRendition.of("spotify://0000000000000000000000", bySpotifyId, byUri))
        assertEquals(studio, SpatialItemRendition.of("file:///data/cloud_audio/J7p4bzqLvCw/sp-studio.flac", bySpotifyId, byUri))
        assertNull(SpatialItemRendition.of("file:///cache/audio_cache/song.webm", bySpotifyId, byUri), "the listening cache is the stream")
        assertNull(SpatialItemRendition.of("http://127.0.0.1:4242/spotify/x", bySpotifyId, byUri))
        assertNull(SpatialItemRendition.of(null, bySpotifyId, byUri))
    }

    // ── Mid-song swaps (§6.3) ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a render takes over mid-song only with 20 s left and nothing in flight`() {
        fun can(position: Long, duration: Long = 200_000, ready: Boolean = true, sinceSeek: Long = 5_000, transition: Boolean = false) =
            SpatialRenditionRule.canSwapMidSong(position, duration, ready, sinceSeek, transition)
        assertTrue(can(position = 60_000))
        assertTrue(can(position = 180_000)) // exactly 20 s left
        assertFalse(can(position = 180_001))
        assertFalse(can(position = 60_000, ready = false), "buffering after a seek")
        assertFalse(can(position = 60_000, sinceSeek = 200), "a seek in flight")
        assertFalse(can(position = 60_000, transition = true), "a track change in flight")
        assertFalse(can(position = 0, duration = 0), "unknown length")
    }

    @Test
    fun `the plan follows the wanted profile and never changes the main player's file`() {
        val studio = SpatialProfile.STUDIO
        val hall = SpatialProfile.HALL
        // Stream on the main player.
        assertEquals(SpatialSwapPlan.FIND_RENDER, SpatialRenditionRule.plan(studio, null, null))
        assertEquals(SpatialSwapPlan.KEEP, SpatialRenditionRule.plan(studio, null, studio))
        assertEquals(SpatialSwapPlan.FIND_RENDER, SpatialRenditionRule.plan(hall, null, studio), "profile change")
        assertEquals(SpatialSwapPlan.BACK_TO_MAIN, SpatialRenditionRule.plan(null, null, studio), "Spatial off or route")
        assertEquals(SpatialSwapPlan.KEEP, SpatialRenditionRule.plan(null, null, null))
        // The main player started on a render.
        assertEquals(SpatialSwapPlan.KEEP, SpatialRenditionRule.plan(studio, studio, null))
        assertEquals(SpatialSwapPlan.KEEP, SpatialRenditionRule.plan(null, studio, null), "finishes the song on it")
        assertEquals(SpatialSwapPlan.FIND_RENDER, SpatialRenditionRule.plan(hall, studio, null))
        assertEquals(SpatialSwapPlan.BACK_TO_MAIN, SpatialRenditionRule.plan(studio, studio, hall))
    }

    @Test
    fun `renders line up by media time across sample rates`() {
        // 60 s into a 48 kHz stream is the same instant of a 44.1 kHz render.
        val streamFrame = 2_880_000L
        val mediaUs = SpatialSwapMath.mediaTimeUs(streamFrame, 48_000)
        assertEquals(60_000_000L, mediaUs)
        assertEquals(2_646_000L, SpatialSwapMath.frameAt(mediaUs, 44_100))
        // And back, frame-exact both ways.
        assertEquals(streamFrame, SpatialSwapMath.frameAt(SpatialSwapMath.mediaTimeUs(2_646_000L, 44_100), 48_000))
        // A render's length comes from its own frames and rate.
        val iphoneRender = render(sampleRate = 44_100, seconds = 202)
        assertEquals(202_000L, SpatialSwapMath.renderDurationMs(iphoneRender))
        // The 20 s rule uses the player's length when known, the render's otherwise.
        assertEquals(203_500L, SpatialSwapMath.durationMs(203_500L, iphoneRender))
        assertEquals(202_000L, SpatialSwapMath.durationMs(-9_223_372_036_854_775_807L, iphoneRender))
        // The seek target is the main player's media time plus the lead, kept inside the render.
        assertEquals(60_150L, SpatialSwapMath.alignTargetMs(60_000L, 150L, 202_000L))
        assertEquals(201_999L, SpatialSwapMath.alignTargetMs(201_990L, 400L, 202_000L))
        assertEquals(0L, SpatialSwapMath.alignTargetMs(-30L, 0L, 202_000L))
    }

    // ── What is heard (§6.3, integration map 1.2 item 9) ────────────────────────────────────────────────────────

    @Test
    fun `audible names the rendition and the first block`() {
        val stream = SpatialStreamReport("s", render = null, formatSupported = true)
        fun of(
            profile: SpatialProfile? = SpatialProfile.STUDIO,
            routeOk: Boolean = true,
            remote: Boolean = false,
            sing: Boolean = false,
            report: SpatialStreamReport? = stream,
            shadow: SpatialRender? = null,
        ) = SpatialAudibleRule.of("s", "J7p4bzqLvCw", profile, routeOk, remote, sing, stageReady = true, stream = report, shadow = shadow)

        assertEquals(SpatialAudible.NOTHING, SpatialAudibleRule.of(null, null, SpatialProfile.STUDIO, true, false, false, true, null, null))
        assertEquals(SpatialRendition.ON_DEVICE, of().rendition)
        assertEquals(SpatialRendition.STEREO, of(profile = null).rendition)
        assertNull(of(profile = null).blockedBy)
        assertEquals(SpatialBlock.ROUTE, of(routeOk = false).blockedBy)
        assertEquals(SpatialBlock.ROUTE, of(routeOk = false, sing = true, remote = true).blockedBy, "the first of them")
        assertEquals(SpatialBlock.SING, of(sing = true).blockedBy)
        assertEquals(SpatialBlock.REMOTE, of(remote = true).blockedBy)
        val unsupported = of(report = stream.copy(formatSupported = false))
        assertEquals(SpatialBlock.FORMAT, unsupported.blockedBy)
        assertEquals(SpatialRendition.STEREO, unsupported.rendition)
        val onMain = of(report = stream.copy(render = render()))
        assertEquals(SpatialRendition.CLOUD_RENDER, onMain.rendition)
        assertNull(onMain.blockedBy)
        val onShadow = of(shadow = render(profile = SpatialProfile.HALL))
        assertEquals(SpatialRendition.CLOUD_RENDER, onShadow.rendition)
        assertEquals(SpatialProfile.HALL, onShadow.profile)
        assertTrue(onShadow.isSpatial)
    }
}
