package com.theveloper.pixelplay.data.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * Shared WorkManager tag every job-producing worker in the app adds to its requests, so the
 * home screen's "active jobs" list can observe all of them through one
 * `getWorkInfosByTagFlow` query instead of needing to know every worker's unique-work-name
 * scheme up front (several of them, like [BsRoformerRenderWorker]/[StemSeparatorWorker]/
 * [TaisStudioWorker], key their unique name off a per-song id that isn't enumerable in
 * advance).
 */
const val PIXELPLAY_JOB_TAG = "pixelplay_job"

/** What kind of work a [WorkInfo] tagged with [PIXELPLAY_JOB_TAG] represents, for display. */
enum class PixelPlayJobKind(val label: String) {
    LIBRARY_SYNC("Library sync"),
    SPOTIFY_SYNC("Spotify sync"),
    SPOTIFY_MATCH("Finding audio"),
    SONG_DOWNLOAD("Downloading song"),
    STEM_SEPARATION("Separating stems"),
    BS_ROFORMER_RENDER("Rendering instrumental"),
    LYRICS_SYNC("Syncing lyrics"),
    AI_TASK("AI task"),
    AI_DAILY_MIX("Curating Daily Mix"),
    UNKNOWN("Working")
}

/**
 * Classifies a tagged [WorkInfo] by the unique-work-name prefix its worker uses (or, for
 * [AiWorker], its own dedicated tag) — the tag alone can't tell workers apart since they all
 * share it on purpose.
 */
fun WorkInfo.pixelPlayJobKind(): PixelPlayJobKind = when {
    tags.contains(AiDailyMixWorker.WORK_NAME) -> PixelPlayJobKind.AI_DAILY_MIX
    tags.contains(AiWorker.WORK_NAME) -> PixelPlayJobKind.AI_TASK
    tags.any { it == SyncWorker.WORK_NAME || it == SyncWorker.PERIODIC_MAINTENANCE_WORK_NAME } ->
        PixelPlayJobKind.LIBRARY_SYNC
    tags.contains(SpotifySyncWorker.WORK_NAME) -> PixelPlayJobKind.SPOTIFY_SYNC
    tags.contains(SpotifyMatchWorker.WORK_NAME) -> PixelPlayJobKind.SPOTIFY_MATCH
    tags.any { it.startsWith(SongDownloadWorker.WORK_NAME_PREFIX) } -> PixelPlayJobKind.SONG_DOWNLOAD
    tags.any { it.startsWith(StemSeparatorWorker.WORK_NAME_PREFIX) } -> PixelPlayJobKind.STEM_SEPARATION
    tags.any { it.startsWith(BsRoformerRenderWorker.WORK_NAME_PREFIX) } -> PixelPlayJobKind.BS_ROFORMER_RENDER
    tags.any { it.startsWith(TaisStudioWorker.WORK_NAME_PREFIX) } -> PixelPlayJobKind.LYRICS_SYNC
    tags.any { it.startsWith(LyricsAlignerWorker.WORK_NAME_PREFIX) } -> PixelPlayJobKind.LYRICS_SYNC
    else -> PixelPlayJobKind.UNKNOWN
}

/**
 * Enqueues [requests] to run strictly one at a time, oldest first, instead of WorkManager's
 * default of running everything with a distinct unique-work-name in parallel.
 *
 * This matters specifically for anything that resolves/downloads YouTube audio
 * ([StemSeparatorWorker], [TaisStudioWorker], both need a local copy of Spotify-matched
 * songs before they can run): firing a whole playlist's worth of these at once floods the
 * same YouTube resolution pipeline live playback depends on — confirmed by real logs where
 * dozens of parallel [StemSeparatorWorker] jobs from a single "Instrumentalize all songs"
 * tap starved the currently-playing song's own audio resolution and made every one of those
 * jobs fail outright. [SongDownloadWorker] already avoids this by sharing one queue name;
 * this does the same thing for workers whose per-song unique name would otherwise let them
 * all run at once.
 */
fun WorkManager.enqueueSequentialBatch(
    batchWorkName: String,
    requests: List<OneTimeWorkRequest>,
    policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
) {
    if (requests.isEmpty()) return
    var continuation = beginUniqueWork(batchWorkName, policy, requests.first())
    for (index in 1 until requests.size) {
        continuation = continuation.then(requests[index])
    }
    continuation.enqueue()
}
