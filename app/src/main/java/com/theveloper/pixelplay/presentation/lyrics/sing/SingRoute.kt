package com.theveloper.pixelplay.presentation.lyrics.sing

import com.theveloper.pixelplay.data.cloudstudio.CloudLimits
import com.theveloper.pixelplay.data.cloudstudio.CloudSingUnavailable

/**
 * Where a Sing tap gets its instrumental from. Pure, so the decision (and the words that explain a
 * fallback) are unit-tested.
 *
 * 1. An instrumental is already on this phone: switch to it now.
 * 2. The cloud can take the song: start a Sing job there and keep playing the original with its
 *    karaoke lyrics; the song switches at a word gap when the instrumental lands.
 * 3. Otherwise remove the vocals on this phone, and say why the cloud was not used.
 *
 * A cloud job that failed for this song sends the next tap straight to the phone, so a broken cloud
 * never turns into silent, repeated waiting.
 */
object SingRoute {

    sealed interface Route {
        /** An instrumental exists: crossfade to it at the current position. */
        data object Instant : Route

        /** Start (or join) a cloud Sing job. */
        data object Cloud : Route

        /** Render on this phone. [reason] is why the cloud was not used; null when no explanation is owed. */
        data class OnDevice(val reason: OnDeviceReason?) : Route
    }

    /** Why a tap renders on the phone instead of in the cloud (each has its own plain-English toast). */
    enum class OnDeviceReason {
        CLOUD_OFF,
        CLOUD_NOT_SET_UP,
        OFFLINE,
        NOT_ON_WIFI,
        BUDGET_USED,
        TOO_LONG,
        OTHER,
    }

    /**
     * @param existingPath the instrumental already on disk for the song, if any.
     * @param cloud what stops the cloud from taking the song (null = it can).
     * @param online the phone has a working internet connection.
     * @param durationMs the song's length.
     * @param cloudFailedForThisSong the cloud job for this song failed on an earlier tap.
     */
    fun choose(
        existingPath: String?,
        cloud: CloudSingUnavailable?,
        online: Boolean,
        durationMs: Long,
        cloudFailedForThisSong: Boolean,
    ): Route {
        if (existingPath != null) return Route.Instant
        // The person has already been told the cloud failed; this tap does the work on the phone.
        if (cloudFailedForThisSong) return Route.OnDevice(null)
        // Settings problems first: they are what the person can change, offline or not.
        when (cloud) {
            CloudSingUnavailable.OFF -> return Route.OnDevice(OnDeviceReason.CLOUD_OFF)
            CloudSingUnavailable.NOT_CONFIGURED,
            CloudSingUnavailable.KEYS_MISSING -> return Route.OnDevice(OnDeviceReason.CLOUD_NOT_SET_UP)
            else -> Unit
        }
        if (!online) return Route.OnDevice(OnDeviceReason.OFFLINE)
        if (cloud != null) return Route.OnDevice(reasonFor(cloud))
        if (durationMs > CloudLimits.MAX_DURATION_MS) return Route.OnDevice(OnDeviceReason.TOO_LONG)
        return Route.Cloud
    }

    /** The fallback explanation for a cloud refusal. A reason added to the cloud later reads as [OnDeviceReason.OTHER]. */
    fun reasonFor(unavailable: CloudSingUnavailable): OnDeviceReason = when (unavailable) {
        CloudSingUnavailable.OFF -> OnDeviceReason.CLOUD_OFF
        CloudSingUnavailable.NOT_CONFIGURED,
        CloudSingUnavailable.KEYS_MISSING -> OnDeviceReason.CLOUD_NOT_SET_UP
        CloudSingUnavailable.CAP_REACHED -> OnDeviceReason.BUDGET_USED
        CloudSingUnavailable.NOT_ON_WIFI -> OnDeviceReason.NOT_ON_WIFI
        CloudSingUnavailable.TOO_LONG -> OnDeviceReason.TOO_LONG
        else -> OnDeviceReason.OTHER
    }
}
