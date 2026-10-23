package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.cloudstudio.CloudBatchKind
import com.theveloper.pixelplay.data.cloudstudio.CloudBatchPreview
import com.theveloper.pixelplay.data.cloudstudio.CloudCheck
import com.theveloper.pixelplay.data.cloudstudio.CloudConnectionReport
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudSecrets
import com.theveloper.pixelplay.data.cloudstudio.CloudSecretsState
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.data.cloudstudio.CloudSingStart
import com.theveloper.pixelplay.data.cloudstudio.CloudSingUnavailable
import com.theveloper.pixelplay.data.cloudstudio.CloudSkipReason
import com.theveloper.pixelplay.data.cloudstudio.CloudSong
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioSettings
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioState
import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoPrepareScheduler
import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoPrepareStatus
import com.theveloper.pixelplay.data.cloudstudio.toCloudSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.screens.cloudstudio.CloudStudioCopy
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What the Cloud Studio screens show besides the engine's own state (the iOS `CloudStudio` observable fields). */
data class CloudStudioUiState(
    val connectionReport: CloudConnectionReport? = null,
    val selftestCheck: CloudCheck? = null,
    val isTesting: Boolean = false,
    /** A "Process in cloud" is being looked over or sent (its buttons wait; the queue shows "Looking through your library"). */
    val isPreviewing: Boolean = false,
    /** The keys as typed (loaded from secure storage once, then edited here); null until loaded. */
    val draft: CloudSecrets? = null,
    val keysMissing: Boolean = false,
    val secureStorageUnavailable: Boolean = false,
)

/**
 * Cloud Studio's UI state (Settings › Sing › Cloud processing, the queue, the "Process in cloud" buttons),
 * process-scoped like the other state holders: a song's card, a playlist and the queue can all start songs, and a test
 * or a start keeps running when its screen goes away. Every engine call runs on [Dispatchers.Default] (the engine
 * itself does its disk and network work on IO): nothing here blocks the main thread.
 *
 * The keys are edited as a draft held only in memory (never in a saved-state bundle, which Android writes to disk)
 * and written to encrypted storage 600 ms after the last keystroke, when the screen closes, and before a test.
 */
