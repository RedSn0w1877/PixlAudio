package com.theveloper.pixelplay.presentation.viewmodel

import android.os.SystemClock
import androidx.media3.common.C
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.data.DailyMixManager
import com.theveloper.pixelplay.data.brain.FinishedPlay
import com.theveloper.pixelplay.data.brain.PlayContext
import com.theveloper.pixelplay.data.brain.PlaySink
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Tracks listening statistics for songs.
 * Extracted from PlayerViewModel to reduce its size and improve modularity.
 *
 * Responsibilities:
 * - Track active listening sessions
 * - Record play statistics when session ends
 * - Handle voluntary vs automatic plays
 * - Hand every play to the music brain ([PlaySink]) with its context and the reason it ended
 *   (docs/brain-spec.md, 4.2). That happens BEFORE the five-second floor below, which only guards the
 *   legacy stores: a two-second skip is exactly what the brain needs to see.
 */
@Singleton
class ListeningStatsTracker @Inject constructor(
    private val dailyMixManager: DailyMixManager,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val tasteRepository: com.theveloper.pixelplay.data.recommendation.MusicTasteRepository,
    private val playSink: PlaySink = PlaySink.NONE,
) {
    private var currentSession: ActiveSession? = null
    // The same play as the brain sees it. It outlives a legacy session that the UI closed
    // ([onCleared]) while the service kept playing, so one listen stays one event.
    private var brainPlay: BrainPlay? = null
    private var pendingVoluntarySongId: String? = null
    private var scope: CoroutineScope? = null
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _playbackHistory = MutableStateFlow<List<PlaybackStatsRepository.PlaybackHistoryEntry>>(emptyList())
    val playbackHistory: StateFlow<List<PlaybackStatsRepository.PlaybackHistoryEntry>> = _playbackHistory.asStateFlow()

    /**
     * Must be called to set the coroutine scope for async operations.
     */
    fun initialize(coroutineScope: CoroutineScope) {
        val activeScope = scope
        if (activeScope == null || activeScope.coroutineContext[Job]?.isActive != true) {
            scope = coroutineScope
        }
        coroutineScope.launch(Dispatchers.IO) {
            _playbackHistory.value = playbackStatsRepository.loadPlaybackHistory(
                limit = MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS
            )
        }
    }

    @Synchronized
    fun onVoluntarySelection(songId: String) {
        pendingVoluntarySongId = songId
    }

    fun onSongChanged(
        song: Song?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        onTrackChanged(
            songId = song?.id,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = song?.duration ?: 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun onTrackChanged(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        onTrackChanged(
            songId = songId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = 0L,
            isPlaying = isPlaying
        )
    }

    /**
     * A new track became current. [context] says what it is and how it started; [endReason] (an
     * `EndReason` code) is why the one before it ended. Both come from `MusicService`; a caller that
     * has neither (Cast) leaves them out and the brain records nothing for that play.
     */
    @Synchronized
    fun onTrackChanged(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        fallbackDurationMs: Long,
        isPlaying: Boolean,
        context: PlayContext? = null,
        endReason: Int = EndReason.UNKNOWN,
    ) {
        startSession(songId, positionMs, durationMs, fallbackDurationMs, isPlaying, context, endReason, sameSongContinues = false)
    }

    private fun startSession(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        fallbackDurationMs: Long,
        isPlaying: Boolean,
        context: PlayContext?,
        endReason: Int,
        sameSongContinues: Boolean,
    ) {
        val safeSongId = songId?.takeIf { it.isNotBlank() }
        val openPlay = brainPlay
        // The brain's play goes on when the same song simply continues (the UI closed the legacy
        // session, or a player swap and its transition callback both announce the same new track).
        val continues = safeSongId != null && openPlay != null && openPlay.songId == safeSongId &&
            (sameSongContinues || openPlay.isFreshStart(SystemClock.elapsedRealtime()))
        finalizeCurrentSession(
            trackChanged = songId != null && songId != currentSession?.songId,
            endReason = endReason,
            keepBrainPlay = continues,
        )
        if (safeSongId == null) {
            return
        }

        val nowRealtime = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()
        val normalizedDuration = normalizeDuration(durationMs, fallbackDurationMs)

        currentSession = ActiveSession(
            songId = safeSongId,
            totalDurationMs = normalizedDuration,
            startedAtEpochMs = nowEpoch,
            lastKnownPositionMs = positionMs.coerceAtLeast(0L),
            accumulatedListeningMs = 0L,
            lastRealtimeMs = nowRealtime,
            lastUpdateEpochMs = nowEpoch,
            isPlaying = isPlaying,
            isVoluntary = pendingVoluntarySongId == safeSongId
        )
        if (pendingVoluntarySongId == safeSongId) {
            pendingVoluntarySongId = null
        }
        if (continues) {
            // The transition callback describes the play better than the hand-over that announced it
            // first. A session that merely resumes after the UI closed keeps what it knew.
            if (context != null && !sameSongContinues) openPlay?.context = context
            openPlay?.update(nowRealtime, isPlaying, normalizedDuration)
        } else if (context != null) {
            openBrainPlay(safeSongId, context, normalizedDuration, isPlaying, nowRealtime, nowEpoch)
        }
    }

    private fun openBrainPlay(songId: String, context: PlayContext, durationMs: Long, isPlaying: Boolean, nowRealtime: Long, nowEpoch: Long) {
        brainPlay = BrainPlay(
            songId = songId,
            context = context,
            startedAtEpochMs = nowEpoch,
            startedRealtimeMs = nowRealtime,
            tzOffsetMin = java.util.TimeZone.getDefault().getOffset(nowEpoch) / 60_000,
            durationMs = durationMs,
            isPlaying = isPlaying,
            lastRealtimeMs = nowRealtime,
        ).also { if (isPlaying) announceOpen(it) }
    }

    @Synchronized
    fun onPlayStateChanged(isPlaying: Boolean, positionMs: Long) {
        if (currentSession == null && brainPlay == null) return
        val nowRealtime = SystemClock.elapsedRealtime()
        updateBrainPlay(nowRealtime, isPlaying, 0L)
        val session = currentSession ?: return
        accumulateRealtimeListening(session, nowRealtime)
        session.isPlaying = isPlaying
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        session.lastUpdateEpochMs = System.currentTimeMillis()
    }

    @Synchronized
    fun onProgress(positionMs: Long, isPlaying: Boolean) {
        if (currentSession == null && brainPlay == null) return
        val nowRealtime = SystemClock.elapsedRealtime()
        updateBrainPlay(nowRealtime, isPlaying, 0L)
        val session = currentSession ?: return
        accumulateRealtimeListening(session, nowRealtime)
        session.isPlaying = isPlaying
        session.lastRealtimeMs = nowRealtime
        session.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        session.lastUpdateEpochMs = System.currentTimeMillis()
    }

    /**
     * Sets an `EventFlags` bit on the play in flight when it is [songId] (liked, saved, added to a
     * playlist, "Not for me"). A flag for a song that is not playing is dropped: the like still
     * reaches the brain as a library fact.
     */
    @Synchronized
    fun markFlag(songId: String, flag: Int) {
        val play = brainPlay ?: return
        if (play.songId == songId) play.flags = play.flags or flag
    }

    /**
     * A seek inside the current track. Back to the start after 30 s of listening is a replay; back by
     * 10 s or more after 30 s is a seek-back (4.3).
     */
    @Synchronized
    fun onSeek(fromPositionMs: Long, toPositionMs: Long) {
        val play = brainPlay ?: return
        updateBrainPlay(SystemClock.elapsedRealtime(), play.isPlaying, 0L)
        if (play.playedMs < SEEK_SIGNAL_MIN_PLAYED_MS) return
        if (toPositionMs <= REPLAY_POSITION_MS && fromPositionMs >= SEEK_SIGNAL_MIN_PLAYED_MS) {
            if (play.flags and EventFlags.REPLAYED == 0) play.replayFlaggedAtRealtimeMs = SystemClock.elapsedRealtime()
            play.flags = play.flags or EventFlags.REPLAYED
        } else if (fromPositionMs - toPositionMs >= SEEK_BACK_MIN_MS) {
            play.flags = play.flags or EventFlags.SEEK_BACK
        }
    }

    fun ensureSession(
        song: Song?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        ensureSession(
            songId = song?.id,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = song?.duration ?: 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun ensureSession(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean
    ) {
        ensureSession(
            songId = songId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = 0L,
            isPlaying = isPlaying
        )
    }

    @Synchronized
    fun ensureSession(
        songId: String?,
        positionMs: Long,
        durationMs: Long,
        fallbackDurationMs: Long,
        isPlaying: Boolean,
        context: PlayContext? = null,
        endReason: Int = EndReason.UNKNOWN,
    ) {
        val safeSongId = songId?.takeIf { it.isNotBlank() }
        if (safeSongId == null) {
            finalizeCurrentSession(endReason = endReason)
            return
        }
        val existing = currentSession
        if (existing?.songId == safeSongId) {
            updateDuration(normalizeDuration(durationMs, fallbackDurationMs))
            val nowRealtime = SystemClock.elapsedRealtime()
            if (brainPlay == null && context != null) {
                // A session that started without context (Cast handing back to this phone): the brain
                // sees the play from here on.
                openBrainPlay(safeSongId, context, normalizeDuration(durationMs, fallbackDurationMs), isPlaying, nowRealtime, System.currentTimeMillis())
            } else {
                updateBrainPlay(nowRealtime, isPlaying, normalizeDuration(durationMs, fallbackDurationMs))
            }
            accumulateRealtimeListening(existing, nowRealtime)
            existing.isPlaying = isPlaying
            existing.lastRealtimeMs = nowRealtime
            existing.lastKnownPositionMs = positionMs.coerceAtLeast(0L)
            existing.lastUpdateEpochMs = System.currentTimeMillis()
            return
        }
        startSession(
            songId = safeSongId,
            positionMs = positionMs,
            durationMs = durationMs,
            fallbackDurationMs = fallbackDurationMs,
            isPlaying = isPlaying,
            context = context,
            endReason = endReason,
            sameSongContinues = true,
        )
    }

    @Synchronized
    fun updateDuration(durationMs: Long) {
        if (durationMs > 0 && durationMs != C.TIME_UNSET) {
            brainPlay?.durationMs = durationMs
        }
        val session = currentSession ?: return
        if (durationMs > 0 && durationMs != C.TIME_UNSET) {
            session.totalDurationMs = durationMs
        }
    }

    /**
     * Ends the current session. [endReason] is an `EndReason` code from the service: why the play
     * ended. With [keepBrainPlay] only the legacy session ends and the brain's play goes on.
     */
    @Synchronized
    fun finalizeCurrentSession(
        forceSynchronousPersistence: Boolean = false,
        trackChanged: Boolean = false,
        endReason: Int = EndReason.UNKNOWN,
        keepBrainPlay: Boolean = false,
    ) {
        // Nothing is open: nothing to end (and the clock is not read).
        if (currentSession == null && (keepBrainPlay || brainPlay == null)) return
        val nowRealtime = SystemClock.elapsedRealtime()
        // The brain first, and whatever the legacy session says: no five-second floor (4.2).
        val finishedContext = brainPlay?.context
        if (!keepBrainPlay) finalizeBrainPlay(nowRealtime, endReason)
        val session = currentSession ?: return
        val nowEpoch = System.currentTimeMillis()
        accumulateRealtimeListening(session, nowRealtime)
        val listened = session.accumulatedListeningMs.coerceAtLeast(0L)
        if (listened >= MIN_SESSION_LISTEN_MS) {
            val rawEndTimestamp = when {
                session.isPlaying -> nowEpoch
                session.lastUpdateEpochMs > 0L -> session.lastUpdateEpochMs
                else -> session.startedAtEpochMs + listened
            }
            val timestamp = rawEndTimestamp
                .coerceAtLeast(session.startedAtEpochMs.coerceAtLeast(0L))
                .coerceAtMost(nowEpoch)
            val songId = session.songId
            val historyEntry = PlaybackStatsRepository.PlaybackHistoryEntry(
                songId = songId,
                timestamp = timestamp
            )
            _playbackHistory.update { current ->
                (listOf(historyEntry) + current).take(MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS)
            }
            persistPlayback(
                songId = songId,
                listened = listened,
                timestamp = timestamp,
                forceSynchronous = forceSynchronousPersistence,
                context = finishedContext,
                totalDurationMs = session.totalDurationMs,
                isVoluntary = session.isVoluntary,
                trackChanged = trackChanged,
            )
        }
        currentSession = null
        if (pendingVoluntarySongId == session.songId) {
            pendingVoluntarySongId = null
        }
    }

    @Synchronized
    fun onPlaybackStopped(endReason: Int = EndReason.STOPPED) {
        finalizeCurrentSession(endReason = endReason)
    }

    /**
     * The UI is going away. Its legacy session is closed as before, but the service may well keep
     * playing the same track, so the brain's play stays open: the service ends it with a real reason.
     */
    @Synchronized
    fun onCleared() {
        finalizeCurrentSession(forceSynchronousPersistence = true, keepBrainPlay = true)
        scope = null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun persistPlayback(
        songId: String,
        listened: Long,
        timestamp: Long,
        forceSynchronous: Boolean,
        context: PlayContext?,
        totalDurationMs: Long,
        isVoluntary: Boolean,
        trackChanged: Boolean,
    ) {
        persistenceScope.launch {
            // A track from outside the library has no place in the legacy counters: they are capped at
            // 5,000 songs and feed rankers that cost money in the cloud (4.2). Its PlaybackEvent is
            // still written below, so it shows in Recently Played.
            val outsideLibrary = runCatching { playSink.isOutsideLibrary(songId, context) }.getOrDefault(false)
            if (!outsideLibrary) {
                runCatching {
                    tasteRepository.record(songId, listened, totalDurationMs, isVoluntary, trackChanged, timestamp)
                }.onFailure { Timber.w(it, "Failed to update local music learning") }
            }
            runCatching {
                persistPlaybackInternal(songId = songId, listened = listened, timestamp = timestamp, legacyCounters = !outsideLibrary)
            }.onFailure { throwable ->
                Timber.e(throwable, "Failed to persist listening session for song=%s", songId)
            }
        }
    }

    private suspend fun persistPlaybackInternal(songId: String, listened: Long, timestamp: Long, legacyCounters: Boolean) {
        if (legacyCounters) {
            dailyMixManager.recordPlay(
                songId = songId,
                songDurationMs = listened,
                timestamp = timestamp
            )
        }
        playbackStatsRepository.recordPlayback(
            songId = songId,
            durationMs = listened,
            timestamp = timestamp
        )
    }

    // ── The brain's play ─────────────────────────────────────────────────────

    private fun updateBrainPlay(nowRealtime: Long, isPlaying: Boolean, durationMs: Long) {
        val play = brainPlay ?: return
        val wasPlaying = play.isPlaying
        val everPlayed = play.everPlayed
        play.update(nowRealtime, isPlaying, durationMs)
        // Written down when it first plays and at every pause, so a process death still leaves a
        // "killed" event with what was heard by then.
        if ((isPlaying && !everPlayed) || (wasPlaying && !isPlaying)) announceOpen(play)
    }

    private fun announceOpen(play: BrainPlay) {
        runCatching { playSink.onPlayOpen(play.snapshot(EndReason.KILLED)) }
    }

    private fun finalizeBrainPlay(nowRealtime: Long, endReason: Int) {
        val play = brainPlay ?: return
        brainPlay = null
        play.update(nowRealtime, play.isPlaying, 0L)
        // Previous pressed twice goes back a track: the first press seeks to the start (which looks
        // like a replay), the second leaves. A "replay" that is left by Previous within moments was
        // the way out, not a wish to hear the track again.
        if (endReason == EndReason.PREV && play.replayFlaggedAtRealtimeMs != 0L &&
            nowRealtime - play.replayFlaggedAtRealtimeMs < REPLAY_CONFIRM_MS
        ) {
            play.flags = play.flags and EventFlags.REPLAYED.inv()
        }
        // An item that never played and was not ended by the listener or an error is not a play
        // attempt (a restored, paused queue; a queue that was cleared).
        val ended = endReason == EndReason.NEXT || endReason == EndReason.PREV ||
            endReason == EndReason.PICKED_OTHER || endReason == EndReason.QUEUE_EDIT || endReason == EndReason.ERROR
        if (!play.everPlayed && !ended) return
        if (play.playedMs <= 0L) play.flags = play.flags or EventFlags.NEVER_AUDIBLE
        runCatching {
            playSink.onPlayFinished(play.snapshot(endReason))
            if (play.everPlayed) playSink.onPlayOpen(null)
        }.onFailure { Timber.w(it, "Failed to hand a play to the music brain") }
    }

    private fun accumulateRealtimeListening(session: ActiveSession, nowRealtime: Long) {
        if (!session.isPlaying) return
        val delta = (nowRealtime - session.lastRealtimeMs).coerceAtLeast(0L)
        if (delta > 0L) {
            session.accumulatedListeningMs += delta
        }
    }

    private fun normalizeDuration(durationMs: Long, fallbackDurationMs: Long): Long {
        return when {
            durationMs > 0 && durationMs != C.TIME_UNSET -> durationMs
            fallbackDurationMs > 0 && fallbackDurationMs != C.TIME_UNSET -> fallbackDurationMs
            else -> 0L
        }
    }

    companion object {
        private val MIN_SESSION_LISTEN_MS = TimeUnit.SECONDS.toMillis(5)
        private const val MAX_INTERNAL_PLAYBACK_HISTORY_ITEMS = 500
        private const val SEEK_SIGNAL_MIN_PLAYED_MS = 30_000L
        private const val SEEK_BACK_MIN_MS = 10_000L
        private const val REPLAY_POSITION_MS = 3_000L
        private const val REPLAY_CONFIRM_MS = 5_000L
    }
}

/**
 * One play as the music brain will record it: the item's context and the time actually spent
 * playing. Kept beside [ActiveSession], whose shape and five-second floor belong to the legacy stores.
 */
private class BrainPlay(
    val songId: String,
    var context: PlayContext,
    val startedAtEpochMs: Long,
    val startedRealtimeMs: Long,
    val tzOffsetMin: Int,
    var durationMs: Long,
    var isPlaying: Boolean,
    var lastRealtimeMs: Long,
) {
    var playedMs: Long = 0L
    var flags: Int = 0
    var everPlayed: Boolean = isPlaying
    /** When a seek to the start first flagged this play as replayed; 0 when it never did. */
    var replayFlaggedAtRealtimeMs: Long = 0L

    fun update(nowRealtime: Long, playing: Boolean, duration: Long) {
        if (isPlaying) playedMs += (nowRealtime - lastRealtimeMs).coerceAtLeast(0L)
        isPlaying = playing
        if (playing) everPlayed = true
        lastRealtimeMs = nowRealtime
        if (duration > 0L) durationMs = duration
    }

    /** True in the first moments of a play that has not been heard yet: a second announcement of the same track is the same play. */
    fun isFreshStart(nowRealtime: Long): Boolean =
        playedMs < FRESH_PLAYED_MS && nowRealtime - startedRealtimeMs < FRESH_WINDOW_MS

    fun snapshot(endReason: Int) = FinishedPlay(
        songId = songId,
        context = context,
        startedAtMs = startedAtEpochMs,
        playedMs = playedMs,
        durationMs = durationMs,
        endReason = endReason,
        flags = flags,
        tzOffsetMin = tzOffsetMin,
    )

    private companion object {
        const val FRESH_PLAYED_MS = 500L
        const val FRESH_WINDOW_MS = 2_000L
    }
}

/**
 * Represents an active listening session for a song.
 */
data class ActiveSession(
    val songId: String,
    var totalDurationMs: Long,
    val startedAtEpochMs: Long,
    var lastKnownPositionMs: Long,
    var accumulatedListeningMs: Long,
    var lastRealtimeMs: Long,
    var lastUpdateEpochMs: Long,
    var isPlaying: Boolean,
    val isVoluntary: Boolean
)
