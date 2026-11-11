package com.theveloper.pixelplay.data.spatial.library

import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus
import kotlin.math.min

// The library's pure rules (cloud-audio-spec §6.1, §6.2, §6.4): the status of one song and profile, the failure
// backoff, the network a step needs, and the background queue's conditions and pace. [CloudSpatialLibrary] only
// sequences the I/O around them, so these are what the tests pin.

/** One song (its YouTube video, the library key) and one profile. */
data class SpatialRenderKey(val videoId: String, val profile: SpatialProfile)

/** What the library itself is doing for a song and profile before a cloud job carries it (§6.2 steps 2-3). */
enum class SpatialActivity {
    /** Checking the shared library for a finished render: `queued`. */
    LOOKUP,

    /** Downloading a render the shared library has: `downloading`. */
    FETCH,

    /** Wanted, but there is no network the person allows for cloud work: `queued`, reason `waitingForNetwork`. */
    WAITING_FOR_NETWORK,

    /** Wanted, but this month's limit for library jobs is reached: `queued`, reason `capReached`. */
    CAP_REACHED,

    /** Handing the job to Cloud Studio's engine: `queued`. */
    SENDING,
}

/** The network facts the library decides on, as they change. */
data class SpatialNetwork(
    val connected: Boolean = false,
    /** Wi-Fi, Ethernet, or a network the carrier doesn't meter right now. */
    val unmetered: Boolean = false,
    /** Android's Data Saver (the role of iOS Low Data Mode). */
    val dataSaver: Boolean = false,
) {
    /** A network the person allows for cloud work (§6.2 step 2): unmetered, or any with "Use mobile data" on. */
    fun allowsCloudWork(useCellular: Boolean): Boolean = connected && (unmetered || useCellular)

    /** The background queue's network (§6.4): unmetered, and never while Data Saver is on. */
    val allowsAhead: Boolean get() = connected && unmetered && !dataSaver
}

/** A recorded failure for one song and profile: how many in a row, and when the last one happened. */
data class SpatialFailure(
    val count: Int,
    val lastFailedAtMs: Long,
) {
    /** When the next attempt may start (§6.1: 10 minutes, 1 hour, 1 day). */
    val retryAtMs: Long get() = lastFailedAtMs + SpatialBackoff.delayMs(count)
}

/** The failure backoff of §6.1: retry later after 10 minutes, then 1 hour, then 1 day (and every day after that). */
object SpatialBackoff {
    val LADDER_MS: List<Long> = listOf(10L * 60_000, 60L * 60_000, 24L * 60 * 60_000)

    /** The wait after [failures] failures in a row (1 or more). */
    fun delayMs(failures: Int): Long = LADDER_MS[(failures - 1).coerceIn(0, LADDER_MS.size - 1)]

    /** [failure] (if any) still holds a new attempt back at [nowMs]. */
    fun isWaiting(failure: SpatialFailure?, nowMs: Long): Boolean = failure != null && nowMs < failure.retryAtMs
}

/** §6.1's state for one song and profile, from what the library knows. */
object SpatialStatusRules {
    /**
     * The library's status (never [SpatialRenditionStatus.PREVIEW], the player's fact):
     * - a verified render in the local store is [SpatialRenditionStatus.READY];
     * - else what the library itself is doing ([activity]);
     * - else the pending cloud job for it ([job], see [jobStatus]);
     * - else a recorded failure is [SpatialRenditionStatus.FAILED] until a new attempt starts;
     * - else [SpatialRenditionStatus.NONE].
     */
    fun status(
        hasRender: Boolean,
        activity: SpatialActivity?,
        job: CloudJobRecord?,
        failed: Boolean,
        personNetworkOk: Boolean,
        aheadNetworkOk: Boolean,
        capReached: Boolean,
    ): SpatialRenditionStatus = when {
        hasRender -> SpatialRenditionStatus.READY
        activity != null -> when (activity) {
            SpatialActivity.LOOKUP, SpatialActivity.SENDING -> SpatialRenditionStatus.QUEUED
            SpatialActivity.FETCH -> SpatialRenditionStatus.DOWNLOADING
            SpatialActivity.WAITING_FOR_NETWORK -> SpatialRenditionStatus.WAITING_FOR_NETWORK
            SpatialActivity.CAP_REACHED -> SpatialRenditionStatus.CAP_REACHED
        }
        job != null && job.state.isPending ->
            jobStatus(job.state, if (job.isLibraryAhead) aheadNetworkOk else personNetworkOk, capReached)
        failed -> SpatialRenditionStatus.FAILED
        else -> SpatialRenditionStatus.NONE
    }

