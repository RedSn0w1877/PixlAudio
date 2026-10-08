package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Immutable
import androidx.media3.common.Player
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.lyrics.sync.LyricsExport
import com.theveloper.pixelplay.data.lyrics.sync.LyricsSyncDraftStore
import com.theveloper.pixelplay.data.lyrics.sync.LyricsTapSync
import com.theveloper.pixelplay.data.lyrics.sync.SyncDraft
import com.theveloper.pixelplay.data.lyrics.sync.SyncDraftOrigin
import com.theveloper.pixelplay.data.lyrics.sync.SyncStep
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.service.player.InstrumentalCrossfadeController
import com.theveloper.pixelplay.data.service.player.TransitionController
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyrics
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyricsBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Where the "sync it yourself" editor should start. */
enum class SyncEntry {
    /** Decide from the song's lyrics and any draft (the lyrics sheet's entry points). */
    AUTO,
    /** Straight to "Paste the lyrics", pre-filled with the current words ("Change the words"). */
    WORDS,
    /** Straight to the preview of the saved timing ("Fix timing"). */
    FIX_TIMING,
}

/** The editor's screens (spec §2). */
@Immutable
sealed interface SyncPhase {
    data object Closed : SyncPhase
    data object Loading : SyncPhase
    data class ResumePrompt(val tapped: Int, val total: Int) : SyncPhase
    data object NeedWords : SyncPhase
    data object Intro : SyncPhase
    /** Ready, Tapping and Paused: one screen, the button label follows [SyncUiState]. */
    data object Tapping : SyncPhase
    data object Preview : SyncPhase
    /** Re-tapping one line from the preview; later lines keep their timing. */
    data class FixLine(val lineIndex: Int) : SyncPhase
    /** The song is already user-synced: Fix timing · Start over · Remove my timing. */
    data object Manage : SyncPhase
    data class Error(val message: String) : SyncPhase
}

enum class SyncDialog { NONE, LEAVE, SONG_CHANGED, ENDED_EARLY, SAVE_FAILED }

enum class SyncNoticeKind { REMOVED_WORDS, WAIT_TIP, PAST_NEXT_LINE }

/** A transient message shown as a pill over the controls. [id] changes for every new notice. */
@Immutable
data class SyncNotice(val id: Long, val kind: SyncNoticeKind, val count: Int = 0, val canUndo: Boolean = false)

/** One "Find lyrics online" hit. */
@Immutable
data class SyncSearchHit(val label: String, val text: String)

/** The finished result drawn by the shared karaoke renderer in Preview. */
@Immutable
data class SyncPreview(
    val prepared: PreparedLyrics,
    /** Draft line index for each prepared line index. */
    val draftLineForPrepared: PersistentList<Int>,
    val hasRoughLines: Boolean,
)

/**
 * Everything the editor screens draw. It changes on taps, phase changes and settings, never per
 * frame (the position is read straight from [LyricsSyncEditorStateHolder.positionMs] in draw).
 */
@Immutable
data class SyncUiState(
    val songId: String = "",
    val title: String = "",
    val artist: String = "",
    val artUri: String? = null,
    val draft: SyncDraft? = null,
    val origin: SyncDraftOrigin = SyncDraftOrigin.NONE,
    val wordsSeed: String = "",
    val searching: Boolean = false,
    val searchHits: PersistentList<SyncSearchHit>? = null,
    val speed: Float = 1f,
    val isPlaying: Boolean = false,
    /** The user pressed "Start the song" (or resumed a draft) in this session. */
    val started: Boolean = false,
    val sessionTaps: Int = 0,
    val fixLine: Int? = null,
    val dialog: SyncDialog = SyncDialog.NONE,
    val endedEarlyWords: Int = 0,
    val notice: SyncNotice? = null,
    val isSaving: Boolean = false,
    val preview: SyncPreview? = null,
    val lineSelectMode: Boolean = false,
    val haptics: Boolean = true,
    val offsetMs: Int = LyricsTapSync.DEFAULT_OFFSET_SPEAKER_MS,
)

/**
 * "Sync it yourself": the tap-to-sync lyrics editor's state and its player session (spec §2–§5).
 *
 * Process-scoped like the other player state holders, so a configuration change never ends a
 * session. The pure maths lives in [LyricsTapSync]; this class feeds it exact player positions
 * and turns its results into seeks, drafts and the saved [LyricsDoc].
 *
 * A session owns the player while open: it pauses, turns offload off and pauses at the end of
 * the item ([DualPlayerEngine.beginExactTimingSession]), suspends crossfades, and changes speed.
 * [close] restores all of it, whatever the reason for closing.
 *
 * Main thread only (the player and the controller live there).
 */
