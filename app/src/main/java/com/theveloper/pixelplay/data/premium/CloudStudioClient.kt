package com.theveloper.pixelplay.data.premium

import com.theveloper.pixelplay.data.preferences.TaisRoformerBackendType
import com.theveloper.pixelplay.data.tais.stems.BsRoformerApiClient
import com.theveloper.pixelplay.data.tais.stems.DirectPostStemApiClient
import com.theveloper.pixelplay.data.tais.stems.RoformerResult
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User supplied or first-party hosted processing configuration.
 *
 * The app deliberately does not ship a server URL, API key, or a promise of unlimited cloud
 * processing. A server must be configured by the distribution/backend and the user must opt in
 * before audio leaves the device. The local TAIS pipeline remains the caller's fallback.
 */
data class CloudStudioConfig(
    val baseUrl: String,
    val apiName: String = "/predict",
    val apiKey: String? = null,
    val backendType: TaisRoformerBackendType = TaisRoformerBackendType.GRADIO_SPACE,
    val consentToUpload: Boolean = false
) {
    fun validationError(): String? {
        if (!consentToUpload) return "Cloud processing is disabled until you opt in."
        val normalized = baseUrl.trim()
        if (normalized.isBlank()) return "No cloud processing endpoint is configured."
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: return "The cloud endpoint is not a valid URL."
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return "The cloud endpoint is missing a host."
        // Never send a bearer key over an arbitrary cleartext connection. Local development
        // servers are allowed for testing, while production endpoints must use TLS.
        if (scheme != "https" && !(scheme == "http" && isPrivateDevelopmentHost(host))) {
            return "Cloud processing requires an HTTPS endpoint."
        }
        return null
    }

    private fun isPrivateDevelopmentHost(host: String): Boolean {
        val secondOctet = host.substringAfter("172.", "").substringBefore('.').toIntOrNull()
        return host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            (host.startsWith("172.") && secondOctet != null && secondOctet in 16..31)
    }
}

sealed interface CloudStudioOutcome {
    data class Success(val result: RoformerResult) : CloudStudioOutcome
    data class Unavailable(val reason: String) : CloudStudioOutcome
}

/** A small boundary that lets Plus use a hosted accelerator without coupling UI to its protocol. */
interface CloudStudioClient {
    suspend fun render(
        config: CloudStudioConfig,
        sourceAudioFile: File,
        outputDir: File,
        onStage: suspend (String) -> Unit = {}
    ): CloudStudioOutcome
}

/** Uses the existing Gradio/direct POST clients and fails closed when consent/config is absent. */
@Singleton
class ConfiguredCloudStudioClient @Inject constructor(
    private val gradioClient: BsRoformerApiClient,
    private val directPostClient: DirectPostStemApiClient
) : CloudStudioClient {
    override suspend fun render(
        config: CloudStudioConfig,
        sourceAudioFile: File,
        outputDir: File,
        onStage: suspend (String) -> Unit
    ): CloudStudioOutcome {
        config.validationError()?.let { return CloudStudioOutcome.Unavailable(it) }
        if (!sourceAudioFile.isFile || sourceAudioFile.length() == 0L) {
            return CloudStudioOutcome.Unavailable("The source audio file is unavailable.")
        }
        val result = when (config.backendType) {
            TaisRoformerBackendType.GRADIO_SPACE -> gradioClient.separate(
                baseUrl = config.baseUrl,
                apiName = config.apiName,
                apiKey = config.apiKey?.takeIf { it.isNotBlank() },
                sourceAudioFile = sourceAudioFile,
                outputDir = outputDir,
                onStage = onStage
            )
            TaisRoformerBackendType.DIRECT_POST -> directPostClient.separate(
                baseUrl = config.baseUrl,
                route = config.apiName,
                apiKey = config.apiKey?.takeIf { it.isNotBlank() },
                sourceAudioFile = sourceAudioFile,
                outputDir = outputDir,
                onStage = onStage
            )
        }
        return result?.let(CloudStudioOutcome::Success)
            ?: CloudStudioOutcome.Unavailable("The cloud render failed; use on-device processing instead.")
    }
}
