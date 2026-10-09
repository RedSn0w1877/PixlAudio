package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.premium.PlusLicenseManager
import com.theveloper.pixelplay.data.premium.PremiumFeatureLimits
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.tais.lyrics.TaisLyricsAligner
import com.theveloper.pixelplay.utils.LyricsUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Coordinates quiet processing in the foreground and through WorkManager while backgrounded. */
@Singleton
class AutomaticStudioManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val musicRepository: MusicRepository,
    private val audioCacheManager: AudioCacheManager,
    private val engagementDao: EngagementDao,
    private val lyricsAligner: TaisLyricsAligner,
    private val preferences: UserPreferencesRepository,
    private val environment: AutomaticStudioEnvironment,
    /** Songs on their way to Cloud Studio are left to it (the iOS `skipsSong` hook). */
    private val cloudStudio: dagger.Lazy<com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow("Waiting for suitable background conditions")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _lyricsUpdated = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val lyricsUpdated: SharedFlow<String> = _lyricsUpdated.asSharedFlow()
    @Volatile private var visible = false
    @Volatile private var currentSongId: String? = null
    @Volatile private var lyricsEnabled = false
    @Volatile private var instrumentalsEnabled = false
    @Volatile private var readyAfterMs = 0L
    private var graceJob: Job? = null
    private var nextKind = AutomaticStudioKind.LYRICS
    private val observedWork = LinkedHashSet<UUID>()
    private val completedWork = LinkedHashSet<UUID>()
    // True while an automatic job may exist in WorkManager. Starts true (a job from an earlier process
    // could be running) and is corrected by the first scan that actually reads the job list.
    @Volatile private var automaticMayBeRunning = true
    private val idleWalk = IdleWalkMemo()
    private val anyEnabled = MutableStateFlow(false)
    // The job budget survives the process: each WorkManager sweep may start in a fresh one.
    private val budget by lazy {
        val prefs = context.getSharedPreferences("automatic_studio_budget_v1", Context.MODE_PRIVATE)
        AutomaticStudioBudget(object : AutomaticStudioBudget.Store {
            override fun load(): Pair<Long, Int>? =
                if (prefs.contains(BUDGET_WINDOW_KEY)) prefs.getLong(BUDGET_WINDOW_KEY, 0L) to prefs.getInt(BUDGET_JOBS_KEY, 0) else null
            override fun save(windowStartedMs: Long, jobs: Int) {
                prefs.edit().putLong(BUDGET_WINDOW_KEY, windowStartedMs).putInt(BUDGET_JOBS_KEY, jobs).apply()
            }
        })
    }
    private val ledgerPreferences by lazy { context.getSharedPreferences("automatic_studio_cooldowns_v1", Context.MODE_PRIVATE) }
    private val cooldowns by lazy {
        AutomaticStudioCooldowns(ledgerPreferences.all.mapNotNull { (key, value) ->
            (value as? Long)?.let { key to it }
        }.toMap())
    }

    init {
        scheduleBackgroundSweep()
        scope.launch {
            combine(preferences.automaticLyricsEnabledFlow, preferences.automaticInstrumentalsEnabledFlow) { lyrics, stems ->
                lyrics to stems
            }.collect { (lyrics, stems) ->
                lyricsEnabled = lyrics
                instrumentalsEnabled = stems
                anyEnabled.value = lyrics || stems
                idleWalk.invalidate()
                environment.setEnabled(lyrics, stems)
                if (!lyrics) workManager.cancelAllWorkByTag(AUTO_LYRICS_TAG)
                if (!stems) workManager.cancelAllWorkByTag(AUTO_INSTRUMENTAL_TAG)
                if (!lyrics && !stems) {
                    workManager.cancelUniqueWork(BACKGROUND_SWEEP_NAME)
                } else {
                    scheduleBackgroundSweep()
                }
                requestScan()
            }
        }
        scope.launch {
            // Watching WorkManager's job rows is only useful while a feature is on; every row change of
            // any PixelPlay job (one per downloaded song too) used to wake a scan, switches off or not.
            anyEnabled.collectLatest { enabled ->
                if (enabled) workManager.getWorkInfosByTagFlow(PIXELPLAY_JOB_TAG).collect { requestScan() }
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                // Recheck battery/thermal/cache availability while the process is alive, but only when
                // a scan could actually start something (not while music plays, the app is on screen or
                // both switches are off).
                if (AutomaticStudioPolicy.shouldPoll(anyEnabled.value, PlaybackActivityTracker.isPlaybackActive, visible)) {
                    requestScan()
                }
            }
        }
        scope.launch {
            for (ignored in requests) {
                delay(750) // Coalesce playback, settings, and progress callbacks.
                try { scan() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Timber.d(error, "Automatic studio will try again when conditions change")
                    _status.value = "Automatic processing is waiting; manual controls are available"
                }
            }
        }
    }

    /**
     * Another producer (Cloud Studio's import) saved new lyrics for [songId]: the lyrics screen reloads them when it
     * shows that song, exactly as after an automatic lyric sync.
     */
    fun noteLyricsUpdated(songId: String) {
        _lyricsUpdated.tryEmit(songId)
    }

    @Synchronized
    fun setAppVisible(isVisible: Boolean) {
        if (isVisible && !visible) {
            allowPlaybackToSettle()
        }
        if (isVisible != visible) idleWalk.invalidate()
        visible = isVisible
        environment.setAppVisible(isVisible)
        if (!isVisible) {
            graceJob?.cancel()
            _status.value = "Automatic processing continues quietly in the background"
        }
        scanNow()
    }

    @Synchronized
    fun onSongChanged(songId: String?) {
        val canonical = songId?.takeIf(String::isNotBlank)?.let(TaisInstrumentalIndex::canonicalSongId)
        if (canonical == currentSongId) return
        currentSongId = canonical
        idleWalk.invalidate()
        if (visible) allowPlaybackToSettle()
        requestScan()
    }

    private fun allowPlaybackToSettle() {
        readyAfterMs = SystemClock.elapsedRealtime() + 15_000
        graceJob?.cancel()
        graceJob = scope.launch { delay(15_000); scanNow() }
    }

    /** A Settings action: re-evaluate eligibility; never bypass battery, cooldowns, or user preferences. */
    fun scanNow() {
        idleWalk.invalidate()
        requestScan()
    }

    private fun requestScan() { requests.trySend(Unit) }

    /** Entry point for the WorkManager sweep. It refreshes persisted settings because this may be
     * the first object created in a newly started background process. */
    internal suspend fun runBackgroundSweep() {
        lyricsEnabled = preferences.automaticLyricsEnabledFlow.first()
        instrumentalsEnabled = preferences.automaticInstrumentalsEnabledFlow.first()
        environment.setEnabled(lyricsEnabled, instrumentalsEnabled)
        anyEnabled.value = lyricsEnabled || instrumentalsEnabled
        idleWalk.invalidate()
        automaticMayBeRunning = true // a fresh process: read the job list at least once
        scan()
    }

    private fun scheduleBackgroundSweep() {
        val request = buildSweepRequest()
        scope.launch(Dispatchers.IO) {
            runCatching {
                // UPDATE (not KEEP) so installs that already have the old 15-minute, any-power sweep
                // pick up the charger-only, few-hours one.
                workManager.enqueueUniquePeriodicWork(
                    BACKGROUND_SWEEP_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }.onFailure { Timber.d(it, "Unable to schedule automatic background sweep") }
        }
    }

    private suspend fun scan() {
        val enabled = lyricsEnabled || instrumentalsEnabled
        val playbackActive = PlaybackActivityTracker.isPlaybackActive
        if (!AutomaticStudioPolicy.scanNeedsWorkQuery(enabled, playbackActive, visible, automaticMayBeRunning)) {
            // Nothing to schedule and no automatic job to clean up: don't touch WorkManager at all.
            _status.value = when {
                !enabled -> "Automatic processing is off"
                playbackActive -> "Waiting until playback is idle"
                else -> "Automatic processing waits until you leave the app"
            }
            return
        }
        // All blocking WorkManager operations run on this IO coordinator, never the UI thread.
        val infos = workManager.getWorkInfosByTag(PIXELPLAY_JOB_TAG).get(5, TimeUnit.SECONDS)
        val automatic = infos.filter { AUTO_STUDIO_WORK_TAG in it.tags }
        automaticMayBeRunning = automatic.any { !it.state.isFinished }
        observedWork.addAll(automatic.filterNot { it.state.isFinished }.map { it.id })
        automatic.filter { it.state.isFinished && it.id in observedWork && completedWork.add(it.id) }
            .forEach { recordCompletion(it) }
        while (completedWork.size > 256) {
            val old = completedWork.first()
            completedWork.remove(old)
            observedWork.remove(old)
        }
        if (!lyricsEnabled && !instrumentalsEnabled) {
            _status.value = "Automatic processing is off"
            return
        }
        val manual = infos.any {
            !it.state.isFinished && AUTO_STUDIO_WORK_TAG !in it.tags && it.pixelPlayJobKind() in setOf(
                PixelPlayJobKind.LYRICS_SYNC, PixelPlayJobKind.STEM_SEPARATION, PixelPlayJobKind.BS_ROFORMER_RENDER
            )
        }
        if (manual) {
            if (automaticMayBeRunning) workManager.cancelAllWorkByTag(AUTO_STUDIO_WORK_TAG)
            _status.value = "Waiting for your manually started processing"
            return
        }
        if (playbackActive) {
            if (automaticMayBeRunning) workManager.cancelAllWorkByTag(AUTO_STUDIO_WORK_TAG)
            _status.value = "Waiting until playback is idle"
            return
        }
        automatic.firstOrNull { !it.state.isFinished }?.let { active ->
            _status.value = active.progress.getString(TaisStudioWorker.PROGRESS_DETAIL)
                ?: "Preparing the next song quietly"
            return
        }
        if (visible) {
            // Unattended DSP keeps several cores and tens of MB busy for minutes and is thrown away when
            // the user presses play: it waits until the app is off screen (leaving it triggers a scan).
            _status.value = "Automatic processing waits until you leave the app"
            return
        }
        if (SystemClock.elapsedRealtime() < readyAfterMs) {
            _status.value = "Letting playback settle before automatic processing"
            return
        }
        val now = System.currentTimeMillis()
        val entitlement = PlusLicenseManager(context).activeEntitlement()
        val jobLimit = PremiumFeatureLimits.backgroundJobsPerWindow(entitlement, now)
        if (budget.jobsInWindow(now) >= jobLimit) {
            _status.value = "Automatic processing is paced to protect battery and playback"
            return
        }
        val kinds = listOf(nextKind, if (nextKind == AutomaticStudioKind.LYRICS) AutomaticStudioKind.INSTRUMENTAL else AutomaticStudioKind.LYRICS)
            .filter { (it == AutomaticStudioKind.LYRICS && lyricsEnabled) || (it == AutomaticStudioKind.INSTRUMENTAL && instrumentalsEnabled) }
        val allowedKinds = kinds.filter { environment.blockedReason(it) == null }
        if (allowedKinds.isEmpty()) {
            _status.value = environment.blockedReason(kinds.first()) ?: "Waiting for suitable conditions"
            return
        }
        // Cloud Studio's stored jobs are read first, so a song already on its way there is skipped on a cold start too.
        try {
            cloudStudio.get().loadForDisplay()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.d("Cloud Studio jobs unreadable: %s", error.javaClass.simpleName)
        }
        if (idleWalk.shouldSkipWalk(now)) {
            // The last full walk found nothing to schedule and nothing relevant changed since.
            _status.value = "Up to date for your recent songs; instrumentals use audio already on this phone"
            return
        }
        for (song in candidates()) {
            if (!AutomaticStudioPolicy.canProcessDuration(song.duration)) continue
            if (cloudStudio.get().hasPendingJob(song.id)) continue
            val localAudio = AutomaticStudioEnvironment.localAudio(song, audioCacheManager)
            for (kind in allowedKinds) {
                val now = System.currentTimeMillis()
                val key = ledgerKey(kind, song.id)
                if (cooldowns.until(key) > now) continue
                val complete = when (kind) {
                    AutomaticStudioKind.INSTRUMENTAL -> TaisInstrumentalIndex.bestAvailableFile(context, song.id) != null
                    AutomaticStudioKind.LYRICS -> {
                        val lyrics = musicRepository.getStoredLyrics(song)?.first
                            ?: song.lyrics?.let(LyricsUtils::parseLyrics)
                        lyricsAligner.alignmentStateFor(lyrics) == TaisLyricsAligner.AlignmentState.WordSynced
                    }
                }
                if (!AutomaticStudioPolicy.canSchedule(kind, localAudio != null, complete, cooldowns.until(key), now)) continue
                // Visibility/preferences may change while Room and the audio cache are read.
                if (environment.blockedReason(kind) != null) return
                val request = when (kind) {
                    AutomaticStudioKind.LYRICS -> TaisStudioWorker.buildRequest(song.id, localAudio ?: song.contentUriString, automatic = true)
                    AutomaticStudioKind.INSTRUMENTAL -> StemSeparatorWorker.buildRequest(song.id, localAudio!!, automatic = true)
                }
                workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request).result.get(5, TimeUnit.SECONDS)
                // KEEP may retain an older process's active request. Only count requests actually inserted.
                if (workManager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS) != null) {
                    observedWork += request.id
                    automaticMayBeRunning = true
                    budget.recordJob(now)
                    recordCooldown(key, now + 15 * 60_000L) // Avoid a crash/restart retry loop.
                    nextKind = if (kind == AutomaticStudioKind.LYRICS) AutomaticStudioKind.INSTRUMENTAL else AutomaticStudioKind.LYRICS
                    _status.value = if (kind == AutomaticStudioKind.LYRICS) "Finding synced lyrics for ${song.title}" else "Preparing an instrumental for ${song.title}"
                }
                return
            }
        }
        idleWalk.markNothingToDo(System.currentTimeMillis())
        _status.value = "Up to date for your recent songs; instrumentals use audio already on this phone"
    }

    private suspend fun candidates(): List<Song> {
        val current = currentSongId?.let { musicRepository.getSong(it).first() }
        val all = musicRepository.getAllSongsOnce()
        if (all.isEmpty()) return listOfNotNull(current)
        val engagement = engagementDao.getAllEngagements().associateBy { it.songId }
        val now = System.currentTimeMillis()
        return all.asSequence()
            .plus(listOfNotNull(current).asSequence())
            .distinctBy { it.id }
            .sortedByDescending { song ->
                val stats = engagement[song.id]
                AutomaticStudioPolicy.priority(
                    song.id,
                    current?.id ?: currentSongId,
                    song.isFavorite,
                    stats?.playCount ?: 0,
                    stats?.lastPlayedTimestamp ?: 0L,
                    now
                )
            }
            .toList()
    }

    private suspend fun recordCompletion(info: WorkInfo) {
        val kind = if (AUTO_LYRICS_TAG in info.tags) AutomaticStudioKind.LYRICS else AutomaticStudioKind.INSTRUMENTAL
        val songId = info.tags.firstOrNull { it.startsWith(AUTO_SONG_TAG_PREFIX) }?.removePrefix(AUTO_SONG_TAG_PREFIX) ?: return
        val deferred = info.state == WorkInfo.State.CANCELLED || info.outputData.getBoolean(OUTPUT_AUTOMATIC_DEFERRED, false)
        val timedOut = info.outputData.getBoolean(OUTPUT_AUTOMATIC_TIMED_OUT, false)
        val failed = info.state == WorkInfo.State.FAILED || info.outputData.getString(TaisStudioWorker.OUTPUT_OUTCOME) == TaisStudioWorker.OUTCOME_FAILED
        val delay = when {
            timedOut -> AutomaticStudioPolicy.TIMEOUT_COOLDOWN_MS
            deferred -> AutomaticStudioPolicy.DEFERRED_COOLDOWN_MS
            failed -> AutomaticStudioPolicy.FAILURE_COOLDOWN_MS
            kind == AutomaticStudioKind.LYRICS && !info.outputData.getBoolean(TaisStudioWorker.OUTPUT_WORD_SYNC_PRODUCED, false) -> AutomaticStudioPolicy.CATALOG_COOLDOWN_MS
            else -> 7 * 24 * 60 * 60_000L // Complete artifacts are also checked before any future scheduling.
        }
        recordCooldown(ledgerKey(kind, songId), System.currentTimeMillis() + delay)
        idleWalk.invalidate()
        if (kind == AutomaticStudioKind.LYRICS && info.state == WorkInfo.State.SUCCEEDED &&
            info.outputData.getBoolean(TaisStudioWorker.OUTPUT_LYRICS_UPDATED, false)) _lyricsUpdated.emit(songId)
    }

    private fun recordCooldown(key: String, deadline: Long) {
        cooldowns.record(key, deadline)
        ledgerPreferences.edit().clear().apply {
            cooldowns.snapshot().forEach { (id, until) -> putLong(id, until) }
        }.apply()
    }

    private fun ledgerKey(kind: AutomaticStudioKind, songId: String) = "${kind.name}:$songId"
    internal companion object {
        const val UNIQUE_WORK_NAME = "automatic_studio_one_at_a_time"
        const val BACKGROUND_SWEEP_NAME = "automatic_studio_background_sweep"
        private const val BUDGET_WINDOW_KEY = "window_started_ms"
        private const val BUDGET_JOBS_KEY = "jobs"

        /** The wake-up that finds the next song: on a charger only, every few hours. */
        fun buildSweepRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<AutomaticStudioSweepWorker>(AutomaticStudioPolicy.SWEEP_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(automaticStudioConstraints())
                .build()
    }
}
