package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.cloudstudio.CloudClipPlanner
import com.theveloper.pixelplay.data.cloudstudio.CloudJobOrigin
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudNotice
import com.theveloper.pixelplay.data.cloudstudio.CloudSingStart
import com.theveloper.pixelplay.data.cloudstudio.CloudSingUnavailable
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.tais.SingClipStore
import com.theveloper.pixelplay.data.tais.SingClipWindow
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.presentation.viewmodel.CloudStudioStateHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Where a render job stands, flattened from [WorkInfo.State] (or a Cloud Studio job's state). */
enum class RenderJobState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    val isFinished: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/** Who renders the instrumental. */
enum class RenderSource { ON_DEVICE, CLOUD }

/** The step a cloud render is in (the lyrics page only tells "waiting for Wi-Fi" apart from the rest). */
enum class CloudPhase { WAITING_FOR_WIFI, PREPARING, UPLOADING, QUEUED_AT_GPU, PROCESSING, DOWNLOADING }

/** One instrumental render for a song, as the lyrics page's Sing button needs it. */
data class InstrumentalRenderJob(
    val id: String,
    val state: RenderJobState,
    val percent: Int,
    val instrumentalPath: String?,
    val failureReason: String?,
    /** Quiet maintenance work (Automatic Studio, the cloud's automatic preparation), which a person's own tap adopts. */
    val automatic: Boolean,
    val createdAtMs: Long,
    val source: RenderSource = RenderSource.ON_DEVICE,
    /** The cloud step, for [RenderSource.CLOUD] jobs. */
    val phase: CloudPhase? = null,
    /**
     * Set on a Sing's preview clip: the part of the song it holds. Its [instrumentalPath] is the transient clip, never
     * the song's instrumental, so it never counts as one ("available", a finished render): the Sing plays it until the
     * full job lands.
     */
    val clip: SingClipWindow? = null,
) {
    val isClip: Boolean get() = clip != null
}

/** The instrumental renders behind the Sing button: WorkManager and Cloud Studio in the app, a fake in tests. */
interface InstrumentalRenderJobs {
    /** Every render job tagged for [songId], updated as WorkManager and Cloud Studio change them. */
    fun jobsFor(songId: String): Flow<List<InstrumentalRenderJob>>

    /** Starts a person's render of [song] on this phone (quiet automatic work is cancelled so this one runs now). */
    fun enqueue(song: Song)

    /** The finished instrumental on disk for [songId], if there is one (read off the main thread). */
    suspend fun bestAvailablePath(songId: String): String?

    /** What keeps the cloud from taking [song] right now (null: it can). */
    fun cloudAvailability(song: Song): CloudSingUnavailable?

    /**
     * Starts a Sing job for [song] in the cloud, or joins the one already on its way. [playheadMs] (where the song is
     * now) lets a preview clip from there go first.
     */
    suspend fun startCloud(song: Song, playheadMs: Long? = null): CloudSingStart

    /** The song's Sing preview clips go (the song changed); one still playing goes once the player lets go of it. */
    suspend fun discardClips(songId: String) {}
}

@Singleton
class WorkManagerInstrumentalRenderJobs @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workManager: WorkManager,
) {

    fun jobsFor(songId: String): Flow<List<InstrumentalRenderJob>> =
        workManager.getWorkInfosByTagFlow(StemSeparatorWorker.uniqueWorkName(songId))
            .map { infos -> infos.map { it.toRenderJob() } }

    fun enqueue(song: Song) {
        StemSeparatorWorker.enqueue(workManager, song.id, song.contentUriString)
    }

    suspend fun bestAvailablePath(songId: String): String? = withContext(Dispatchers.IO) {
        TaisInstrumentalIndex.bestAvailableFile(context, songId)?.absolutePath
    }

    /** The Sing preview clip of [songId] that starts at [startMs], if it is on disk. */
    suspend fun clipPath(songId: String, startMs: Long): String? = withContext(Dispatchers.IO) {
        SingClipStore.find(SingClipStore.directory(context), songId, startMs)?.absolutePath
    }

    suspend fun discardClips(songId: String) = withContext(Dispatchers.IO) {
        SingClipStore.discard(SingClipStore.directory(context), songId)
    }
}

