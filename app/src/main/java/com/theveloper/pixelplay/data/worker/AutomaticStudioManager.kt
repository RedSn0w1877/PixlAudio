package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
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
    private val environment: AutomaticStudioEnvironment
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
    private val jobsThisVisit = AtomicInteger(0)
    @Volatile private var readyAfterMs = 0L
    private var graceJob: Job? = null
    private var nextKind = AutomaticStudioKind.LYRICS
    private var budgetWindowStartedMs = System.currentTimeMillis()
    private val observedWork = LinkedHashSet<UUID>()
    private val completedWork = LinkedHashSet<UUID>()
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
                environment.setEnabled(lyrics, stems)
                if (!lyrics) workManager.cancelAllWorkByTag(AUTO_LYRICS_TAG)
                if (!stems) workManager.cancelAllWorkByTag(AUTO_INSTRUMENTAL_TAG)
                if (!lyrics && !stems) {
                    workManager.cancelUniqueWork(BACKGROUND_SWEEP_NAME)
                } else {
                    scheduleBackgroundSweep()
                }
                scanNow()
            }
        }
        scope.launch {
            workManager.getWorkInfosByTagFlow(PIXELPLAY_JOB_TAG).collect { scanNow() }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                scanNow() // Recheck battery/thermal/cache availability while the process is alive.
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

    @Synchronized
    fun setAppVisible(isVisible: Boolean) {
        if (isVisible && !visible) {
            jobsThisVisit.set(0)
            allowPlaybackToSettle()
        }
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
        if (visible) allowPlaybackToSettle()
        scanNow()
    }

    private fun allowPlaybackToSettle() {
        readyAfterMs = SystemClock.elapsedRealtime() + 15_000
        graceJob?.cancel()
        graceJob = scope.launch { delay(15_000); scanNow() }
    }

    /** A Settings action: re-evaluate eligibility; never bypass battery, cooldowns, or user preferences. */
    fun scanNow() { requests.trySend(Unit) }

    /** Entry point for the WorkManager sweep. It refreshes persisted settings because this may be
     * the first object created in a newly started background process. */
    internal suspend fun runBackgroundSweep() {
        lyricsEnabled = preferences.automaticLyricsEnabledFlow.first()
        instrumentalsEnabled = preferences.automaticInstrumentalsEnabledFlow.first()
        environment.setEnabled(lyricsEnabled, instrumentalsEnabled)
        scan()
    }

    private fun scheduleBackgroundSweep() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<AutomaticStudioSweepWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        scope.launch(Dispatchers.IO) {
            runCatching {
                workManager.enqueueUniquePeriodicWork(
                    BACKGROUND_SWEEP_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }.onFailure { Timber.d(it, "Unable to schedule automatic background sweep") }
        }
    }

    private suspend fun scan() {
        // All blocking WorkManager operations run on this IO coordinator, never the UI thread.
        val infos = workManager.getWorkInfosByTag(PIXELPLAY_JOB_TAG).get(5, TimeUnit.SECONDS)
        val automatic = infos.filter { AUTO_STUDIO_WORK_TAG in it.tags }
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
            workManager.cancelAllWorkByTag(AUTO_STUDIO_WORK_TAG)
            _status.value = "Waiting for your manually started processing"
            return
        }
        if (PlaybackActivityTracker.isPlaybackActive) {
            workManager.cancelAllWorkByTag(AUTO_STUDIO_WORK_TAG)
            _status.value = "Waiting until playback is idle"
            return
        }
        automatic.firstOrNull { !it.state.isFinished }?.let { active ->
            _status.value = active.progress.getString(TaisStudioWorker.PROGRESS_DETAIL)
                ?: "Preparing the next song quietly"
            return
        }
        if (SystemClock.elapsedRealtime() < readyAfterMs) {
            _status.value = "Letting playback settle before automatic processing"
            return
        }
        val now = System.currentTimeMillis()
        if (now - budgetWindowStartedMs >= AutomaticStudioPolicy.BACKGROUND_WINDOW_MS) {
            budgetWindowStartedMs = now
            jobsThisVisit.set(0)
        }
        val entitlement = PlusLicenseManager(context).activeEntitlement()
        val jobLimit = PremiumFeatureLimits.backgroundJobsPerWindow(entitlement, now)
        if (jobsThisVisit.get() >= jobLimit) {
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
        for (song in candidates()) {
            if (!AutomaticStudioPolicy.canProcessDuration(song.duration)) continue
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
                    jobsThisVisit.incrementAndGet()
                    recordCooldown(key, now + 15 * 60_000L) // Avoid a crash/restart retry loop.
                    nextKind = if (kind == AutomaticStudioKind.LYRICS) AutomaticStudioKind.INSTRUMENTAL else AutomaticStudioKind.LYRICS
                    _status.value = if (kind == AutomaticStudioKind.LYRICS) "Finding synced lyrics for ${song.title}" else "Preparing an instrumental for ${song.title}"
                }
                return
            }
        }
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
        val failed = info.state == WorkInfo.State.FAILED || info.outputData.getString(TaisStudioWorker.OUTPUT_OUTCOME) == TaisStudioWorker.OUTCOME_FAILED
        val delay = when {
            deferred -> AutomaticStudioPolicy.DEFERRED_COOLDOWN_MS
            failed -> AutomaticStudioPolicy.FAILURE_COOLDOWN_MS
            kind == AutomaticStudioKind.LYRICS && !info.outputData.getBoolean(TaisStudioWorker.OUTPUT_WORD_SYNC_PRODUCED, false) -> AutomaticStudioPolicy.CATALOG_COOLDOWN_MS
            else -> 7 * 24 * 60 * 60_000L // Complete artifacts are also checked before any future scheduling.
        }
        recordCooldown(ledgerKey(kind, songId), System.currentTimeMillis() + delay)
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
    private companion object {
        const val UNIQUE_WORK_NAME = "automatic_studio_one_at_a_time"
        const val BACKGROUND_SWEEP_NAME = "automatic_studio_background_sweep"
    }
}
