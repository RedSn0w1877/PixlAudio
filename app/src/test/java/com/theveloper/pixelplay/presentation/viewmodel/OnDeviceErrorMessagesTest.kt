package com.theveloper.pixelplay.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.local.OnDeviceAiException
import com.theveloper.pixelplay.data.ai.local.OnDeviceFailure
import com.theveloper.pixelplay.data.ai.provider.AiProviderSupport
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * On-device failures used to read "No Internet Connection". These pin the two halves of the fix:
 * the error tables find an on-device failure first, however it is wrapped, and the English
 * messages can't be re-matched by the older substring rules (network / connect / offline / wifi /
 * timeout / key / config).
 */
class OnDeviceErrorMessagesTest {

    @Test
    fun `on-device failures resolve to their own message however they are wrapped`() {
        for (failure in listOf(OnDeviceFailure.TOO_LONG, OnDeviceFailure.BUSY, OnDeviceFailure.BLOCKED)) {
            val original = OnDeviceAiException(failure)
            val asPlaylistGeneratorWraps = Exception("AI Error: ${failure.name}", original)
            val asAiHandlerWraps = AiProviderSupport.wrapThrowable("On-device", asPlaylistGeneratorWraps)

            assertThat(AiErrorMessages.onDeviceMessageRes(original)).isEqualTo(failure.messageRes)
            assertThat(AiErrorMessages.onDeviceMessageRes(asPlaylistGeneratorWraps)).isEqualTo(failure.messageRes)
            assertThat(AiErrorMessages.onDeviceMessageRes(asAiHandlerWraps)).isEqualTo(failure.messageRes)
            assertThat(failure.messageRes).isNotEqualTo(R.string.ai_state_error_api_key)
        }
        assertThat(AiErrorMessages.onDeviceMessageRes(Exception("401 unauthorized"))).isNull()
    }

    @Test
    fun `no on-device error message contains a word the old error tables match`() {
        val strings = englishStrings()
        val onDevice = strings.filterKeys { it.startsWith("ai_on_device_error_") }
        // One message per failure.
        assertThat(onDevice).hasSize(OnDeviceFailure.entries.size)
        val forbidden = listOf("network", "connect", "offline", "wifi", "wi-fi", "timeout", "time out", "key", "config")
        for ((name, text) in onDevice) {
            val lower = text.lowercase()
            for (word in forbidden) {
                assertWithMessage(name).that(lower).doesNotContain(word)
            }
        }
    }

    private fun englishStrings(): Map<String, String> {
        // Gradle runs unit tests from the module directory.
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.exists() }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
}
