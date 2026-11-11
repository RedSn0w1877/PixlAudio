package com.theveloper.pixelplay.data.spatial.playback

import android.media.AudioDeviceInfo
import com.theveloper.pixelplay.data.service.player.RenditionSwapTiming
import com.theveloper.pixelplay.data.spatial.SpatialAudible
import com.theveloper.pixelplay.data.spatial.SpatialBlock
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialRendition
import com.theveloper.pixelplay.data.spatial.SpatialRouteMode
import kotlin.math.pow

// The rules of cloud-audio-spec §6.1 and §6.3 the player applies, as pure functions (unit-tested on the JVM).

/** §6.1 `spatial.route`: what counts as headphones. Works on the raw [AudioDeviceInfo] type, not the coarse category. */
object SpatialRouteRule {
    /**
     * Wired headset or headphones, Bluetooth A2DP or LE audio (a Bluetooth speaker counts), a USB headset. Not: the
     * phone itself, line out, a USB device or dock (a DAC into speakers), HDMI, call audio (SCO), hearing aids or an LE
     * broadcast. [deviceType] null is the phone itself (or a route that could not be read).
     */
    fun isHeadphones(deviceType: Int?): Boolean = when (deviceType) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> false
    }

    /**
     * Whether the route lets Spatial audio play. `always` passes everything (another device then reports
     * [SpatialBlock.REMOTE]); `headphones` needs headphones on this phone, so Cast and Spotify Connect ([remote]) fail
     * it like a speaker does (integration map 1.2 item 9).
     */
    fun passes(mode: SpatialRouteMode, deviceType: Int?, remote: Boolean): Boolean = when (mode) {
        SpatialRouteMode.ALWAYS -> true
        SpatialRouteMode.HEADPHONES -> !remote && isHeadphones(deviceType)
    }
}

/** §6.3 "Level": while a spatial rendition is audible the normalisation gain gets the headroom back, capped at unity. */
object SpatialLevelRule {
    /** `10^(headroomDb / 20)`: 1.585 for the 4 dB of today's renders and table. */
    fun liftFactor(headroomDb: Float): Float = 10f.pow(headroomDb.coerceAtLeast(0f) / 20f)

    /** `min(1.0, gain * lift)`: never louder than unity, so nothing clips. */
    fun lifted(gain: Float, lift: Float): Float = (gain * lift).coerceIn(0f, 1f)

    /**
     * The lift for what is audible: the render's `headroomDb` while a cloud render plays, the table's for the on-device
     * stage, none for stereo.
     */
    fun liftFor(audible: SpatialAudible, renderHeadroomDb: Float?, tableHeadroomDb: Float?): Float = when (audible.rendition) {
        SpatialRendition.STEREO -> 1f
        SpatialRendition.ON_DEVICE -> liftFactor(tableHeadroomDb ?: 0f)
        SpatialRendition.CLOUD_RENDER -> liftFactor(renderHeadroomDb ?: tableHeadroomDb ?: 0f)
    }

    /**
     * The volumes a level change steps through: linear from [from] to [to] in [steps] equal steps (the last one exactly
     * [to]), so the change is a ramp like the stage's own.
     */
    fun ramp(from: Float, to: Float, steps: Int): FloatArray {
        val count = steps.coerceAtLeast(1)
        return FloatArray(count) { i -> if (i == count - 1) to else from + (to - from) * (i + 1) / count }
    }
}

/** What a swap of renditions mid-song should do (§6.3). */
enum class SpatialSwapPlan {
    /** Nothing to change. */
    KEEP,

    /** A render is on the second player but should not be: back to what the main player plays. */
    BACK_TO_MAIN,

    /** Look for a local render of the wanted profile and fade to it if the timing rules allow. */
    FIND_RENDER,
}

/** §6.3 "Playing": which file a song starts on, and when a render may take over mid-song. */
object SpatialRenditionRule {
    /**
     * At the start of a song: play the local render instead of the stream only when Spatial audio is active
     * ([active]), the render is the selected profile's for this video, and its file is there.
     */
    fun startsOnRender(
        active: Boolean,
        profile: SpatialProfile?,
        videoId: String?,
        render: SpatialRender?,
        fileExists: Boolean,
    ): Boolean = active && profile != null && videoId != null && render != null &&
        render.profile == profile && render.videoId == videoId && fileExists

    /**
     * A render may take over mid-song with at least 20 s left ([RenditionSwapTiming.MIN_REMAINING_MS]) and no seek or
     * track change in flight: the player is ready, the last seek has settled, no next-song transition runs. Sing's own
     * swap has no such rule and keeps none.
     */
    fun canSwapMidSong(
        positionMs: Long,
        durationMs: Long,
        playerReady: Boolean,
        msSinceSeek: Long,
        transitionRunning: Boolean,
    ): Boolean = nothingInFlight(playerReady, msSinceSeek, transitionRunning) && hasTimeLeft(positionMs, durationMs)

