package com.theveloper.pixelplay.data.ai.local

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.presentation.viewmodel.AiAvailability
import com.theveloper.pixelplay.presentation.viewmodel.AiAvailabilityResolver
import org.junit.jupiter.api.Test

class AiRouteResolverTest {

    @Test
    fun `the default is Gemini Nano`() {
        assertThat(AiRouteResolver.resolve("ON_DEVICE", useDownloadedModel = false, downloadedModelReady = false))
            .isEqualTo(AiRoute.OnDevice(LocalEngineId.NANO))
        // A missing or unknown value never routes to the cloud.
        assertThat(AiProvider.fromString("")).isEqualTo(AiProvider.ON_DEVICE)
        assertThat(AiProvider.fromString("SOMETHING_NEW")).isEqualTo(AiProvider.ON_DEVICE)
    }

    @Test
    fun `the downloaded model answers only when the switch is on and the file is ready`() {
        assertThat(AiRouteResolver.resolve("ON_DEVICE", useDownloadedModel = true, downloadedModelReady = true))
            .isEqualTo(AiRoute.OnDevice(LocalEngineId.GEMMA))
        assertThat(AiRouteResolver.resolve("ON_DEVICE", useDownloadedModel = true, downloadedModelReady = false))
            .isEqualTo(AiRoute.OnDevice(LocalEngineId.NANO))
        assertThat(AiRouteResolver.resolve("ON_DEVICE", useDownloadedModel = false, downloadedModelReady = true))
            .isEqualTo(AiRoute.OnDevice(LocalEngineId.NANO))
        assertThat(
            AiRouteResolver.resolve("ON_DEVICE", useDownloadedModel = true, downloadedModelReady = true, downloadedModelSupported = false)
        ).isEqualTo(AiRoute.OnDevice(LocalEngineId.NANO))
    }

    @Test
    fun `a cloud selection routes to that provider and its chain never contains on-device`() {
        assertThat(AiRouteResolver.resolve("GROQ", useDownloadedModel = true, downloadedModelReady = true))
            .isEqualTo(AiRoute.Cloud(AiProvider.GROQ))
        for (provider in AiProvider.cloudProviders) {
            val chain = AiRouteResolver.cloudChain(provider)
            assertThat(chain.first()).isEqualTo(provider)
            assertThat(chain).doesNotContain(AiProvider.ON_DEVICE)
        }
        assertThat(AiProvider.cloudProviders).doesNotContain(AiProvider.ON_DEVICE)
    }

    @Test
    fun `on-device never reads as offline`() {
        assertThat(AiProvider.ON_DEVICE.displayName.lowercase()).doesNotContain("offline")
    }

    @Test
    fun `availability on-device follows Gemini Nano, and the ready downloaded model stands alone`() {
        fun resolve(status: NanoStatus, useDownloaded: Boolean = false, ready: Boolean = false) =
            AiAvailabilityResolver.resolve(AiProvider.ON_DEVICE, "", "", useDownloaded, ready, status)

        assertThat(resolve(NanoStatus.Ready)).isEqualTo(AiAvailability.Ready(AiRoute.OnDevice(LocalEngineId.NANO)))
        assertThat(resolve(NanoStatus.Unknown)).isEqualTo(AiAvailability.Checking)
        assertThat(resolve(NanoStatus.Unknown).isUsable).isTrue()
        assertThat(resolve(NanoStatus.NeedsDownload)).isEqualTo(AiAvailability.NeedsNanoDownload)
        assertThat(resolve(NanoStatus.NeedsDownload).isUsable).isFalse()
        assertThat(resolve(NanoStatus.Unavailable(OnDeviceFailure.NOT_SUPPORTED)))
            .isEqualTo(AiAvailability.Unavailable(OnDeviceFailure.NOT_SUPPORTED))
        assertThat(resolve(NanoStatus.Unavailable(OnDeviceFailure.NOT_SUPPORTED), useDownloaded = true, ready = true))
            .isEqualTo(AiAvailability.Ready(AiRoute.OnDevice(LocalEngineId.GEMMA)))
        assertThat(resolve(NanoStatus.Unavailable(OnDeviceFailure.NOT_SUPPORTED), useDownloaded = true, ready = false))
            .isEqualTo(AiAvailability.Unavailable(OnDeviceFailure.MODEL_MISSING))
    }

    @Test
    fun `availability on a cloud assistant needs its key or base URL`() {
        assertThat(AiAvailabilityResolver.resolve(AiProvider.GEMINI, "", "", false, false, NanoStatus.Ready))
            .isEqualTo(AiAvailability.NeedsCloudSetup(AiProvider.GEMINI))
        assertThat(AiAvailabilityResolver.resolve(AiProvider.GEMINI, "k", "", false, false, NanoStatus.Unknown))
            .isEqualTo(AiAvailability.Ready(AiRoute.Cloud(AiProvider.GEMINI)))
        assertThat(AiAvailabilityResolver.resolve(AiProvider.OLLAMA, "", "", false, false, NanoStatus.Ready))
            .isEqualTo(AiAvailability.NeedsCloudSetup(AiProvider.OLLAMA))
        assertThat(AiAvailabilityResolver.resolve(AiProvider.OLLAMA, "", "http://192.168.1.5:11434/v1", false, false, NanoStatus.Ready))
            .isEqualTo(AiAvailability.Ready(AiRoute.Cloud(AiProvider.OLLAMA)))
    }
}
