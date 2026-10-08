package com.theveloper.pixelplay.data.ai.local

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.worker.LocalModelDownloadWorker
import com.theveloper.pixelplay.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The downloaded model's state, for Settings and the AI route. */
sealed interface DownloadedModelState {
    data object NotDownloaded : DownloadedModelState
    /** Enqueued, waiting for its constraints (Wi-Fi unless mobile data was allowed, battery). */
    data object Queued : DownloadedModelState
    data class Downloading(val bytes: Long, val total: Long) : DownloadedModelState
    data object Verifying : DownloadedModelState
    data class Ready(val sizeBytes: Long) : DownloadedModelState
    data class Failed(val reason: DownloadFailureReason) : DownloadedModelState
}

/**
 * Owns the downloadable model file ([DownloadedModelCatalog]): where it lives, its download
 * (a foreground [LocalModelDownloadWorker]) and deleting it.
 *
 * The file goes in `noBackupFilesDir/llm/`, not filesDir: Android Auto Backup would otherwise try
 * to copy 2.6 GB (the old MediaPipe import lived in filesDir/on_device_models, which it includes).
 */
@Singleton
class DownloadedModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiPreferencesRepository: AiPreferencesRepository,
    private val gemmaEngine: GemmaLiteRtEngine,
    @AppScope private val appScope: CoroutineScope,
) {
    val spec: DownloadedModelSpec get() = DownloadedModelCatalog.current

    private val modelDir: File get() = File(context.noBackupFilesDir, MODEL_DIR)
    val modelFile: File get() = File(modelDir, spec.fileName)
    val partFile: File get() = File(modelDir, spec.fileName + ".part")

    private val fileSize = MutableStateFlow<Long?>(null)

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    val state: StateFlow<DownloadedModelState> by lazy {
        appScope.launch { refreshFile() }
        combine(fileSize, workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME)) { size, infos ->
            DownloadedModelStateMapper.map(
                readyFileBytes = size,
                expectedBytes = spec.sizeBytes,
                work = infos.firstOrNull()?.let(::snapshotOf),
            )
        }
            .distinctUntilChanged()
            .stateIn(appScope, SharingStarted.Eagerly, DownloadedModelState.NotDownloaded)
    }

    /** The verified model's path, or null when it isn't (fully) on the phone. */
    fun readyModelPath(): String? {
        val file = modelFile
        return file.takeIf { it.exists() && it.length() == spec.sizeBytes }?.absolutePath
    }

    fun freeBytes(): Long = runCatching {
        modelDir.mkdirs()
        StatFs(modelDir.path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    fun hasRoomForDownload(): Boolean {
        val already = partFile.takeIf { it.exists() }?.length() ?: 0L
        return freeBytes() >= ModelFileDownloader.requiredFreeBytes(spec.sizeBytes, already)
    }

    /**
     * Starts (or resumes) the download. Wi-Fi only unless [allowMetered]; never while the battery
     * is low. Running as a foreground dataSync worker keeps a 2.6 GB transfer alive when the user
     * leaves the app.
     */
    fun download(allowMetered: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(LocalModelDownloadWorker.INPUT_ALLOW_METERED to allowMetered))
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops the download and frees what it had written (an explicit cancel means "not now"). */
    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
        appScope.launch {
            // Give the worker a moment to notice the stop before its .part goes away.
            kotlinx.coroutines.delay(1_000)
            partFile.delete()
        }
    }

    /** Deletes the model and turns "Use downloaded AI model" off, so AI goes back to Gemini Nano. */
    suspend fun delete() {
        workManager.cancelUniqueWork(WORK_NAME)
        gemmaEngine.release()
        aiPreferencesRepository.setDownloadedModelEnabled(false)
        withContext(Dispatchers.IO) {
            modelFile.delete()
            partFile.delete()
            refreshFile()
        }
    }

    /** Re-reads the file on disk (after a download finishes or a delete). */
    fun refreshFile() {
        fileSize.value = readyModelPath()?.let { modelFile.length() }
    }

    private fun snapshotOf(info: WorkInfo): DownloadWorkSnapshot = DownloadWorkSnapshot(
        phase = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadWorkSnapshot.Phase.QUEUED
            WorkInfo.State.RUNNING -> if (info.progress.getBoolean(LocalModelDownloadWorker.PROGRESS_VERIFYING, false)) {
                DownloadWorkSnapshot.Phase.VERIFYING
            } else {
                DownloadWorkSnapshot.Phase.RUNNING
            }
            WorkInfo.State.SUCCEEDED -> DownloadWorkSnapshot.Phase.SUCCEEDED
            WorkInfo.State.FAILED -> DownloadWorkSnapshot.Phase.FAILED
            WorkInfo.State.CANCELLED -> DownloadWorkSnapshot.Phase.CANCELLED
        },
        bytes = info.progress.getLong(LocalModelDownloadWorker.PROGRESS_BYTES, 0L),
        failure = info.outputData.getString(LocalModelDownloadWorker.OUTPUT_FAILURE)
            ?.let { name -> DownloadFailureReason.entries.find { it.name == name } },
    ).also {
        // A worker that just finished wrote the file: pick it up without waiting for a restart.
        if (it.phase == DownloadWorkSnapshot.Phase.SUCCEEDED && fileSize.value == null) appScope.launch { refreshFile() }
    }

    companion object {
        const val WORK_NAME = "llm_download"
        private const val MODEL_DIR = "llm"
    }
}

/** The parts of a [WorkInfo] the state needs; plain data so the mapping is unit-tested. */
data class DownloadWorkSnapshot(
    val phase: Phase,
    val bytes: Long = 0L,
    val failure: DownloadFailureReason? = null,
) {
    enum class Phase { QUEUED, RUNNING, VERIFYING, SUCCEEDED, FAILED, CANCELLED }
}

internal object DownloadedModelStateMapper {
    fun map(readyFileBytes: Long?, expectedBytes: Long, work: DownloadWorkSnapshot?): DownloadedModelState {
        if (readyFileBytes != null) return DownloadedModelState.Ready(readyFileBytes)
        return when (work?.phase) {
            DownloadWorkSnapshot.Phase.QUEUED -> DownloadedModelState.Queued
            DownloadWorkSnapshot.Phase.RUNNING ->
                DownloadedModelState.Downloading(work.bytes.coerceIn(0L, expectedBytes), expectedBytes)
            DownloadWorkSnapshot.Phase.VERIFYING -> DownloadedModelState.Verifying
            DownloadWorkSnapshot.Phase.FAILED ->
                DownloadedModelState.Failed(work.failure ?: DownloadFailureReason.IO)
            // SUCCEEDED before the file was re-read, CANCELLED, or no work at all.
            DownloadWorkSnapshot.Phase.SUCCEEDED -> DownloadedModelState.Verifying
            DownloadWorkSnapshot.Phase.CANCELLED, null -> DownloadedModelState.NotDownloaded
        }
    }
}
