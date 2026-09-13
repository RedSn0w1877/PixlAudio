package com.theveloper.pixelplay.data.ai.ondevice

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class OnDeviceModelInfo(
    val fileName: String,
    val sizeBytes: Long
)

/**
 * Owns the user-imported on-device model file.
 *
 * There's no in-app download: Google's ready-to-use Gemma `.task`/`.litertlm` builds are gated on
 * Hugging Face behind a login and license acceptance, so there's no anonymous URL this app could
 * fetch on the user's behalf. Instead the user downloads a compatible file themselves (any model
 * converted for MediaPipe's LLM Inference API — Gemma, Phi-2, Falcon-RW-1B, StableLM all work)
 * and imports it here via the system file picker; we copy it into app-private storage so it
 * survives the source document being moved/deleted and so [android.net.Uri] permission churn
 * across reboots can't break inference.
 */
@Singleton
class OnDeviceModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modelDir: File
        get() = File(context.filesDir, "on_device_models").apply { mkdirs() }

    private val modelFile: File
        get() = File(modelDir, MODEL_FILE_NAME)

    private val _modelInfo = MutableStateFlow<OnDeviceModelInfo?>(null)
    val modelInfo: StateFlow<OnDeviceModelInfo?> = _modelInfo.asStateFlow()

    init {
        refreshState()
    }

    private fun refreshState() {
        _modelInfo.value = modelFile.takeIf { it.exists() && it.length() > 0 }
            ?.let { OnDeviceModelInfo(fileName = it.name, sizeBytes = it.length()) }
    }

    fun currentModelPath(): String? = modelFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath

    fun hasModel(): Boolean = currentModelPath() != null

    /** Copies the picked document into app-private storage. Any previous model is replaced. */
    suspend fun importModel(uri: Uri): Result<OnDeviceModelInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File(modelDir, "$MODEL_FILE_NAME.importing")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Couldn't open the selected file")

            if (tempFile.length() == 0L) {
                tempFile.delete()
                throw IllegalStateException("The selected file is empty")
            }

            val finalFile = modelFile
            if (finalFile.exists()) finalFile.delete()
            tempFile.renameTo(finalFile)

            val info = OnDeviceModelInfo(fileName = finalFile.name, sizeBytes = finalFile.length())
            _modelInfo.value = info
            info
        }.onFailure { e ->
            Timber.tag(TAG).e(e, "Failed to import on-device model")
        }
    }

    fun deleteModel() {
        if (modelFile.exists()) modelFile.delete()
        _modelInfo.value = null
    }

    companion object {
        private const val TAG = "OnDeviceModelManager"
        // Model format (Gemma vs. Phi-2 vs. Falcon, quantization) doesn't affect how this class
        // stores it, so one fixed name is fine — only one on-device model is active at a time.
        private const val MODEL_FILE_NAME = "model.task"
    }
}
