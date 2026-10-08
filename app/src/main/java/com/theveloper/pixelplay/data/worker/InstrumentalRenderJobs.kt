package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Where a render job stands, flattened from [WorkInfo.State]. */
enum class RenderJobState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    val isFinished: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/** One instrumental render ([StemSeparatorWorker]) for a song, as the lyrics page's Sing button needs it. */
data class InstrumentalRenderJob(
    val id: String,
    val state: RenderJobState,
    val percent: Int,
    val instrumentalPath: String?,
    val failureReason: String?,
    /** Quiet maintenance work (Automatic Studio), which a person's own render cancels and replaces. */
    val automatic: Boolean,
    val createdAtMs: Long,
)

/** The instrumental renders behind the Sing button: WorkManager in the app, a fake in tests. */
interface InstrumentalRenderJobs {
    /** Every render job tagged for [songId], updated as WorkManager changes them. */
    fun jobsFor(songId: String): Flow<List<InstrumentalRenderJob>>

    /** Starts a person's render of [song] (quiet automatic work is cancelled so this one runs now). */
    fun enqueue(song: Song)

    /** The finished instrumental on disk for [songId], if there is one (read off the main thread). */
    suspend fun bestAvailablePath(songId: String): String?
}

@Singleton
class WorkManagerInstrumentalRenderJobs @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workManager: WorkManager,
) : InstrumentalRenderJobs {

    override fun jobsFor(songId: String): Flow<List<InstrumentalRenderJob>> =
        workManager.getWorkInfosByTagFlow(StemSeparatorWorker.uniqueWorkName(songId))
            .map { infos -> infos.map { it.toRenderJob() } }

    override fun enqueue(song: Song) {
        StemSeparatorWorker.enqueue(workManager, song.id, song.contentUriString)
    }

    override suspend fun bestAvailablePath(songId: String): String? = withContext(Dispatchers.IO) {
        TaisInstrumentalIndex.bestAvailableFile(context, songId)?.absolutePath
    }
}

internal fun WorkInfo.toRenderJob(): InstrumentalRenderJob = InstrumentalRenderJob(
    id = id.toString(),
    state = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> RenderJobState.QUEUED
        WorkInfo.State.RUNNING -> RenderJobState.RUNNING
        WorkInfo.State.SUCCEEDED -> RenderJobState.SUCCEEDED
        WorkInfo.State.FAILED -> RenderJobState.FAILED
        WorkInfo.State.CANCELLED -> RenderJobState.CANCELLED
    },
    percent = progress.getInt(StemSeparatorWorker.PROGRESS_PERCENT, 0),
    instrumentalPath = outputData.getString(StemSeparatorWorker.OUTPUT_INSTRUMENTAL_PATH),
    failureReason = outputData.getString(StemSeparatorWorker.OUTPUT_FAILURE_REASON),
    automatic = AUTO_STUDIO_WORK_TAG in tags,
    createdAtMs = tags.firstOrNull { it.startsWith(TaisStudioWorker.REQUEST_CREATED_TAG) }
        ?.removePrefix(TaisStudioWorker.REQUEST_CREATED_TAG)
        ?.toLongOrNull() ?: 0L,
)