    /**
     * A pending library job's status: preparing and uploading are `queued` (or wait for the network the job's rules
     * allow), an upload the month's limit holds back is `capReached`, a job waiting for a worker is `queued`, the GPU
     * is `processing`, and the finished renders coming down are `downloading` (or wait for the network).
     */
    fun jobStatus(state: CloudJobState, networkOk: Boolean, capReached: Boolean): SpatialRenditionStatus = when (state) {
        CloudJobState.QUEUED, CloudJobState.PREPARING, CloudJobState.UPLOADING ->
            if (networkOk) SpatialRenditionStatus.QUEUED else SpatialRenditionStatus.WAITING_FOR_NETWORK
        CloudJobState.UPLOADED -> if (capReached) SpatialRenditionStatus.CAP_REACHED else SpatialRenditionStatus.QUEUED
        CloudJobState.SUBMITTED -> SpatialRenditionStatus.QUEUED
        CloudJobState.RUNNING -> SpatialRenditionStatus.PROCESSING
        CloudJobState.RESULTS_READY ->
            if (networkOk) SpatialRenditionStatus.DOWNLOADING else SpatialRenditionStatus.WAITING_FOR_NETWORK
        CloudJobState.DOWNLOADING -> SpatialRenditionStatus.DOWNLOADING
        CloudJobState.IMPORTED, CloudJobState.FAILED, CloudJobState.CANCELLED, CloudJobState.EXPIRED ->
            SpatialRenditionStatus.NONE
    }
}

/** The phone's state for the background queue (§6.4), read once per run. */
data class SpatialAheadConditions(
    val network: SpatialNetwork,
    /** 0-100, or -1 when unknown. */
    val batteryPercent: Int,
)

/** The background queue's rules and pace (§6.4). */
object SpatialAheadRules {
    /** "Battery not low": the same line Sing's automatic preparing keeps. */
    const val LOW_BATTERY_PERCENT = 15

    /** A run every 2 hours (WorkManager, unmetered, battery not low): enough runs to reach 25 songs a day. */
    const val PERIOD_HOURS = 2L

    /** The best-ranked songs looked at in one run. */
    const val CANDIDATES_PER_RUN = 40

    /** Renders fetched from the shared library in one run (each tens of MB, on Wi-Fi). */
    const val FETCHES_PER_RUN = 12

    /** Cloud jobs sent in one run at most, so the day's count spreads over the day. */
    const val JOBS_PER_RUN = 8

    /** Why the queue may not run now, or null when it may: Wi-Fi only, battery not low, never under Data Saver. */
    fun block(conditions: SpatialAheadConditions): String? = when {
        !conditions.network.connected || !conditions.network.unmetered -> "Waiting for Wi-Fi"
        conditions.network.dataSaver -> "Waiting for Data Saver to be off"
        conditions.batteryPercent in 0 until LOW_BATTERY_PERCENT -> "Waiting for the battery to charge"
        else -> null
    }

    /** How many jobs this run may send: what the day leaves (`cloudAudio.dailyAhead`), at most [JOBS_PER_RUN]. */
    fun jobRoom(dailyAhead: Int, sentToday: Int): Int = min(JOBS_PER_RUN, dailyAhead - sentToday).coerceAtLeast(0)
}
