package com.theveloper.pixelplay.presentation.lyrics.sing

import com.theveloper.pixelplay.data.cloudstudio.CloudSingUnavailable
import com.theveloper.pixelplay.presentation.lyrics.sing.SingRoute.OnDeviceReason
import com.theveloper.pixelplay.presentation.lyrics.sing.SingRoute.Route
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SingRouteTest {

    private fun choose(
        existingPath: String? = null,
        cloud: CloudSingUnavailable? = null,
        online: Boolean = true,
        durationMs: Long = 240_000,
        failed: Boolean = false,
    ) = SingRoute.choose(existingPath, cloud, online, durationMs, failed)

    @Test
    fun `an instrumental on disk is used at once, whatever the cloud says`() {
        assertEquals(Route.Instant, choose(existingPath = "/stems/1.wav"))
        assertEquals(
            Route.Instant,
            choose(existingPath = "/stems/1.wav", cloud = CloudSingUnavailable.OFF, online = false, failed = true),
        )
    }

    @Test
    fun `a usable cloud takes the song`() {
        assertEquals(Route.Cloud, choose())
    }

    @Test
    fun `each cloud refusal renders on the phone and says why`() {
        assertEquals(Route.OnDevice(OnDeviceReason.CLOUD_OFF), choose(cloud = CloudSingUnavailable.OFF))
        assertEquals(Route.OnDevice(OnDeviceReason.CLOUD_NOT_SET_UP), choose(cloud = CloudSingUnavailable.NOT_CONFIGURED))
        assertEquals(Route.OnDevice(OnDeviceReason.CLOUD_NOT_SET_UP), choose(cloud = CloudSingUnavailable.KEYS_MISSING))
        assertEquals(Route.OnDevice(OnDeviceReason.BUDGET_USED), choose(cloud = CloudSingUnavailable.CAP_REACHED))
        assertEquals(Route.OnDevice(OnDeviceReason.NOT_ON_WIFI), choose(cloud = CloudSingUnavailable.NOT_ON_WIFI))
        assertEquals(Route.OnDevice(OnDeviceReason.TOO_LONG), choose(cloud = CloudSingUnavailable.TOO_LONG))
        assertEquals(Route.OnDevice(OnDeviceReason.OTHER), choose(cloud = CloudSingUnavailable.NO_AUDIO))
        assertEquals(Route.OnDevice(OnDeviceReason.OTHER), choose(cloud = CloudSingUnavailable.ALREADY_HAS_INSTRUMENTAL))
    }

    @Test
    fun `offline renders on the phone`() {
        assertEquals(Route.OnDevice(OnDeviceReason.OFFLINE), choose(online = false))
    }

    @Test
    fun `a setting that is off is named before being offline`() {
        assertEquals(Route.OnDevice(OnDeviceReason.CLOUD_OFF), choose(cloud = CloudSingUnavailable.OFF, online = false))
        assertEquals(
            Route.OnDevice(OnDeviceReason.OFFLINE),
            choose(cloud = CloudSingUnavailable.NOT_ON_WIFI, online = false),
        )
    }

    @Test
    fun `a song over fifteen minutes stays on the phone`() {
        assertEquals(Route.Cloud, choose(durationMs = 15 * 60_000L))
        assertEquals(Route.OnDevice(OnDeviceReason.TOO_LONG), choose(durationMs = 15 * 60_000L + 1))
    }

    @Test
    fun `after a failed cloud job the next tap goes to the phone without another explanation`() {
        assertEquals(Route.OnDevice(null), choose(failed = true))
        assertEquals(Route.OnDevice(null), choose(cloud = CloudSingUnavailable.CAP_REACHED, failed = true))
    }
}
