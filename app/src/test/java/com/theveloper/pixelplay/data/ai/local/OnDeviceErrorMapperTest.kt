package com.theveloper.pixelplay.data.ai.local

import com.google.common.truth.Truth.assertThat
import com.google.mlkit.genai.common.GenAiException
import com.theveloper.pixelplay.data.ai.provider.AiProviderSupport
import org.junit.jupiter.api.Test

class OnDeviceErrorMapperTest {

    private fun failureFor(code: Int) = OnDeviceErrorMapper.fromErrorCode(code).failure

    @Test
    fun `ML Kit error codes map to what the user can do about them`() {
        assertThat(failureFor(GenAiException.ErrorCode.BUSY)).isEqualTo(OnDeviceFailure.BUSY)
        assertThat(failureFor(GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED)).isEqualTo(OnDeviceFailure.NEEDS_FOREGROUND)
        assertThat(failureFor(GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED)).isEqualTo(OnDeviceFailure.DAILY_LIMIT)
        assertThat(failureFor(GenAiException.ErrorCode.REQUEST_TOO_LARGE)).isEqualTo(OnDeviceFailure.TOO_LONG)
        assertThat(failureFor(GenAiException.ErrorCode.NOT_AVAILABLE)).isEqualTo(OnDeviceFailure.NOT_SUPPORTED)
        assertThat(failureFor(GenAiException.ErrorCode.AICORE_INCOMPATIBLE)).isEqualTo(OnDeviceFailure.NOT_SUPPORTED)
        assertThat(failureFor(GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE)).isEqualTo(OnDeviceFailure.NEEDS_UPDATE)
        assertThat(failureFor(GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR)).isEqualTo(OnDeviceFailure.BLOCKED)
        assertThat(failureFor(GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR)).isEqualTo(OnDeviceFailure.BLOCKED)
        assertThat(failureFor(GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE)).isEqualTo(OnDeviceFailure.PREPARING)
        assertThat(failureFor(-987_654)).isEqualTo(OnDeviceFailure.OTHER)
    }

    @Test
    fun `only BUSY carries a retry delay`() {
        assertThat(OnDeviceErrorMapper.fromErrorCode(GenAiException.ErrorCode.BUSY, 1_500L).retryAfterMs).isEqualTo(1_500L)
        assertThat(OnDeviceErrorMapper.fromErrorCode(GenAiException.ErrorCode.REQUEST_TOO_LARGE, 1_500L).retryAfterMs).isNull()
    }

    @Test
    fun `LiteRT-LM loading failures are MODEL_LOAD_FAILED so Gemini Nano can stand in`() {
        assertThat(OnDeviceErrorMapper.fromLiteRt(IllegalStateException("bad file"), whileLoading = true).failure)
            .isEqualTo(OnDeviceFailure.MODEL_LOAD_FAILED)
        assertThat(OnDeviceErrorMapper.fromLiteRt(UnsatisfiedLinkError("no liblitertlm_jni.so"), whileLoading = false).failure)
            .isEqualTo(OnDeviceFailure.MODEL_LOAD_FAILED)
        assertThat(OnDeviceErrorMapper.fromLiteRt(OutOfMemoryError(), whileLoading = false).failure)
            .isEqualTo(OnDeviceFailure.MODEL_LOAD_FAILED)
        assertThat(OnDeviceErrorMapper.fromLiteRt(RuntimeException("Input token ids exceed max_num_tokens"), whileLoading = false).failure)
            .isEqualTo(OnDeviceFailure.TOO_LONG)
        assertThat(OnDeviceErrorMapper.fromLiteRt(RuntimeException("decode failed"), whileLoading = false).failure)
            .isEqualTo(OnDeviceFailure.BLOCKED)
    }

    @Test
    fun `an on-device failure is found through every wrapper the app puts around it`() {
        val original = OnDeviceAiException(OnDeviceFailure.TOO_LONG)
        // AiPlaylistGenerator: Exception(details, cause). The cloud handler: AiProviderException.
        val playlistWrapped = Exception("AI Error: TOO_LONG", original)
        val providerWrapped = AiProviderSupport.wrapThrowable("On-device", playlistWrapped)

        assertThat(original.findOnDeviceFailure()).isSameInstanceAs(original)
        assertThat(playlistWrapped.findOnDeviceFailure()).isSameInstanceAs(original)
        assertThat(providerWrapped.findOnDeviceFailure()).isSameInstanceAs(original)
        assertThat(IllegalStateException("network down").findOnDeviceFailure()).isNull()
    }

    @Test
    fun `the exception message never reads like a network or key problem`() {
        val forbidden = listOf("network", "connect", "offline", "wifi", "timeout", "key", "config")
        for (failure in OnDeviceFailure.entries) {
            val message = OnDeviceAiException(failure).message.orEmpty().lowercase()
            forbidden.forEach { word -> assertThat(message).doesNotContain(word) }
        }
    }
}