/**
 * The app's [InstrumentalRenderJobs]: the on-device renders (WorkManager) and the Cloud Studio jobs for the
 * same song, side by side. A cloud job counts as done when its instrumental is imported, not when the whole
 * job is (the lyrics import comes first, but the job only finishes after both).
 *
 * Cloud Studio is behind [dagger.Lazy]: nothing of it is built until a Sing tap or a lyrics page needs it.
 */
@Singleton
class CompositeInstrumentalRenderJobs @Inject constructor(
    private val onDevice: WorkManagerInstrumentalRenderJobs,
    private val cloud: dagger.Lazy<CloudStudioStateHolder>,
) : InstrumentalRenderJobs {

    override fun jobsFor(songId: String): Flow<List<InstrumentalRenderJob>> =
        combine(onDevice.jobsFor(songId), cloudJobs(songId)) { local, remote -> local + remote }

    override fun enqueue(song: Song) = onDevice.enqueue(song)

    override suspend fun bestAvailablePath(songId: String): String? = onDevice.bestAvailablePath(songId)

    override fun cloudAvailability(song: Song): CloudSingUnavailable? = cloud.get().singAvailability(song)

    override suspend fun startCloud(song: Song, playheadMs: Long?): CloudSingStart = cloud.get().startSing(song, playheadMs)

    override suspend fun discardClips(songId: String) = onDevice.discardClips(songId)

    private fun cloudJobs(songId: String): Flow<List<InstrumentalRenderJob>> {
        val holder = cloud.get()
        val conditions = holder.engineState
            .map { CloudConditions(it.notice == CloudNotice.WAITING_FOR_WIFI, it.transferProgress) }
            .distinctUntilChanged()
        return combine(holder.cloudJobsFor(songId), conditions) { records, now -> records to now }
            .map { (records, now) ->
                // Only a full job's import gives the song an instrumental; a preview clip's file is its own.
                val someImported = records.any { !it.isClip && (it.importedInstrumental || it.state == CloudJobState.IMPORTED) }
                val path = if (someImported) bestAvailablePath(songId) else null
                records.mapNotNull { record ->
                    val clipStart = record.clipStartMs
                    val recordPath = when {
                        clipStart == null -> path
                        record.importedInstrumental -> onDevice.clipPath(record.songId, clipStart)
                        else -> null
                    }
                    record.toRenderJob(now.waitingForWifi, now.transfers[record.jobKey], recordPath)
                }
            }
            // A Cloud Studio hiccup never takes the on-device renders down with it.
            .catch { emit(emptyList()) }
    }

    private data class CloudConditions(val waitingForWifi: Boolean, val transfers: Map<String, Float>)
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

/**
 * A Cloud Studio job as a render job, or null when it does not make an instrumental.
 *
 * - Before the GPU has it, the job is [RenderJobState.QUEUED] with its [CloudPhase]; at RunPod and while the
 *   result downloads it is [RenderJobState.RUNNING].
 * - It is [RenderJobState.SUCCEEDED] as soon as the instrumental is imported (whatever state the job is in), or
 *   when it finished importing.
 * - [waitingForWifi] is the engine's "waiting for Wi-Fi" notice; it only applies to the steps that move song data.
 * - [transfer] is the running upload or download's fraction, [instrumentalPath] the file on disk once imported.
 * - A Sing's preview clip keeps its [InstrumentalRenderJob.clip] window: [instrumentalPath] is then the clip's own
 *   transient file, which never stands for the song's instrumental.
 */
internal fun CloudJobRecord.toRenderJob(
    waitingForWifi: Boolean,
    transfer: Float? = null,
    instrumentalPath: String? = null,
): InstrumentalRenderJob? {
    if (CloudTask.INSTRUMENTAL !in tasks) return null
    val resultsWaiting = (state == CloudJobState.RESULTS_READY || state == CloudJobState.DOWNLOADING) && !importedInstrumental
    val movesData = state == CloudJobState.UPLOADING || (state == CloudJobState.QUEUED && isStreamed) || resultsWaiting
    val phase = when {
        waitingForWifi && movesData -> CloudPhase.WAITING_FOR_WIFI
        state == CloudJobState.QUEUED || state == CloudJobState.PREPARING -> CloudPhase.PREPARING
        state == CloudJobState.UPLOADING || state == CloudJobState.UPLOADED -> CloudPhase.UPLOADING
        state == CloudJobState.SUBMITTED -> CloudPhase.QUEUED_AT_GPU
        state == CloudJobState.RUNNING -> CloudPhase.PROCESSING
        resultsWaiting -> CloudPhase.DOWNLOADING
        else -> null
    }
    val renderState = when {
        importedInstrumental -> RenderJobState.SUCCEEDED
        else -> when (state) {
            CloudJobState.QUEUED, CloudJobState.PREPARING, CloudJobState.UPLOADING,
            CloudJobState.UPLOADED, CloudJobState.SUBMITTED -> RenderJobState.QUEUED
            CloudJobState.RUNNING, CloudJobState.RESULTS_READY, CloudJobState.DOWNLOADING -> RenderJobState.RUNNING
            CloudJobState.IMPORTED -> RenderJobState.SUCCEEDED
            CloudJobState.FAILED, CloudJobState.EXPIRED -> RenderJobState.FAILED
            CloudJobState.CANCELLED -> RenderJobState.CANCELLED
        }
    }
    val done = renderState == RenderJobState.SUCCEEDED
    return InstrumentalRenderJob(
        id = jobKey,
        state = renderState,
        percent = if (done) 100 else (progressFraction(transfer) * 100f).roundToInt().coerceIn(0, 100),
        instrumentalPath = instrumentalPath.takeIf { done },
        failureReason = lastError.takeIf { renderState == RenderJobState.FAILED },
        automatic = origin == CloudJobOrigin.AUTO,
        createdAtMs = createdAtMs,
        source = RenderSource.CLOUD,
        phase = phase.takeIf { !done && !renderState.isFinished },
        clip = clipStartMs?.let { start ->
            SingClipWindow(start, clipEndMs ?: (start + CloudClipPlanner.AFTER_PLAYHEAD_MS + CloudClipPlanner.PRE_ROLL_MS))
        },
    )
}

/**
 * How far along a cloud job is, 0..1 (the Sing button's fill), from what is really known: the upload and
 * download fractions, and the worker's own percentage. A step with nothing to measure (waiting for a GPU)
 * takes the step's start, so the fill still moves when the job changes step. Same numbers as the iOS app.
 */
internal fun CloudJobRecord.progressFraction(transfer: Float?): Float {
    val t = (transfer ?: 0f).coerceIn(0f, 1f)
    return when (state) {
        CloudJobState.QUEUED -> 0f
        CloudJobState.PREPARING -> 0.05f
        CloudJobState.UPLOADING -> 0.1f + 0.3f * t
        CloudJobState.UPLOADED -> 0.4f
        CloudJobState.SUBMITTED -> 0.45f
        CloudJobState.RUNNING -> 0.5f + 0.4f * ((progressPercent ?: 0).coerceIn(0, 100) / 100f)
        CloudJobState.RESULTS_READY -> 0.9f
        CloudJobState.DOWNLOADING -> 0.9f + 0.1f * t
        CloudJobState.IMPORTED -> 1f
        CloudJobState.FAILED, CloudJobState.CANCELLED, CloudJobState.EXPIRED -> 0f
    }
}