@Singleton
class CloudStudioStateHolder @Inject constructor(
    private val engine: CloudStudioEngine,
    private val settings: CloudStudioSettings,
    private val autoPrepare: CloudAutoPrepareStatus,
    private val autoPrepareScheduler: CloudAutoPrepareScheduler,
) {
    // A failure in one action (a Keystore error, a disk hiccup) never takes the app down; the screens just don't change.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> Timber.w("Cloud Studio action failed: %s", error.javaClass.simpleName) }
    )
    private val _ui = MutableStateFlow(CloudStudioUiState())
    val ui: StateFlow<CloudStudioUiState> = _ui.asStateFlow()
    val engineState: StateFlow<CloudStudioState> get() = engine.state
    val settingsState: StateFlow<CloudSettingsSnapshot> get() = settings.settings

    // One line per start (see [startNow]). A channel rather than a shared flow, so a toast shows once even when two
    // screens collect at the same time (the song sheet open over a playlist, say): each line goes to one collector.
    private val _messages = Channel<String>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var draftSave: Job? = null
    /** The draft was edited since it was last saved (closing a screen never rewrites keys nobody touched). */
    @Volatile private var draftDirty = false
    private val secretsLock = Mutex()

    // ─── Loading ────────────────────────────────────────────────────────────────────────────

    /** A screen opened: the stored jobs (even while off) and the keys. */
    fun onScreenShown() {
        scope.launch { engine.loadForDisplay() }
        scope.launch { loadSecrets() }
    }

    /** A song's card opened: only the stored jobs (so its Cloud line is right even while off); no keys are read. */
    fun loadJobs() {
        scope.launch { engine.loadForDisplay() }
    }

    private suspend fun loadSecrets() {
        val state = settings.secrets()
        _ui.update {
            it.copy(
                // A draft being edited wins over what is stored.
                draft = it.draft ?: state.secrets,
                keysMissing = state.keysMissing,
                secureStorageUnavailable = state.storageUnavailable,
            )
        }
    }

    // ─── Settings ───────────────────────────────────────────────────────────────────────────

    fun updateSettings(change: (CloudSettingsSnapshot) -> CloudSettingsSnapshot) {
        val wasEnabled = settings.snapshot().enabled
        val wasAutoPrepare = settings.snapshot().autoPrepareEnabled
        // "Use my own keys" is locked while songs are on their way: they were sent with the keys in use, and their
        // results come back only through those (the screen disables the switch; this keeps it true for any caller).
        val locked = hasJobsInFlight()
        settings.update { before -> change(before).let { if (locked) it.copy(useOwnKeys = before.useOwnKeys) else it } }
        val enabled = settings.snapshot().enabled
        val autoOn = settings.snapshot().autoPrepareEnabled
        // Switching on while the app is open starts the foreground watch right away; switching off stops every
        // transfer at once (a running pass stops at its next step).
        if (!wasEnabled && enabled) scope.launch { engine.setAppVisible(true) }
        if (wasEnabled && !enabled) engine.stopTransfers()
        // "Prepare songs ahead": the 6-hourly worker follows the two switches; turning it off stops the automatic jobs
        // still on the phone (those at RunPod are paid for and finish).
        if (wasEnabled != enabled || wasAutoPrepare != autoOn) scope.launch { autoPrepareScheduler.sync() }
        if (wasAutoPrepare && !autoOn) scope.launch { engine.cancelPendingAuto() }
    }

    // ─── Sing ───────────────────────────────────────────────────────────────────────────────

    /** Why a Sing on [song] can't use the cloud now (null: it can). From the settings only; no I/O. */
    fun singAvailability(song: Song): CloudSingUnavailable? = engine.singAvailability(song.duration)

    /**
     * A tap on Sing: starts (or joins) the song's cloud job, ahead of everything else. The answer says which, or why the
     * cloud can't take it and the caller removes the vocals on this phone instead.
     */
    suspend fun startSing(song: Song): CloudSingStart = withContext(Dispatchers.Default) {
        engine.startSing(song.toCloudSong())
    }

    /** Every cloud job of the song as it changes (a Sing watches its own: queued, uploading, at the GPU, importing). */
    fun cloudJobsFor(songId: String): Flow<List<CloudJobRecord>> = flow {
        engine.loadForDisplay() // the stored jobs first, or a Sing that was already on its way would look absent
        emitAll(engine.jobsFor(songId))
    }

    // ─── Prepare songs ahead ────────────────────────────────────────────────────────────────

    /** Under the switch: "3 songs today · $0.12 of $1.00 this month", or "Waiting for Wi-Fi". Empty while off. */
    val autoPrepareStatus: StateFlow<String> get() = autoPrepare.status

    /** Developer mode's "Why these songs": the last run's picks, each with its reasons. */
    val autoPrepareWhy: StateFlow<List<String>> get() = autoPrepare.why

    /** Jobs that aren't finished yet (waiting, uploading, at RunPod, or with results to collect). */
    fun hasJobsInFlight(): Boolean = engine.state.value.jobs.any { it.state.isPending }

    /** The keys as typed; saved 600 ms after the last change (iOS's draft rule). */
    fun updateDraft(draft: CloudSecrets) {
        _ui.update { it.copy(draft = draft) }
        draftDirty = true
        draftSave?.cancel()
        draftSave = scope.launch {
            delay(600)
            saveDraft()
        }
    }

    /** Saves the draft now (the screen closed, or a test is about to read the keys). */
    fun flushDraft() {
        draftSave?.cancel()
        scope.launch { saveDraft() }
    }

    private suspend fun saveDraft() {
        secretsLock.withLock {
            if (!draftDirty) return
            val draft = _ui.value.draft ?: return
            draftDirty = false
            if (settings.saveSecrets(draft)) {
                _ui.update { it.copy(keysMissing = false) }
            } else {
                _ui.update { it.copy(secureStorageUnavailable = true) }
            }
        }
    }

    fun forgetKeys() {
        draftSave?.cancel()
        draftDirty = false
        _ui.update { it.copy(draft = CloudSecrets.EMPTY, keysMissing = false, connectionReport = null, selftestCheck = null) }
        scope.launch { secretsLock.withLock { settings.forgetSecrets() } }
    }

    // ─── Test connection ────────────────────────────────────────────────────────────────────

    fun testConnection() = runTest {
        val report = engine.testConnection()
        _ui.update { it.copy(connectionReport = report) }
    }

    /** "Run selftest (~1¢)": keys typed a moment ago are saved first, as for Test connection. */
    fun runSelftest() = runTest {
        val report = engine.runSelftest()
        _ui.update { it.copy(selftestCheck = report.check) }
    }

    private fun runTest(block: suspend () -> Unit) {
        if (_ui.value.isTesting) return
        _ui.update { it.copy(isTesting = true) }
        draftSave?.cancel()
        scope.launch {
            try {
                saveDraft()
                block()
            } finally {
                _ui.update { it.copy(isTesting = false) }
            }
        }
    }

    // ─── Starting ───────────────────────────────────────────────────────────────────────────

    /** The queue's Add choices (developer mode). "Current song" without a song playing says so. */
    fun startNow(kind: CloudBatchKind, currentSong: CloudSong?, title: String) = start {
        engine.preview(kind, currentSong, title)
    }

    /** A song's "Process in cloud" or a playlist's "Process all in cloud": no confirm step, the outcome is a message. */
    fun startNow(songs: List<CloudSong>, title: String) = start {
        engine.preview(songs, title)
    }

    private fun start(make: suspend () -> CloudBatchPreview?) {
        if (_ui.value.isPreviewing) return
        if (!settings.snapshot().enabled) {
            _messages.trySend(CloudStudioCopy.OFF_MESSAGE)
            return
        }
        _ui.update { it.copy(isPreviewing = true) }
        scope.launch {
            try {
                _messages.trySend(sendAndReport(make()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w("Cloud start failed: %s", error.javaClass.simpleName)
                _messages.trySend(CloudStudioCopy.START_FAILED_MESSAGE)
            } finally {
                _ui.update { it.copy(isPreviewing = false) }
            }
        }
    }

    /**
     * Creates the jobs for [batch] and returns what to tell the person. The guards the confirm sheet used to apply live
     * here: nothing to process (with the first reason why), and the month's budget; the engine checks them again.
     */
    private suspend fun sendAndReport(batch: CloudBatchPreview?): String {
        if (batch == null) return CloudStudioCopy.PLAY_A_SONG_MESSAGE
        if (batch.isEmpty) return CloudStudioCopy.nothingToProcessMessage(batch.skipped.firstOrNull()?.first)
        if (!batch.estimate.fitsCap) return CloudStudioCopy.CAP_MESSAGE
        val started = engine.send(batch)
        // Nothing was added: every song got a job of its own in the meantime.
        if (started == 0) return CloudStudioCopy.nothingToProcessMessage(CloudSkipReason.PENDING_JOB)
        return CloudStudioCopy.startedMessage(started, waitsForWifi = !settings.snapshot().useCellular)
    }

    // ─── Jobs ───────────────────────────────────────────────────────────────────────────────

    fun cancel(jobKey: String) { scope.launch { engine.cancel(jobKey) } }
    fun retry(jobKey: String) { scope.launch { engine.retry(jobKey) } }
    fun remove(jobKey: String) { scope.launch { engine.remove(jobKey) } }
    fun clearFinished() { scope.launch { engine.clearFinished() } }

    fun committedThisMonthMicroUsd(): Long = engine.committedThisMonthMicroUsd()
}
