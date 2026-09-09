package com.theveloper.pixelplay.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.tais.lyrics.TaisLyricsAligner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Word-Sync Render Pipeline — the "Lyrics" button's background job. Stem separation now lives
 * entirely in [com.theveloper.pixelplay.data.worker.StemSeparatorWorker] (its own "Instrumental"
 * button) so the two can be triggered and progress-tracked independently instead of always
 * running back-to-back.
 *
 * [TaisLyricsAligner] force-aligns the track's known lyric text against a CTC acoustic model
 * (wav2vec2 through ONNX Runtime; hardware acceleration depends on device support)
 * to produce word-level timestamps, then persists them through the normal lyrics cache so future
 * playback loads instantly. Batch sync skips existing word timings; explicit resync replaces them.
 *
 * Progress is reported via [setProgress] as `(stage, percent)` pairs — see [STAGE_ALIGNING] /
 * [STAGE_DONE] — for
 * [com.theveloper.pixelplay.presentation.components.tais.TaisStudioProgressCard] to render.
 */
@HiltWorker
class TaisStudioWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val lyricsAligner: TaisLyricsAligner,
    private val musicRepository: MusicRepository,
    private val audioCacheManager: AudioCacheManager,
    private val spotifyStreamProxy: SpotifyStreamProxy
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(INPUT_SONG_ID)
            ?: return@withContext completeFailure("Missing song ID")
        try {
            reportProgress(STAGE_WAITING, 0, "Waiting for the lyric sync engine…")
            // Single-song taps and separate playlist batches share the same process-wide lane.
            // Re-classify only after acquiring it, so overlapping requests do not re-render a
            // song already completed by the previous worker.
            syncMutex.withLock {
                val song = musicRepository.getSong(songId).first()
                    ?: return@withLock completeFailure("Song is no longer in the library")
                reportProgress(STAGE_DOWNLOADING, 0, "Finding lyrics for ${song.title}…")
                val (_, state) = lyricsAligner.loadAndClassify(
                    song, forceResync = inputData.getBoolean(INPUT_FORCE_RESYNC, false)
                )
                when (state) {
                    TaisLyricsAligner.AlignmentState.WordSynced -> {
                        reportProgress(STAGE_DONE, 100, "Already word-synced — ${song.title}")
                        return@withLock Result.success(resultData(OUTCOME_SKIPPED, false, "Already word-synced"))
                    }
                    else -> Unit
                }
                reportProgress(STAGE_DOWNLOADING, 0, "Checking pre-synced lyric catalogs…")
                val online = withTimeoutOrNull(25_000L) {
                    try { lyricsAligner.findOnlineSyncedLyrics(song) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Timber.tag(TAG).w(e, "Catalog lookup unavailable"); null }
                }
                if (online != null && !online.lyrics.synced.isNullOrEmpty()) {
                    lyricsAligner.saveOnlineSyncedLyrics(song, online)
                    val vocalLines = online.lyrics.synced.orEmpty().filter { it.line.isNotBlank() }
                    val wordLines = vocalLines.count { !it.words.isNullOrEmpty() }
                    val hasWords = vocalLines.isNotEmpty() && wordLines == vocalLines.size
                    val detail = when {
                        hasWords -> "Word-synced lyrics from ${online.source}"
                        wordLines > 0 -> "Lyrics from ${online.source}; word timing on $wordLines of ${vocalLines.size} lines."
                        else -> "Line-synced lyrics from ${online.source}; word timings are not available for this recording."
                    }
                    reportProgress(STAGE_DONE, 100, detail)
                    return@withLock Result.success(resultData(OUTCOME_SYNCED, hasWords, detail))
                }
                if (state == TaisLyricsAligner.AlignmentState.NoLyrics) {
                    reportProgress(STAGE_DONE, 100, "No lyrics found — skipped ${song.title}")
                    return@withLock Result.success(resultData(OUTCOME_SKIPPED, false, "No lyrics found"))
                }
                reportProgress(STAGE_DOWNLOADING, 0, "Getting ${song.title} ready…")
                val sourceAudioPath = song.spotifyId?.let { spotifyId ->
                    var local: java.io.File? = null
                    for (attempt in 1..3) {
                        currentCoroutineContext().ensureActive()
                        // Calling the cache manager even when a file exists promotes a temporary
                        // playback cache entry to a permanent offline download.
                        local = withTimeoutOrNull(90_000L) {
                            audioCacheManager.downloadAndCache(spotifyStreamProxy, spotifyId, isPermanent = true)
                        }
                        if (local != null) break
                        if (attempt < 3) {
                            reportProgress(STAGE_DOWNLOADING, 0, "Retrying download for ${song.title} ($attempt/2)…")
                            delay(attempt * 2_000L)
                        }
                    }
                    local?.absolutePath ?: throw IOException("Couldn't download ${song.title}. Check your connection and retry this song.")
                } ?: song.contentUriString.ifBlank {
                    inputData.getString(INPUT_SOURCE_AUDIO_PATH).orEmpty()
                }
                check(sourceAudioPath.isNotBlank()) { "This song has no available audio source" }
                reportProgress(STAGE_ALIGNING, ALIGN_RANGE_START, "Syncing ${song.title} word-by-word…")
                val wordSyncProduced = runWordSyncAlignment(song, sourceAudioPath, state) { done, total ->
                    val overall = (ALIGN_RANGE_START + (done.toFloat() / total.coerceAtLeast(1)) * ALIGN_RANGE_SPAN)
                        .toInt().coerceIn(ALIGN_RANGE_START, 100)
                    reportProgress(STAGE_ALIGNING, overall, "${song.title} — pass $done of $total…")
                }
                check(wordSyncProduced) { "No usable word timing was produced. Existing lyrics were kept." }
                reportProgress(STAGE_DONE, 100, "Done — ${song.title} lyrics synced")
                Result.success(resultData(OUTCOME_SYNCED, true))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Lyric sync failed for songId=%s", songId)
            completeFailure(e.message ?: "Couldn't sync this song")
        }
    }

    private fun resultData(outcome: String, produced: Boolean, detail: String? = null) = workDataOf(
        OUTPUT_SONG_ID to inputData.getString(INPUT_SONG_ID),
        OUTPUT_WORD_SYNC_PRODUCED to produced,
        OUTPUT_LYRICS_UPDATED to (outcome == OUTCOME_SYNCED),
        OUTPUT_OUTCOME to outcome,
        OUTPUT_DETAIL to detail
    )

    private fun completeFailure(reason: String): Result {
        return failureResult(
            inputData.getString(INPUT_SONG_ID), reason,
            inputData.getBoolean(INPUT_CONTINUE_ON_FAILURE, false)
        )
    }

    /** Updates both WorkManager's own progress data (for [com.theveloper.pixelplay.presentation.components.tais.TaisStudioProgressCard]) and the foreground notification in one call — both show the same detailed text, not just a generic stage name. */
    private suspend fun reportProgress(stage: String, percent: Int, detailText: String) {
        val batchIndex = inputData.getInt(INPUT_BATCH_INDEX, 0)
        val batchTotal = inputData.getInt(INPUT_BATCH_TOTAL, 0)
        val detail = if (batchTotal > 0) "Song $batchIndex of $batchTotal · $detailText" else detailText
        setProgress(workDataOf(PROGRESS_STAGE to stage, PROGRESS_PERCENT to percent, PROGRESS_DETAIL to detail))
        setForeground(
            TaisForegroundNotifications.foregroundInfo(
                context = applicationContext,
                notificationId = TaisForegroundNotifications.TAIS_STUDIO_NOTIFICATION_ID,
                title = "Lyric Sync",
                text = detail,
                progress = percent,
                indeterminate = stage == STAGE_DOWNLOADING || stage == STAGE_WAITING
            )
        )
    }

    /** Returns true if word-level sync was newly produced (false if skipped — already synced, or no lyrics at all). */
    private suspend fun runWordSyncAlignment(
        song: com.theveloper.pixelplay.data.model.Song,
        sourceAudioPath: String,
        state: TaisLyricsAligner.AlignmentState,
        onWindowProgress: suspend (Int, Int) -> Unit
    ): Boolean {
        val (plainLines, knownLineStartMs) = when (state) {
            is TaisLyricsAligner.AlignmentState.PlainTextOnly -> state.lines to null
            // Real line timestamps make chunk windowing exact instead of a proportional guess —
            // see TaisLyricsAligner.forceAlign's knownLineStartMs doc.
            is TaisLyricsAligner.AlignmentState.LineSyncedOnly -> state.lines.map { it.line } to state.lines.map { it.time }
            TaisLyricsAligner.AlignmentState.WordSynced -> {
                Timber.tag(TAG).d("Skipping alignment for songId=%s — already word-synced", song.id)
                return false
            }
            TaisLyricsAligner.AlignmentState.NoLyrics -> {
                Timber.tag(TAG).d("Skipping alignment for songId=%s — no lyrics found", song.id)
                return false
            }
        }
        if (plainLines.isEmpty()) return false

        val aligned = lyricsAligner.forceAlign(sourceAudioPath, plainLines, knownLineStartMs) { done, total ->
            onWindowProgress(done, total)
        }
        if (aligned.isEmpty()) return false
        lyricsAligner.persistAligned(song, aligned)
        return true
    }

    companion object {
        private const val TAG = "TaisStudioWorker"
        private const val INPUT_SONG_ID = "song_id"
        private const val INPUT_FORCE_RESYNC = "force_resync"
        private const val INPUT_SOURCE_AUDIO_PATH = "source_audio_path"
        private const val INPUT_CONTINUE_ON_FAILURE = "continue_on_failure"
        private const val INPUT_BATCH_INDEX = "batch_index"
        private const val INPUT_BATCH_TOTAL = "batch_total"
        const val REQUEST_CREATED_TAG = "lyric_requested_"
        const val BATCH_CREATED_TAG = "lyric_batch_created_"
        const val OUTPUT_SONG_ID = "lyric_song_id"
        const val OUTPUT_OUTCOME = "lyric_outcome"
        const val OUTPUT_DETAIL = "lyric_detail"
        const val OUTCOME_SYNCED = "synced"
        const val OUTCOME_SKIPPED = "skipped"
        const val OUTCOME_FAILED = "failed"
        const val STAGE_WAITING = "waiting"
        private val syncMutex = Mutex()

        internal fun failureResult(
            songId: String?, reason: String, continueOnFailure: Boolean
        ): androidx.work.ListenableWorker.Result {
            val data = workDataOf(
                OUTPUT_SONG_ID to songId,
                OUTPUT_WORD_SYNC_PRODUCED to false,
                OUTPUT_OUTCOME to OUTCOME_FAILED,
                OUTPUT_FAILURE_REASON to reason.take(800)
            )
            // WorkManager propagates FAILURE to all dependent songs. A batch's individual
            // failure is an explicit result, displayed and retryable by the playlist UI.
            return if (continueOnFailure) androidx.work.ListenableWorker.Result.success(data)
            else androidx.work.ListenableWorker.Result.failure(data)
        }

        fun playlistWorkName(playlistId: String): String = "playlist_lyric_sync_$playlistId"
        const val PROGRESS_STAGE = "stage"
        const val PROGRESS_PERCENT = "percent"
        const val PROGRESS_DETAIL = "detail"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_ALIGNING = "aligning"
        const val STAGE_DONE = "done"
        // Overall-percent range the alignment stage's own fine-grained progress is mapped into.
        private const val ALIGN_RANGE_START = 5
        private const val ALIGN_RANGE_SPAN = 95 // 5..100
        const val OUTPUT_WORD_SYNC_PRODUCED = "word_sync_produced"
        const val OUTPUT_LYRICS_UPDATED = "lyrics_updated"
        const val OUTPUT_FAILURE_REASON = "failure_reason"
        const val WORK_NAME_PREFIX = "tais_lyric_sync_"

        fun uniqueWorkName(songId: String): String = WORK_NAME_PREFIX + songId

        /** See [StemSeparatorWorker.buildRequest] — same reason: lets bulk callers batch these sequentially instead of firing them all in parallel. */
        fun buildRequest(
            songId: String,
            sourceAudioPath: String,
            batchCreatedAt: Long? = null,
            batchIndex: Int = 0,
            batchTotal: Int = 0,
            forceResync: Boolean = false
        ): androidx.work.OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TaisStudioWorker>()
                .setInputData(
                    workDataOf(
                        INPUT_SONG_ID to songId,
                        INPUT_FORCE_RESYNC to forceResync,
                        INPUT_SOURCE_AUDIO_PATH to sourceAudioPath,
                        INPUT_CONTINUE_ON_FAILURE to (batchCreatedAt != null),
                        INPUT_BATCH_INDEX to batchIndex,
                        INPUT_BATCH_TOTAL to batchTotal
                    )
                )
                .apply { batchCreatedAt?.let { addTag(BATCH_CREATED_TAG + it) } }
                .addTag(REQUEST_CREATED_TAG + System.currentTimeMillis())
                .addTag(PIXELPLAY_JOB_TAG)
                .addTag(uniqueWorkName(songId))
                .build()

        fun enqueue(workManager: WorkManager, songId: String, sourceAudioPath: String, forceResync: Boolean = false) {
            workManager.enqueueUniqueWork(
                uniqueWorkName(songId),
                ExistingWorkPolicy.KEEP,
                buildRequest(songId, sourceAudioPath, forceResync = forceResync)
            )
        }
    }
}
