package com.theveloper.pixelplay.data.cloudstudio.auto

import com.theveloper.pixelplay.data.cloudstudio.CloudBudget
import com.theveloper.pixelplay.data.cloudstudio.CloudSong
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioSettingsSource

/** What the runner needs from Cloud Studio's engine (a seam: tests use a fake; [CloudStudioEngine] is the real one). */
interface CloudAutoEngine {
    suspend fun load()
    /** The cloud can take an automatic job: on, set up, keys fine, room under the month's cap. */
    fun usable(): Boolean
    /** The person has a Sing or a batch on its way: automatic preparing stays out of its way. */
    fun personHasWork(): Boolean
    /** Songs (canonical ids) that already have a job that isn't finished. */
    fun pendingCanonicalIds(): Set<String>
    /** Automatic jobs that aren't finished. */
    fun outstandingAutoJobs(): Int
    fun autoCommittedMicroUsd(): Long
    /** What this month has used or will use, over every job (the shared cap's side). */
    fun committedMicroUsd(): Long
    suspend fun sendAuto(songs: List<CloudSong>): Int
    /** Prepares, uploads, sends and collects whatever is due, until [deadlineMs]. */
    suspend fun workerPass(deadlineMs: Long)
}

/** Songs that rest after being picked (a failure or a dropped job), kept across runs. */
interface CloudAutoCooldowns {
    fun isResting(songId: String, nowMs: Long): Boolean
    fun rest(songId: String, untilMs: Long)
}

/** Where a run reports to (the settings screen's "Why these songs" list). */
interface CloudAutoReport {
    fun picked(lines: List<String>)
}

/** How a run ended, for the log and the tests. */
enum class CloudAutoRunOutcome {
    /** Smart auto-prepare (or Cloud processing) is off. */
    OFF,
    /** The cloud can't take it now (keys, cap) or the person's own work is going on. */
    UNAVAILABLE,
    /** The phone isn't in a state for it (Wi-Fi, charger, heat, storage, music). */
    WAITING,
    /** Nothing new was picked; leftover jobs got a pass. */
    PASS_ONLY,
    /** New jobs were made and a pass ran. */
    SENT,
    /** Nothing in the library is worth preparing within today's and the month's room. */
    NOTHING_TO_SEND,
}

data class CloudAutoRunResult(val outcome: CloudAutoRunOutcome, val added: Int = 0, val blockedBy: AutoBlock? = null)

/**
 * One run of Smart auto-prepare (the 6-hourly worker's body), kept free of WorkManager and Android so it is tested:
 * check the switches and the phone, rank the library, pick the few songs whose audio is already on the phone that fit
 * today's cap and both budgets, make their jobs, then run a pass inside the worker so the work only happens while the
 * worker's own conditions (Wi-Fi, a charger, battery and storage not low) hold. Leftovers continue in the next window.
 * It never touches the network for audio: songs not already cached are skipped.
 */
class CloudAutoPrepareRunner(
    private val settings: CloudStudioSettingsSource,
    private val environment: CloudAutoEnvironment,
    private val facts: CloudAutoFacts,
    private val engine: CloudAutoEngine,
    private val cooldowns: CloudAutoCooldowns,
    private val report: CloudAutoReport,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val dayStartMs: (Long) -> Long = { CloudBudget.dayStartMs(it, java.time.ZoneId.systemDefault()) },
) {
    suspend fun run(deadlineMs: Long): CloudAutoRunResult {
        val snapshot = settings.snapshot()
        if (!snapshot.enabled || !snapshot.autoPrepareEnabled) return CloudAutoRunResult(CloudAutoRunOutcome.OFF)
        engine.load()
        if (engine.personHasWork() || !engine.usable()) return CloudAutoRunResult(CloudAutoRunOutcome.UNAVAILABLE)
        val block = CloudAutoConditions.block(environment.conditions())
        if (block != null) return CloudAutoRunResult(CloudAutoRunOutcome.WAITING, blockedBy = block)

        // Jobs already on their way get their pass; more are only made once few are outstanding.
        if (engine.outstandingAutoJobs() >= CloudAutoPrepare.BATCH_SIZE) {
            engine.workerPass(deadlineMs)
            return CloudAutoRunResult(CloudAutoRunOutcome.PASS_ONLY)
        }

        val now = nowMs()
        val dailyRemaining = snapshot.autoPrepareDailyCap - settings.autoDayCount(dayStartMs(now))
        val capRemaining = CloudBudget.remainingMicroUsd(snapshot.effectiveMonthlyCapMicroUsd, engine.committedMicroUsd())
        val ranked = CloudAutoPrepare.rank(facts.gather(engine.pendingCanonicalIds(), now), now)
            .take(CloudAutoPrepare.CACHE_CHECK_LIMIT)
            .map { it.copy(facts = it.facts.copy(audioCached = facts.isAudioCached(it.facts))) }
        val selection = CloudAutoPrepare.select(
            ranked,
            AutoSelectionState(
                dailyRemaining = dailyRemaining,
                autoBudgetMicroUsd = snapshot.effectiveAutoBudgetMicroUsd,
                autoCommittedMicroUsd = engine.autoCommittedMicroUsd(),
                capRemainingMicroUsd = capRemaining,
                pricePerSecondMicroUsd = snapshot.effectivePricePerSecondMicroUsd,
                quality = snapshot.quality,
                isCoolingDown = { cooldowns.isResting(it, now) },
            ),
        )
        report.picked(selection.picks.ifEmpty { ranked.take(WHY_LINES) }.map { CloudAutoPrepare.whyLine(it, now) })
        if (selection.picks.isEmpty()) {
            engine.workerPass(deadlineMs) // leftovers still get their turn
            return CloudAutoRunResult(CloudAutoRunOutcome.NOTHING_TO_SEND)
        }
        // They rest for a while whatever happens to the job, so a song that keeps dropping isn't picked every run.
        selection.picks.forEach { cooldowns.rest(it.facts.songId, now + CloudAutoPrepare.COOLDOWN_MS) }
        val added = engine.sendAuto(selection.picks.map { it.facts.toCloudSong() })
        engine.workerPass(deadlineMs)
        return CloudAutoRunResult(if (added > 0) CloudAutoRunOutcome.SENT else CloudAutoRunOutcome.NOTHING_TO_SEND, added)
    }

    private fun AutoCandidateFacts.toCloudSong() = CloudSong(
        id = songId, title = title, artist = artist, album = "", durationMs = durationMs, isStreamed = isStreamed,
        hasAudioSource = true,
    )

    private companion object {
        /** The developer's "Why these songs" list shows the best of the run. */
        const val WHY_LINES = 8
    }
}