@Singleton
class LyricsSyncEditorStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackStateHolder,
    private val engine: DualPlayerEngine,
    private val transitionController: TransitionController,
    private val castStateHolder: CastStateHolder,
    private val lyricsRepository: LyricsRepository,
    private val lyricsStateHolder: LyricsStateHolder,
    private val draftStore: LyricsSyncDraftStore,
    private val preferences: UserPreferencesRepository,
    /** The lyrics page's on-device translations, which the editor's draft must never pick up. */
    private val lyricsTranslation: LyricsTranslationStateHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _phase = MutableStateFlow<SyncPhase>(SyncPhase.Closed)
    val phase: StateFlow<SyncPhase> = _phase.asStateFlow()

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    /** Asks the host to show the full player (the editor lives there). */
    private val _expandPlayerRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expandPlayerRequests: SharedFlow<Unit> = _expandPlayerRequests.asSharedFlow()

    val chipDismissedSongIds: kotlinx.coroutines.flow.Flow<Set<String>> =
        preferences.lyricsSyncChipDismissedSongIdsFlow

    /** Hides the "Make the words light up" chip for this song for good. */
    fun dismissChip(songId: String) {
        scope.launch { preferences.dismissLyricsSyncChip(songId) }
    }

    private class Session(val song: Song, val previousSpeed: Float, val bluetooth: Boolean) {
        var dirty = false
        var saved = false
        var lastTapUptimeMs = Long.MIN_VALUE / 2
        var shownWaitTip = false
        var introSeenCount = 0
        /** Draft the resume prompt offers, until the user picks. */
        var storedDraft: SyncDraft? = null
        /** The draft built from the song's lyrics (for "Start over" from the resume prompt). */
        var seedDraft: SyncDraft? = null
        var lyrics: Lyrics? = null
        /** One level of undo for rewind / jump: the draft before and where playback was. */
        var undoSnapshot: Pair<SyncDraft, Long>? = null
    }

    private var session: Session? = null
    private val jobs = ArrayList<Job>()
    private var draftSaveJob: Job? = null
    private var undoSeekJob: Job? = null
    private var finishJob: Job? = null
    private var fixLineReturnJob: Job? = null
    private var previewJob: Job? = null
    private var previewSeekJob: Job? = null
    private var searchJob: Job? = null
    private var pendingOpenJob: Job? = null
    private var noticeCounter = 0L
    private var prunedDrafts = false
    private var observedPlayer: Player? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) onSongEnded()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onSongEnded()
        }
    }

    private val draft: SyncDraft? get() = _uiState.value.draft
    private val offsetMs: Int get() = _uiState.value.offsetMs
    private val speed: Float get() = _uiState.value.speed
    private val scopeLine: Int? get() = _uiState.value.fixLine

    /** The exact media position for per-frame readers (progress bar, preview renderer). */
    fun positionMs(): Long = playback.exactPositionMs()

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Opening and closing
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the editor for [song] from anywhere (e.g. Edit song → "Fix timing"): shows the full
     * player and waits for [song] to be the one playing (the caller starts it) before opening.
     */
    fun requestOpen(song: Song, entry: SyncEntry) {
        pendingOpenJob?.cancel()
        _expandPlayerRequests.tryEmit(Unit)
        pendingOpenJob = scope.launch {
            val current = withTimeoutOrNull(OPEN_WAIT_MS) {
                playback.stablePlayerState.map { it.currentSong?.id }.first { it == song.id }
            }
            if (current != null) open(entry)
        }
    }

    fun isCurrentSong(songId: String): Boolean = playback.stablePlayerState.value.currentSong?.id == songId

    /** Opens the editor for the song that is playing now. */
    fun open(entry: SyncEntry = SyncEntry.AUTO) {
        if (_phase.value != SyncPhase.Closed) return
        val song = playback.stablePlayerState.value.currentSong ?: return
        _uiState.value = SyncUiState(
            songId = song.id,
            title = song.title,
            artist = song.displayArtist,
            artUri = song.albumArtUriString,
        )
        if (castStateHolder.castSession.value != null) {
            _phase.value = SyncPhase.Error(context.getString(R.string.lyrics_sync_casting))
            return
        }
        _phase.value = SyncPhase.Loading
        startSession(song)
        jobs += scope.launch { load(song, entry) }
    }

    private fun startSession(song: Song) {
        val previousSpeed = playback.currentSpeed()
        val s = Session(song, previousSpeed, isBluetoothRoute())
        session = s
        pause()
        engine.beginExactTimingSession()
        transitionController.suspend(OWNER)
        InstrumentalCrossfadeController.suspend(OWNER)
        observedPlayer = engine.masterPlayer.also { it.addListener(playerListener) }

        jobs += scope.launch {
            playback.stablePlayerState
                .map { it.playWhenReady }
                .distinctUntilChanged()
                .collect { playing -> _uiState.update { it.copy(isPlaying = playing) } }
        }
        jobs += scope.launch {
            playback.stablePlayerState
                .map { it.currentSong?.id }
                .distinctUntilChanged()
                .collectLatest { id ->
                    when {
                        id == null -> {
                            // The player unloaded (notification close, playback stopped). A brief
                            // null can happen during a queue rebuild, so only close if it stays.
                            delay(UNLOADED_CLOSE_DELAY_MS)
                            if (session != null) close()
                        }
                        id != song.id -> onSongChangedOutside()
                    }
                }
        }
        jobs += scope.launch {
            castStateHolder.castSession
                .map { it != null }
                .distinctUntilChanged()
                .collect { casting ->
                    if (casting && session != null) {
                        flushDraft()
                        _phase.value = SyncPhase.Error(context.getString(R.string.lyrics_sync_casting))
                    }
                }
        }
        if (!prunedDrafts) {
            prunedDrafts = true
            scope.launch { runCatching { draftStore.pruneOlderThan() } }
        }
    }

    private suspend fun load(song: Song, entry: SyncEntry) {
        val s = session ?: return
        val bluetoothOffset = preferences.lyricsTapOffsetBluetoothMsFlow.first()
        val speakerOffset = preferences.lyricsTapOffsetSpeakerMsFlow.first()
        val defaultSpeed = preferences.lyricsSyncDefaultSpeedFlow.first().takeIf { it in SPEEDS } ?: 1f
        val haptics = preferences.lyricsSyncHapticsFlow.first()
        s.introSeenCount = preferences.lyricsSyncIntroSeenCountFlow.first()
        _uiState.update {
            it.copy(
                offsetMs = if (s.bluetooth) bluetoothOffset else speakerOffset,
                haptics = haptics,
                speed = defaultSpeed,
            )
        }
        if (defaultSpeed != playback.currentSpeed()) playback.setPlaybackSpeed(defaultSpeed)

        val state = playback.stablePlayerState.value
        // The draft carries each line's translation into the saved lyrics, so the on-device ones
        // (memory only, from the lyrics page's Translate) are taken off first.
        val lyrics = state.lyrics?.takeIf { state.currentSong?.id == song.id }
            ?.let { lyricsTranslation.withoutOnDeviceTranslations(song.id, it) }
            ?: try {
                lyricsRepository.getStoredLyrics(song)?.first
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Lyrics sync: could not read stored lyrics")
                null
            }
        s.lyrics = lyrics
        if (session !== s) return

        if (entry == SyncEntry.WORDS) {
            _uiState.update { it.copy(wordsSeed = plainTextOf(lyrics)) }
            _phase.value = SyncPhase.NeedWords
            return
        }

        val seed = withContext(Dispatchers.Default) { LyricsTapSync.buildDraft(song, lyrics, null) }
        if (session !== s) return
        s.seedDraft = seed.draft
        _uiState.update { it.copy(origin = seed.origin) }

        if (entry == SyncEntry.FIX_TIMING && seed.draft != null) {
            setDraft(seed.draft, dirty = false)
            goToPreview()
            return
        }

        val stored = draftStore.load(song.id)
        if (session !== s) return
        if (stored != null && stored.tappedCount > 0) {
            s.storedDraft = stored
            _phase.value = SyncPhase.ResumePrompt(stored.tappedCount, stored.tappableCount)
            return
        }
        startFromSeed(seed.draft, seed.origin)
    }

    private fun startFromSeed(seedDraft: SyncDraft?, origin: SyncDraftOrigin) {
        val s = session ?: return
        when {
            seedDraft == null -> {
                _uiState.update { it.copy(wordsSeed = "") }
                _phase.value = SyncPhase.NeedWords
            }
            origin == SyncDraftOrigin.USER_SYNCED -> {
                setDraft(seedDraft, dirty = false)
                _phase.value = SyncPhase.Manage
            }
            origin == SyncDraftOrigin.WORD_SYNCED -> {
                setDraft(seedDraft, dirty = false)
                _phase.value = SyncPhase.Intro
            }
            s.introSeenCount < INTRO_SHOW_COUNT -> {
                setDraft(seedDraft, dirty = false)
                _phase.value = SyncPhase.Intro
            }
            else -> beginTapping(seedDraft)
        }
    }

    /** Back / ✕. Asks first when there are taps that would otherwise be left as a draft. */
    fun requestClose() {
        val phase = _phase.value
        val s = session
        val hasWork = s != null && s.dirty && (draft?.tappedCount ?: 0) > 0
        if (hasWork && (phase == SyncPhase.Tapping || phase is SyncPhase.FixLine || phase == SyncPhase.Preview)) {
            pause()
            _uiState.update { it.copy(dialog = SyncDialog.LEAVE) }
        } else {
            close()
        }
    }

    /** System back: steps out of line-pick mode first. */
    fun onBack() {
        if (_uiState.value.lineSelectMode) {
            _uiState.update { it.copy(lineSelectMode = false) }
            return
        }
        if (_uiState.value.dialog != SyncDialog.NONE) {
            dismissDialog()
            return
        }
        requestClose()
    }

    /** Ends the session for any reason and puts the player back as it was. Idempotent. */
    fun close() {
        pendingOpenJob?.cancel()
        val s = session
        session = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        listOf(undoSeekJob, finishJob, fixLineReturnJob, previewJob, previewSeekJob, searchJob).forEach { it?.cancel() }
        draftSaveJob?.cancel()
        observedPlayer?.removeListener(playerListener)
        observedPlayer = null
        if (s != null) {
            val toFlush = draft?.takeIf { s.dirty && !s.saved && it.tappedCount > 0 }
            if (toFlush != null) {
                scope.launch(NonCancellable) { draftStore.save(toFlush) }
            }
            // Always, not only when it differs: a speed change may still be in flight through
            // the (asynchronous) MediaController, and a skipped restore would leave it applied.
            playback.setPlaybackSpeed(s.previousSpeed)
            engine.endExactTimingSession()
            transitionController.resume(OWNER)
            InstrumentalCrossfadeController.resume(OWNER)
        }
        _phase.value = SyncPhase.Closed
        _uiState.value = SyncUiState()
    }

    /** The host went to the background: pause and keep the taps. */
    fun onHostStopped() {
        if (session == null) return
        pause()
        flushDraft()
    }

    private fun onSongChangedOutside() {
        if (session == null || _phase.value == SyncPhase.Closed) return
        pause()
        flushDraft()
        _uiState.update { it.copy(dialog = SyncDialog.SONG_CHANGED) }
    }

    fun dismissDialog() {
        when (_uiState.value.dialog) {
            SyncDialog.SONG_CHANGED -> close()
            else -> _uiState.update { it.copy(dialog = SyncDialog.NONE) }
        }
    }

    fun confirmLeave() {
        close()
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Resume prompt, manage, words, intro
    // ─────────────────────────────────────────────────────────────────────────────────────────

    fun resumeKeepGoing() {
        val s = session ?: return
        val stored = s.storedDraft ?: return
        s.storedDraft = null
        setDraft(stored, dirty = true)
        if (stored.isFinished) goToPreview() else beginTapping(stored, resume = true)
    }

    fun resumeStartOver() {
        val s = session ?: return
        s.storedDraft = null
        scope.launch { draftStore.delete(s.song.id) }
        val seed = s.seedDraft
        val origin = _uiState.value.origin
        if (seed != null && (origin == SyncDraftOrigin.USER_SYNCED || origin == SyncDraftOrigin.WORD_SYNCED)) {
            beginTapping(LyricsTapSync.clearAll(seed))
        } else {
            startFromSeed(seed, origin)
        }
    }

    fun manageFixTiming() {
        goToPreview()
    }

    fun manageStartOver() {
        val current = draft ?: return
        session?.let { s -> scope.launch { draftStore.delete(s.song.id) } }
        beginTapping(LyricsTapSync.clearAll(current))
    }

    /** "Remove my timing": deletes the user's sync and goes back to the lyrics found online. */
    fun removeMyTiming() {
        val s = session ?: return
        scope.launch {
            try {
                lyricsRepository.resetLyrics(s.song)
                draftStore.delete(s.song.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Lyrics sync: could not remove the user's timing")
            }
            s.saved = true
            val song = s.song
            close()
            lyricsStateHolder.loadLyricsForSong(song, preferences.lyricsSourcePreferenceFlow.first())
        }
    }

    fun findLyricsOnline() {
        val s = session ?: return
        searchJob?.cancel()
        _uiState.update { it.copy(searching = true, searchHits = null) }
        searchJob = scope.launch {
            val hits = try {
                lyricsRepository.searchRemote(s.song).getOrNull()?.second.orEmpty().mapNotNull { result ->
                    val text = plainTextOf(result.lyrics).ifBlank { return@mapNotNull null }
                    val label = listOf(result.record.name, result.record.artistName)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    SyncSearchHit(label, text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Lyrics sync: online search failed")
                emptyList()
            }
            _uiState.update { it.copy(searching = false, searchHits = hits.toPersistentList()) }
        }
    }

    fun clearSearchHits() {
        searchJob?.cancel()
        _uiState.update { it.copy(searching = false, searchHits = null) }
    }

    /** "Next" on the words screen: the text becomes plain lyrics to tap. */
    fun submitWords(text: String) {
        val s = session ?: return
        val song = s.song
        scope.launch {
            val seed = withContext(Dispatchers.Default) { LyricsTapSync.buildDraft(song, null, text) }
            val built = seed.draft ?: return@launch
            if (session !== s) return@launch
            _uiState.update { it.copy(origin = SyncDraftOrigin.PLAIN, wordsSeed = text) }
            if (s.introSeenCount < INTRO_SHOW_COUNT) {
                setDraft(built, dirty = false)
                _phase.value = SyncPhase.Intro
            } else {
                beginTapping(built)
            }
        }
    }

    /** Intro "Start" ([dontShowAgain] = "Got it, don't show this again"). */
    fun startFromIntro(dontShowAgain: Boolean) {
        val s = session ?: return
        val current = draft ?: return
        s.introSeenCount = if (dontShowAgain) INTRO_SHOW_COUNT else s.introSeenCount + 1
        scope.launch {
            if (dontShowAgain) preferences.setLyricsSyncIntroSeenCount(INTRO_SHOW_COUNT)
            else preferences.incrementLyricsSyncIntroSeenCount()
        }
        val alreadyTimed = _uiState.value.origin == SyncDraftOrigin.WORD_SYNCED ||
            _uiState.value.origin == SyncDraftOrigin.USER_SYNCED
        beginTapping(if (alreadyTimed) LyricsTapSync.clearAll(current) else current)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Tapping
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private fun beginTapping(start: SyncDraft, resume: Boolean = false) {
        pause()
        setDraft(start, dirty = resume || _uiState.value.draft !== start)
        _uiState.update { it.copy(fixLine = null, lineSelectMode = false, started = resume, preview = null) }
        _phase.value = SyncPhase.Tapping
        val seek = if (resume) {
            lastStampedStart(start)?.let { it - scaled(LyricsTapSync.UNDO_PREROLL_MS) }
        } else {
            val firstAnchor = start.lines.firstOrNull { !it.locked }?.anchorMs ?: 0L
            firstAnchor - LyricsTapSync.ANCHOR_PREROLL_MS
        }
        seekTo((seek ?: 0L).coerceAtLeast(0L))
    }

    /**
     * Pointer down on the tap pad, stamped with the event's own uptime. Returns the index of the
     * word that was stamped (so the release can mark a held end), or -1.
     */
    fun onTapDown(eventUptimeMs: Long): Int {
        val phase = _phase.value
        if (phase != SyncPhase.Tapping && phase !is SyncPhase.FixLine) return -1
        val s = session ?: return -1
        val ui = _uiState.value
        val current = ui.draft ?: return -1
        if (ui.dialog != SyncDialog.NONE) return -1
        if (phase == SyncPhase.Tapping && current.isFinished) {
            goToPreview()
            return -1
        }
        if (!isPlayingNow()) {
            play()
            _uiState.update { it.copy(started = true) }
            return -1
        }
        if (eventUptimeMs - s.lastTapUptimeMs < LyricsTapSync.BOUNCE_MS) return -1
        s.lastTapUptimeMs = eventUptimeMs

        val tapSpeed = playback.currentSpeed()
        val raw = LyricsTapSync.rawTapPositionMs(
            positionMs = playback.exactPositionMs(),
            nowUptimeMs = SystemClock.uptimeMillis(),
            eventUptimeMs = eventUptimeMs,
            speed = tapSpeed,
        )
        val index = LyricsTapSync.nextTappable(current, current.cursor)
        val step = LyricsTapSync.tap(current, raw, tapSpeed, offsetMs, scopeLine = scopeLine)
        if (step.draft === current) return -1
        setDraft(step.draft, dirty = true)
        _uiState.update { it.copy(sessionTaps = it.sessionTaps + 1, started = true) }
        if (step.pastNextLine) showNotice(SyncNoticeKind.PAST_NEXT_LINE)
        if (step.tapBeforeAnchor && !s.shownWaitTip) {
            s.shownWaitTip = true
            showNotice(SyncNoticeKind.WAIT_TIP)
        }

        val next = step.draft
        val fix = scopeLine
        if (fix != null) {
            val nextIndex = LyricsTapSync.nextTappable(next, next.cursor)
            val leftLine = nextIndex >= next.tokens.size || next.tokens[nextIndex].line != fix
            if (leftLine) {
                fixLineReturnJob?.cancel()
                fixLineReturnJob = scope.launch {
                    delay(FIX_LINE_RETURN_MS)
                    goToPreview(focusLine = fix)
                }
            }
        } else if (next.isFinished) {
            scheduleFinishPause(next)
        }
        return index
    }

    /** Pointer up: a hold of at least 350 ms marks the end of the word it stamped. */
    fun onTapUp(tokenIndex: Int, downUptimeMs: Long, upUptimeMs: Long) {
        if (tokenIndex < 0 || upUptimeMs - downUptimeMs < LyricsTapSync.HOLD_THRESHOLD_MS) return
        val current = draft ?: return
        val releaseSpeed = playback.currentSpeed()
        val raw = LyricsTapSync.rawTapPositionMs(
            positionMs = playback.exactPositionMs(),
            nowUptimeMs = SystemClock.uptimeMillis(),
            eventUptimeMs = upUptimeMs,
            speed = releaseSpeed,
        )
        val released = LyricsTapSync.release(current, tokenIndex, raw, releaseSpeed, offsetMs)
        if (released !== current) setDraft(released, dirty = true)
    }

    /** Undo: pops one tap now; the rewind seek runs 250 ms after the last of a burst of presses. */
    fun undo() {
        val current = draft ?: return
        val step = LyricsTapSync.undo(current, speed, offsetMs, scopeLine = scopeLine)
        if (step.draft === current) return
        finishJob?.cancel()
        fixLineReturnJob?.cancel()
        setDraft(step.draft, dirty = true)
        val target = step.seekToMs ?: return
        undoSeekJob?.cancel()
        undoSeekJob = scope.launch {
            delay(LyricsTapSync.UNDO_SEEK_DELAY_MS)
            seekTo(target)
        }
    }

    /** Back 5 s: forgets taps after the new position. */
    fun rewind() {
        val current = draft ?: return
        val position = playback.exactPositionMs()
        val step = LyricsTapSync.rewind(current, position, offsetMs, scopeLine = scopeLine)
        applyDestructive(current, position, step)
    }

    /** Tap on a shown line at or before the current one: re-tap from that line. */
    fun jumpToLine(lineIndex: Int) {
        if (_phase.value != SyncPhase.Tapping) return
        val current = draft ?: return
        val position = playback.exactPositionMs()
        val step = LyricsTapSync.jumpToLine(current, lineIndex, speed, offsetMs)
        if (step.draft === current && step.seekToMs == null) return
        // A jump the user picked by touching a line always offers Undo if it erased anything.
        applyDestructive(current, position, step, noticeMin = 0)
    }

    private fun applyDestructive(
        before: SyncDraft,
        position: Long,
        step: SyncStep,
        noticeMin: Int = REMOVED_WORDS_NOTICE_MIN,
    ) {
        finishJob?.cancel()
        fixLineReturnJob?.cancel()
        undoSeekJob?.cancel()
        if (step.draft !== before) setDraft(step.draft, dirty = true)
        step.seekToMs?.let(::seekTo)
        if (step.clearedCount > noticeMin) {
            session?.undoSnapshot = before to position
            showNotice(SyncNoticeKind.REMOVED_WORDS, count = step.clearedCount, canUndo = true)
        }
    }

    /** Snackbar "Undo" after a rewind or jump: restores the taps and the position. */
    fun undoRemoval() {
        val s = session ?: return
        val (snapshot, position) = s.undoSnapshot ?: return
        s.undoSnapshot = null
        setDraft(snapshot, dirty = true)
        seekTo(position)
        dismissNotice()
    }

    fun skipLine() {
        val current = draft ?: return
        val step = LyricsTapSync.skipLine(current, offsetMs)
        if (step.draft === current) return
        setDraft(step.draft, dirty = true)
        if (step.draft.isFinished) scheduleFinishPause(step.draft)
    }

    fun togglePlay() {
        if (isPlayingNow()) {
            pause()
        } else {
            play()
            _uiState.update { it.copy(started = true) }
        }
    }

    fun setSpeed(newSpeed: Float) {
        if (newSpeed !in SPEEDS) return
        _uiState.update { it.copy(speed = newSpeed) }
        playback.setPlaybackSpeed(newSpeed)
    }

    fun setHaptics(enabled: Boolean) {
        _uiState.update { it.copy(haptics = enabled) }
        scope.launch { preferences.setLyricsSyncHaptics(enabled) }
    }

    /** The song ended with words left: "Keep going" seeks to 2 s before the last tap. */
    fun endedKeepGoing() {
        val current = draft ?: return
        _uiState.update { it.copy(dialog = SyncDialog.NONE) }
        seekTo(((lastStampedStart(current) ?: 0L) - LyricsTapSync.UNDO_PREROLL_MS).coerceAtLeast(0L))
    }

    /** "Time the rest roughly": spread the remaining words and show the result. */
    fun endedTimeRest() {
        val current = draft ?: return
        _uiState.update { it.copy(dialog = SyncDialog.NONE) }
        val step = LyricsTapSync.fillRest(current, offsetMs)
        setDraft(step.draft, dirty = true)
        goToPreview()
    }

    private fun onSongEnded() {
        val current = draft ?: return
        if (_phase.value != SyncPhase.Tapping || current.isFinished) return
        _uiState.update { it.copy(dialog = SyncDialog.ENDED_EARLY, endedEarlyWords = current.remainingCount) }
    }

    /** After the last word: play on to the end of the last line + 1.5 s, then pause. */
    private fun scheduleFinishPause(finished: SyncDraft) {
        finishJob?.cancel()
        finishJob = scope.launch {
            val end = withContext(Dispatchers.Default) {
                LyricsTapSync.resolveTiming(finished, offsetMs)?.endsMs?.maxOrNull()
            } ?: return@launch
            while (true) {
                delay(FINISH_POLL_MS)
                if (!isPlayingNow()) return@launch
                if (playback.exactPositionMs() >= end + FINISH_TAIL_MS) {
                    pause()
                    return@launch
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Preview, fix a line, save, share
    // ─────────────────────────────────────────────────────────────────────────────────────────

    fun goToPreview(focusLine: Int? = null) {
        val before = draft ?: return
        if (before.tappedCount == 0 && before.tappableCount > 0) return
        // After "Fix a line" the cursor sits on the next (already timed) line: point it at the
        // first word still untimed, so "Keep tapping" never re-stamps a timed word.
        val firstUntimed = before.tokens.indices.firstOrNull { i ->
            before.tokens[i].rawStartMs == null && !before.lines[before.tokens[i].line].locked
        } ?: before.tokens.size
        val current = if (before.cursor == firstUntimed) before else before.copy(cursor = firstUntimed)
        if (current !== before) setDraft(current, dirty = false)
        finishJob?.cancel()
        fixLineReturnJob?.cancel()
        undoSeekJob?.cancel()
        _uiState.update { it.copy(fixLine = null, lineSelectMode = false) }
        _phase.value = SyncPhase.Preview
        rebuildPreview()
        // The start position needs the whole timing resolved (O(words), allocating): worked out
        // off the main thread, so a tap that finishes the song shows its press and the move to
        // Preview first. Seek and play follow a dispatch later, unless Preview was left already.
        val offset = offsetMs
        previewSeekJob?.cancel()
        previewSeekJob = scope.launch {
            val target = withContext(Dispatchers.Default) { previewStartMs(current, offset, focusLine) }
            if (_phase.value != SyncPhase.Preview) return@launch
            seekTo(target.coerceAtLeast(0L))
            play()
        }
    }

    private fun previewStartMs(current: SyncDraft, offset: Int, focusLine: Int?): Long {
        val timing = LyricsTapSync.resolveTiming(current, offset) ?: return 0L
        return if (focusLine != null && focusLine in current.lines.indices) {
            timing.startsMs[current.lines[focusLine].firstToken] - PREVIEW_LINE_PREROLL_MS
        } else {
            (timing.startsMs.minOrNull() ?: 0L) - PREVIEW_START_PREROLL_MS
        }
    }

    private fun rebuildPreview() {
        val current = draft ?: return
        val offset = offsetMs
        previewJob?.cancel()
        previewJob = scope.launch {
            val preview = withContext(Dispatchers.Default) { buildPreview(current, offset) }
            if (draft === current || draft?.tokens === current.tokens) {
                _uiState.update { it.copy(preview = preview) }
            }
        }
    }

    /** Earlier / later: moves every word by [steps] × 20 ms, no seek. */
    fun nudge(steps: Int) {
        val current = draft ?: return
        val next = LyricsTapSync.setNudge(current, current.nudgeMs + steps * LyricsTapSync.NUDGE_STEP_MS)
        if (next.nudgeMs == current.nudgeMs) return
        setDraft(next, dirty = true)
        rebuildPreview()
    }

    fun setLineSelectMode(enabled: Boolean) {
        _uiState.update { it.copy(lineSelectMode = enabled) }
    }

    /** A tap on a preview line: jump to it, or re-tap it when picking the line that's off. */
    fun onPreviewLineTap(preparedIndex: Int, lineStartMs: Long) {
        val ui = _uiState.value
        if (!ui.lineSelectMode) {
            seekTo((lineStartMs - PREVIEW_LINE_PREROLL_MS).coerceAtLeast(0L))
            return
        }
        val draftLine = ui.preview?.draftLineForPrepared?.getOrNull(preparedIndex) ?: return
        fixLine(draftLine)
    }

    private fun fixLine(lineIndex: Int) {
        val current = draft ?: return
        val step = LyricsTapSync.fixLine(current, lineIndex, speed, offsetMs)
        if (step.draft === current && step.seekToMs == null) return
        setDraft(step.draft, dirty = true)
        _uiState.update { it.copy(fixLine = lineIndex, lineSelectMode = false, preview = null, started = true) }
        _phase.value = SyncPhase.FixLine(lineIndex)
        step.seekToMs?.let(::seekTo)
        play()
    }

    /** "Keep tapping": back to the tap screen at the cursor. */
    fun keepTapping() {
        val current = draft ?: return
        beginTapping(current, resume = true)
    }

    fun save() {
        val s = session ?: return
        val current = draft ?: return
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        pause()
        scope.launch {
            val offset = offsetMs
            // Built and serialized off the main thread: the JSON is tens of KB and would land on
            // the frames of the Save press and the overlay's exit.
            val encoded = withContext(Dispatchers.Default) {
                LyricsTapSync.buildResult(current, offset).getOrNull()?.doc?.let { doc ->
                    try {
                        LyricsDocCodec.encode(doc)
                    } catch (e: Exception) {
                        Timber.w(e, "Lyrics sync: save failed")
                        null
                    }
                }
            }
            val saved = encoded != null && try {
                lyricsRepository.updateLyrics(s.song, encoded, LyricsTapSync.SOURCE_USER)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Lyrics sync: save failed")
                false
            }
            if (session !== s) return@launch
            if (!saved) {
                flushDraft()
                _uiState.update { it.copy(isSaving = false, dialog = SyncDialog.SAVE_FAILED) }
                return@launch
            }
            if (current.nudgeMs != 0) {
                val learned = LyricsTapSync.learnedOffsetMs(offset, current.nudgeMs)
                if (s.bluetooth) preferences.setLyricsTapOffsetBluetoothMs(learned)
                else preferences.setLyricsTapOffsetSpeakerMs(learned)
            }
            draftStore.delete(s.song.id)
            s.saved = true
            val song = s.song
            close()
            lyricsStateHolder.loadLyricsForSong(song, preferences.lyricsSourcePreferenceFlow.first())
            Toast.makeText(context, R.string.lyrics_sync_saved, Toast.LENGTH_SHORT).show()
        }
    }

    /** The finished file for "Share lyrics file", or null while nothing is tapped. */
    suspend fun exportText(ttml: Boolean): String? {
        val current = draft ?: return null
        val offset = offsetMs
        return withContext(Dispatchers.Default) {
            LyricsTapSync.toLyricsDoc(current, offset).getOrNull()?.let { doc ->
                if (ttml) LyricsExport.toTtml(doc) else LyricsExport.toEnhancedLrc(doc)
            }
        }
    }

    /** A file name for the exported lyrics: "Artist - Title". */
    fun exportFileName(ttml: Boolean): String {
        val ui = _uiState.value
        val base = listOf(ui.artist, ui.title).filter { it.isNotBlank() }.joinToString(" - ")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "lyrics" }
        return base + if (ttml) ".ttml" else ".lrc"
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Notices
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private fun showNotice(kind: SyncNoticeKind, count: Int = 0, canUndo: Boolean = false) {
        _uiState.update { it.copy(notice = SyncNotice(++noticeCounter, kind, count, canUndo)) }
    }

    fun dismissNotice(id: Long? = null) {
        _uiState.update { state ->
            if (id == null || state.notice?.id == id) state.copy(notice = null) else state
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private fun setDraft(next: SyncDraft, dirty: Boolean) {
        _uiState.update { it.copy(draft = next) }
        val s = session ?: return
        if (dirty) {
            s.dirty = true
            scheduleDraftSave()
        }
    }

    private fun scheduleDraftSave() {
        draftSaveJob?.cancel()
        draftSaveJob = scope.launch {
            delay(DRAFT_SAVE_DELAY_MS)
            val current = draft ?: return@launch
            if (current.tappedCount > 0 && session?.saved == false) draftStore.save(current)
        }
    }

    private fun flushDraft() {
        val s = session ?: return
        val current = draft ?: return
        if (!s.dirty || s.saved || current.tappedCount == 0) return
        draftSaveJob?.cancel()
        scope.launch(NonCancellable) { draftStore.save(current) }
    }

    private fun lastStampedStart(d: SyncDraft): Long? {
        for (i in minOf(d.cursor, d.tokens.size) - 1 downTo 0) {
            LyricsTapSync.builtStartMs(d, i, offsetMs)?.let { return it }
        }
        return null
    }

    private fun scaled(ms: Long): Long = (ms * speed).toLong()

    // Explicit play / pause (never a toggle): the controller masks its state at once, so two
    // quick calls can't cancel each other out the way two toggles could.
    private fun isPlayingNow(): Boolean = playback.localPlayWhenReady()

    private fun play() {
        playback.playLocal()
    }

    private fun pause() {
        playback.pauseLocal()
    }

    private fun seekTo(positionMs: Long) {
        playback.seekTo(positionMs.coerceAtLeast(0L))
    }

    private fun isBluetoothRoute(): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val OWNER = "lyrics_sync"
        val SPEEDS = listOf(1f, 0.75f, 0.5f)
        const val INTRO_SHOW_COUNT = 2
        private const val OPEN_WAIT_MS = 8_000L
        private const val UNLOADED_CLOSE_DELAY_MS = 600L
        private const val DRAFT_SAVE_DELAY_MS = 1_000L
        private const val FIX_LINE_RETURN_MS = 1_000L
        private const val FINISH_POLL_MS = 100L
        private const val FINISH_TAIL_MS = 1_500L
        private const val PREVIEW_START_PREROLL_MS = 2_000L
        private const val PREVIEW_LINE_PREROLL_MS = 1_500L
        private const val REMOVED_WORDS_NOTICE_MIN = 3
        /** Preview-only mark at the end of a roughly timed line. */
        private const val ROUGH_MARK = " ≈"

        /** The words of any lyrics as plain text, one line per line (romanization dropped). */
        fun plainTextOf(lyrics: Lyrics?): String {
            if (lyrics == null) return ""
            lyrics.document?.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
                return lines.joinToString("\n") { it.text.trim() }
            }
            lyrics.synced?.takeIf { it.isNotEmpty() }?.let { lines ->
                return lines.map { it.line.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            }
            return lyrics.plain.orEmpty().map { it.substringBefore('\n').trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }

        /** The preview document: the real result, with "≈" appended to roughly timed lines. */
        internal fun buildPreview(draft: SyncDraft, offsetMs: Int): SyncPreview? {
            val result = LyricsTapSync.buildResult(draft, offsetMs).getOrNull() ?: return null
            val timing = LyricsTapSync.resolveTiming(draft, offsetMs) ?: return null
            // Same ordering as buildResult: lines with words, stably sorted by first start.
            val order = draft.lines.indices
                .filter { draft.lines[it].tokenCount > 0 }
                .sortedBy { timing.startsMs[draft.lines[it].firstToken] }
            val doc = if (result.roughLineIndices.isEmpty()) result.doc else markRough(result.doc, result.roughLineIndices)
            val prepared = PreparedLyricsBuilder.build(doc) ?: return null
            // Prepared lines are the document's lines in start order (none are blank here).
            val map = if (prepared.lines.size == order.size) order.toPersistentList() else {
                prepared.lines.map { line ->
                    order.firstOrNull { timing.startsMs[draft.lines[it].firstToken] == line.startMs } ?: 0
                }.toPersistentList()
            }
            return SyncPreview(prepared, map, result.roughLineIndices.isNotEmpty())
        }

        private fun markRough(doc: LyricsDoc, rough: Set<Int>): LyricsDoc = doc.copy(
            lines = doc.lines.mapIndexed { index, line ->
                if (index !in rough || line.syllables.isEmpty()) line else {
                    val last = line.syllables.last()
                    line.copy(
                        text = line.text + ROUGH_MARK,
                        syllables = line.syllables.dropLast(1) + last.copy(text = last.text + ROUGH_MARK),
                    )
                }
            }
        )
    }
}
