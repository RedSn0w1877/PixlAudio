package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.cloudstudio.CloudBatchKind
import com.theveloper.pixelplay.data.cloudstudio.CloudBatchPreview
import com.theveloper.pixelplay.data.cloudstudio.CloudCheck
import com.theveloper.pixelplay.data.cloudstudio.CloudConnectionReport
import com.theveloper.pixelplay.data.cloudstudio.CloudSecrets
import com.theveloper.pixelplay.data.cloudstudio.CloudSecretsState
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.data.cloudstudio.CloudSong
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioSettings
import com.theveloper.pixelplay.data.cloudstudio.CloudStudioState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Where a confirm sheet was asked for, so only that screen shows it. */
enum class CloudBatchOrigin { QUEUE, PLAYLIST, STUDIO_CARD }

/** A batch waiting on the confirm sheet. */
data class CloudPendingBatch(val preview: CloudBatchPreview, val origin: CloudBatchOrigin)

/** What the Cloud Studio screens show besides the engine's own state (the iOS `CloudStudio` observable fields). */
data class CloudStudioUiState(
    val pendingBatch: CloudPendingBatch? = null,
    val connectionReport: CloudConnectionReport? = null,
    val selftestCheck: CloudCheck? = null,
    val isTesting: Boolean = false,
    val isPreviewing: Boolean = false,
    val isSending: Boolean = false,
    /** The keys as typed (loaded from secure storage once, then edited here); null until loaded. */
    val draft: CloudSecrets? = null,
    val keysMissing: Boolean = false,
    val secureStorageUnavailable: Boolean = false,
)

/**
 * Cloud Studio's UI state (Settings › Cloud processing, the queue, the confirm sheet), process-scoped like the other
 * state holders: the confirm sheet can be asked for from the queue, a playlist and the studio card, and a test or a
 * batch preview keeps running when its screen goes away. Every engine call runs on [Dispatchers.Default] (the engine
 * itself does its disk and network work on IO): nothing here blocks the main thread.
 *
 * The keys are edited as a draft held only in memory (never in a saved-state bundle, which Android writes to disk)
 * and written to encrypted storage 600 ms after the last keystroke, when the screen closes, and before a test.
 */
@Singleton
class CloudStudioStateHolder @Inject constructor(
    private val engine: CloudStudioEngine,
    private val settings: CloudStudioSettings,
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
        settings.update(change)
        val enabled = settings.snapshot().enabled
        // Switching on while the app is open starts the foreground watch right away; switching off stops every
        // transfer at once (a running pass stops at its next step).
        if (!wasEnabled && enabled) scope.launch { engine.setAppVisible(true) }
        if (wasEnabled && !enabled) engine.stopTransfers()
    }

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

    // ─── Batches ────────────────────────────────────────────────────────────────────────────

    /** The queue's Add choices. "Current song" without a song playing does nothing. */
    fun requestBatch(kind: CloudBatchKind, currentSong: CloudSong?, title: String) = preview(CloudBatchOrigin.QUEUE) {
        engine.preview(kind, currentSong, title)
    }

    /** A playlist's ⋯ or the studio card's button. */
    fun requestBatch(songs: List<CloudSong>, title: String, origin: CloudBatchOrigin) = preview(origin) {
        engine.preview(songs, title)
    }

    private fun preview(origin: CloudBatchOrigin, make: suspend () -> CloudBatchPreview?) {
        if (_ui.value.isPreviewing) return
        _ui.update { it.copy(isPreviewing = true) }
        scope.launch {
            try {
                val batch = make()
                _ui.update { it.copy(pendingBatch = batch?.let { preview -> CloudPendingBatch(preview, origin) }) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _ui.update { it.copy(pendingBatch = null) }
            } finally {
                _ui.update { it.copy(isPreviewing = false) }
            }
        }
    }

    fun dismissBatch() {
        if (!_ui.value.isSending) _ui.update { it.copy(pendingBatch = null) }
    }

    /** "Send N songs": the jobs are created, then the sheet closes (it follows [CloudStudioUiState.pendingBatch]). */
    fun send() {
        val batch = _ui.value.pendingBatch ?: return
        if (_ui.value.isSending) return
        _ui.update { it.copy(isSending = true) }
        scope.launch {
            try {
                engine.send(batch.preview)
            } finally {
                _ui.update { it.copy(isSending = false, pendingBatch = null) }
            }
        }
    }

    // ─── Jobs ───────────────────────────────────────────────────────────────────────────────

    fun cancel(jobKey: String) { scope.launch { engine.cancel(jobKey) } }
    fun retry(jobKey: String) { scope.launch { engine.retry(jobKey) } }
    fun remove(jobKey: String) { scope.launch { engine.remove(jobKey) } }
    fun clearFinished() { scope.launch { engine.clearFinished() } }

    fun committedThisMonthMicroUsd(): Long = engine.committedThisMonthMicroUsd()
}