    /** At least 20 s of the song are left (a render arriving later is for the next play). */
    fun hasTimeLeft(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && durationMs - positionMs >= RenditionSwapTiming.MIN_REMAINING_MS

    /** No seek or track change in flight: the player is ready, the last seek settled, no next-song transition. */
    fun nothingInFlight(playerReady: Boolean, msSinceSeek: Long, transitionRunning: Boolean): Boolean =
        playerReady && !transitionRunning && msSinceSeek >= RenditionSwapTiming.SEEK_SETTLE_MS

    /**
     * What the second player should hold: [wanted] is the profile to hear (null: Spatial audio is not active),
     * [mainProfile] the profile of the render the main player itself plays (null: the stream), [shadowProfile] that of
     * the render on the second player (null: none).
     *
     * The main player's own file never changes mid-item (integration map 2.6 trap 1): a song that started on a render
     * finishes on it when Spatial audio goes off or the route fails (§6.3 allows it), and another profile is heard by
     * putting its render on the second player.
     */
    fun plan(wanted: SpatialProfile?, mainProfile: SpatialProfile?, shadowProfile: SpatialProfile?): SpatialSwapPlan = when {
        wanted == null || mainProfile == wanted ->
            if (shadowProfile != null) SpatialSwapPlan.BACK_TO_MAIN else SpatialSwapPlan.KEEP
        shadowProfile == wanted -> SpatialSwapPlan.KEEP
        else -> SpatialSwapPlan.FIND_RENDER
    }
}

/**
 * The arithmetic of a swap (§6.2 step 3): a render may have another sample rate than the stream, so the two are lined up
 * by media time, never by frame count across files.
 */
object SpatialSwapMath {
    /** Media time of [frames] at [sampleRate], in microseconds. */
    fun mediaTimeUs(frames: Long, sampleRate: Int): Long = frames * 1_000_000L / sampleRate

    /** The frame of [mediaTimeUs] at [sampleRate] (rounded to the nearest frame). */
    fun frameAt(mediaTimeUs: Long, sampleRate: Int): Long = (mediaTimeUs * sampleRate + 500_000L) / 1_000_000L

    /** A render's length in milliseconds, from its own frames and rate. */
    fun renderDurationMs(render: SpatialRender): Long =
        if (render.sampleRate > 0 && render.samples > 0) mediaTimeUs(render.samples, render.sampleRate) / 1_000L
        else render.durationMs

    /** The song's length for the 20 s rule: the player's when it knows it, else the render's. */
    fun durationMs(playerDurationMs: Long, render: SpatialRender): Long =
        if (playerDurationMs > 0) playerDurationMs else renderDurationMs(render)

    /**
     * Where the second player is sent to line up with the main player at [mainPositionMs]: the same media time plus
     * the [leadMs] a seek needs to land, kept inside the render.
     */
    fun alignTargetMs(mainPositionMs: Long, leadMs: Long, renderDurationMs: Long): Long {
        val target = (mainPositionMs + leadMs).coerceAtLeast(0L)
        return if (renderDurationMs > 0) target.coerceAtMost(renderDurationMs - 1) else target
    }
}

/** What the stage saw when a stream started (the playback thread's report, delivered on the main thread). */
data class SpatialStreamReport(
    /** The item's `mediaId` (`Song.id`). */
    val mediaId: String?,
    /** The render the stream is, or null for the song's stream. */
    val render: SpatialRender?,
    /** The stage takes the stream's format (§6.3: 44.1 or 48 kHz stereo). */
    val formatSupported: Boolean,
)

/** §6.3 and integration map 1.2 item 9: what the listener hears, as [SpatialAudible]. */
object SpatialAudibleRule {
    /**
     * @param profile the selected profile (null: off).
     * @param routeOk the route rule passes (with another device it fails unless the mode is `always`).
     * @param remote another device plays (Cast, Spotify Connect).
     * @param sing Sing's instrumental is audible.
     * @param stageReady the on-device table is loaded.
     * @param stream what the current item's stream is (null: not known yet).
     * @param shadow the render audible on the second player, if any.
     */
    fun of(
        songId: String?,
        videoId: String?,
        profile: SpatialProfile?,
        routeOk: Boolean,
        remote: Boolean,
        sing: Boolean,
        stageReady: Boolean,
        stream: SpatialStreamReport?,
        shadow: SpatialRender?,
    ): SpatialAudible {
        if (songId == null) return SpatialAudible.NOTHING
        val mainRender = stream?.render
        val block = when {
            profile == null -> null
            !routeOk -> SpatialBlock.ROUTE
            sing -> SpatialBlock.SING
            remote -> SpatialBlock.REMOTE
            mainRender == null && shadow == null && stream?.formatSupported == false -> SpatialBlock.FORMAT
            else -> null
        }
        return when {
            shadow != null -> SpatialAudible(songId, shadow.videoId, SpatialRendition.CLOUD_RENDER, shadow.profile, block)
            mainRender != null ->
                SpatialAudible(songId, mainRender.videoId, SpatialRendition.CLOUD_RENDER, mainRender.profile, block)
            profile != null && block == null && stageReady ->
                SpatialAudible(songId, videoId, SpatialRendition.ON_DEVICE, profile, null)
            else -> SpatialAudible(songId, videoId, SpatialRendition.STEREO, profile, block)
        }
    }
}
