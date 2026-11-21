package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.brain.HostVoice
import com.theveloper.pixelplay.data.brain.HostNoteFacts
import com.theveloper.pixelplay.data.brain.HostNoteMemory
import com.theveloper.pixelplay.data.brain.SetNoteHistory
import com.theveloper.pixelplay.data.brain.StationStore
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.service.player.HostSpeaker
import com.theveloper.pixelplay.data.service.player.HostVoiceLog
import com.theveloper.pixelplay.data.service.player.HostVoiceLogEntry
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.presentation.components.SetNoteLines
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * The spoken host of Endless Listening (milestone E2): says the short note of a song aloud, at a calm moment, with the music brought down.
 *
 * Everything about *when* is [HostVoice] (pure, shared with iOS through `hostvoice.jsonl`); this class only reads the world and does what it says:
 *  - **Armed** only while "Spoken host notes" is on, Host notes is on and Endless Listening is the queue's station. Until then it watches
 *    nothing, and the system voice does not exist ([HostSpeaker] is lazy).
 *  - On each new song it makes the very note the player shows ([SetNoteLines.forQueue], so a line is the note on the screen), turns it into a
 *    line ([HostVoice.line]), and asks [HostVoice.decide] again and again (every 300 ms at most, a few times) until the answer is to speak
 *    or to give up. Each end of a note is written to the developer log.
 *  - To speak it brings the music down through [DualPlayerEngine.hostDuck], waits for the ramp, speaks, and brings it back. A skip, a pause, a
 *    transition, a focus loss, a call, a route change, or another writer of the volume stops the line at once
 *    ([HostVoice.cancelReason]) and the music comes back quickly.
 *
 * Process-wide like the other state holders; the queue comes from the player view model through [attach]. When the app's player side is gone
 * (the process kept alive only by the service) nothing new is seen, so nothing is said: silence is the safe failure.
 */
@Singleton
class HostVoiceStateHolder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
    private val stations: StationStore,
    private val hostNoteFacts: HostNoteFacts,
    private val hostNoteMemory: HostNoteMemory,
    private val setNoteHistory: SetNoteHistory,
    private val engine: DualPlayerEngine,
    private val playback: PlaybackStateHolder,
    private val speaker: HostSpeaker,
    @AppScope private val appScope: CoroutineScope,
) {
    // A failure nobody expected (a player call after release, an audio service error) is logged and the voice stays silent: it never reaches the app.
    private val main = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e -> Timber.w(e, "host voice: unexpected failure") },
    )

    /** "Spoken host notes": off by default. */
    val spokenNotes: StateFlow<Boolean> = preferences.endlessSpokenNotesFlow
        .stateIn(appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val hostNotes: StateFlow<Boolean> = preferences.endlessHostNotesFlow
        .stateIn(appScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    private val _log = MutableStateFlow<List<HostVoiceLogEntry>>(emptyList())

    /** The last 20 decisions, newest first, for the developer row "Host voice log". */
    val log: StateFlow<List<HostVoiceLogEntry>> = _log.asStateFlow()

    fun setSpokenNotes(enabled: Boolean) {
        appScope.launch {
            try {
                preferences.setEndlessSpokenNotes(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "host voice: saving the switch failed")
            }
        }
    }

    // ── what the player view model gives ─────────────────────────────────────────────────────────────

    private var queueOf: () -> List<Song> = { emptyList() }
    private var whyOf: () -> Map<String, QueueWhy> = { emptyMap() }
    private var attached = false

    /**
     * Starts watching, once: from now on, while armed, each new song may be spoken for. Cheap to call and a no-op the second time; it reads
     * two preferences and the station and does nothing else until the host is armed.
     */
    fun attach(queue: () -> List<Song>, why: () -> Map<String, QueueWhy>) {
        queueOf = queue
        whyOf = why
        if (attached) return
        attached = true
        engine.hostDuck.onEnded = { reason -> onDuckEnded(reason) }
        main.launch {
            combine(spokenNotes, hostNotes, stations.current.map { it?.isEndless == true }) { spoken, notes, endless -> spoken && notes && endless }
                .distinctUntilChanged()
                .collectLatest { armed ->
                    if (!armed) {
                        if (!testing) speaker.shutdown()
                        return@collectLatest
                    }
                    watchRoute()
                    try {
                        coroutineScope {
                            launch {
                                playback.stablePlayerState.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                                    if (!playing) cancelNow("pause")
                                }
                            }
                            playback.stablePlayerState
                                .map { Track(it.currentSong?.id, it.currentMediaItemIndex) }
                                .distinctUntilChanged()
                                .collectLatest { track ->
                                    try {
                                        handleTrack(track)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Timber.w(e, "host voice: a note failed")
                                        record(spoke = false, cancelled = false, reason = "error", text = "")
                                    }
                                }
                        }
                    } finally {
                        unwatchRoute()
                        if (!testing) speaker.shutdown()
                    }
                }
        }
    }

    private data class Track(val id: String?, val index: Int)

    // ── one song ─────────────────────────────────────────────────────────────────────────────────────

    private var trackCounter = 0
    private var lastSpokenCounter = NEVER_COUNTER
    private var lastSpokenText = ""
    private var secondLastText = ""

    private suspend fun handleTrack(track: Track) {
        val songId = track.id ?: return
        trackCounter++
        val myCounter = trackCounter
        val note = noteFor(songId, track.index) ?: return
        // The note is the app's own (its banned words were looked for in what the app wrote, not in a name): only its length is checked again.
        val line = HostVoice.line(note, noteChecked = true)
        if (line.isEmpty()) {
            record(spoke = false, cancelled = false, reason = "no_line", text = note)
            return
        }
        // The first line of a session: the voice starts now, so it is ready when the moment comes.
        speaker.ensure()
        val startedAt = SystemClock.elapsedRealtime() - positionOf(songId)
        while (true) {
            val d = HostVoice.decide(inputs(songId, line, myCounter, startedAt))
            when (d.action) {
                HostVoice.ACT_WAIT -> delay(d.waitMs)
                HostVoice.ACT_SKIP -> {
                    record(spoke = false, cancelled = false, reason = d.reason, text = line)
                    return
                }
                else -> {
                    speakNow(songId, line, myCounter)
                    return
                }
            }
        }
    }

    /** The note the player shows for [songId] (the one that starts at that song), or null when there is none. */
    private suspend fun noteFor(songId: String, index: Int): String? = withContext(Dispatchers.Default) {
        val queue = queueOf()
        if (queue.isEmpty()) return@withContext null
        val at = if (index in queue.indices && queue[index].id == songId) index else queue.indexOfFirst { it.id == songId }
        if (at < 0) return@withContext null
        val window = SetNoteLines.window(queue, at)
        val heard = setNoteHistory.heardSongIds(window)
        val why = whyOf()
        val keys = HashMap<String, String>(window.size * 2)
        for (song in window) why[song.id]?.let { keys[song.id] = it.key }
        val facts = SetNoteLines.HostFacts(
            islandOf = hostNoteFacts.islandNames(window),
            whyKeyOf = keys,
            genreFact = hostNoteFacts::genreFact,
            memory = hostNoteMemory,
        )
        SetNoteLines.forQueue(queue, at, heard, facts).current
    }

    private fun positionOf(songId: String): Long {
        val player = engine.masterPlayerIfAlive ?: return 0L
        return if (player.currentMediaItem?.mediaId == songId) player.currentPosition.coerceAtLeast(0L) else 0L
    }

    private fun inputs(songId: String, line: String, counter: Int, startedAt: Long): HostVoice.Inputs {
        val player = engine.masterPlayerIfAlive
        val isTarget = player?.currentMediaItem?.mediaId == songId
        val position = if (isTarget && player != null) player.currentPosition.coerceAtLeast(0L) else 0L
        val duration = if (isTarget && player != null) player.duration else C.TIME_UNSET
        val now = SystemClock.elapsedRealtime()
        return HostVoice.Inputs(
            enabled = spokenNotes.value,
            endless = stations.current.value?.isEndless == true,
            notesOn = hostNotes.value,
            line = line,
            sameAsLast = line == lastSpokenText || line == secondLastText,
            tracksSinceLast = if (lastSpokenCounter == NEVER_COUNTER) HostVoice.NEVER else counter - lastSpokenCounter - 1,
            sinceStartMs = now - startedAt,
            currentIsTarget = isTarget,
            transitionRunning = engine.isTransitionRunning(),
            playing = player?.isPlaying == true,
            inCall = inCall(),
            msSinceRoute = if (lastRouteChangeAtMs == 0L) LONG_AGO_MS else now - lastRouteChangeAtMs,
            positionMs = position,
            remainingMs = if (duration == C.TIME_UNSET || duration <= 0L) -1L else (duration - position).coerceAtLeast(0L),
            voice = if (speaker.unavailable) HostVoice.VOICE_NONE else speaker.voice,
            canDuck = engine.canDuckForHost(),
        )
    }

    // ── speaking ─────────────────────────────────────────────────────────────────────────────────────

    @Volatile private var speaking = false
    private var duckEnded: String? = null
    private var routeChangedAtSpeech = false
    private var pausedByEvent = false

    private suspend fun speakNow(songId: String, line: String, counter: Int) {
        duckEnded = null
        routeChangedAtSpeech = false
        pausedByEvent = false
        if (!engine.hostDuck.begin()) {
            record(spoke = false, cancelled = false, reason = "cant_duck", text = line)
            return
        }
        speaking = true
        var stoppedFor = ""
        var said = false
        var began = false
        try {
            delay(HostVoice.RAMP_DOWN_MS)
            stoppedFor = stopReason(songId, 0L)
            if (stoppedFor.isEmpty()) {
                val startedAt = SystemClock.elapsedRealtime()
                began = true
                lastSpokenCounter = counter
                secondLastText = lastSpokenText
                lastSpokenText = line
                val deferred = speaker.speak(line)
                while (deferred.isActive) {
                    stoppedFor = stopReason(songId, SystemClock.elapsedRealtime() - startedAt)
                    if (stoppedFor.isNotEmpty()) {
                        speaker.stop()
                        break
                    }
                    delay(POLL_MS)
                }
                if (stoppedFor.isEmpty()) {
                    when (deferred.await()) {
                        HostSpeaker.Outcome.DONE -> said = true
                        HostSpeaker.Outcome.STOPPED -> stoppedFor = duckEnded ?: "stopped"
                        HostSpeaker.Outcome.ERROR -> stoppedFor = "voice_error"
                    }
                }
            }
        } catch (e: CancellationException) {
            // A newer song, the switch, or the end of the station: the line stops at once.
            stoppedFor = "skip"
            throw e
        } finally {
            speaker.stop()
            engine.hostDuck.end(quick = !said)
            speaking = false
            if (said) record(spoke = true, cancelled = false, reason = "speak", text = line)
            else if (began) record(spoke = false, cancelled = true, reason = stoppedFor.ifEmpty { "stopped" }, text = line)
            else record(spoke = false, cancelled = false, reason = stoppedFor.ifEmpty { "stopped" }, text = line)
        }
    }

    private fun stopReason(songId: String, sinceSpeechStartMs: Long): String {
        val player = engine.masterPlayerIfAlive
        val ended = duckEnded
        return HostVoice.cancelReason(
            HostVoice.Running(
                enabled = spokenNotes.value,
                endless = stations.current.value?.isEndless == true,
                notesOn = hostNotes.value,
                songChanged = player?.currentMediaItem?.mediaId != songId,
                playing = player?.isPlaying == true && !pausedByEvent,
                transitionRunning = engine.isTransitionRunning() || ended == "transition",
                focusLost = ended == "focus",
                inCall = inCall(),
                routeChanged = routeChangedAtSpeech,
                volumeTaken = ended != null && ended != "focus" && ended != "transition",
                sinceSpeechStartMs = sinceSpeechStartMs,
            )
        )
    }

    /** The duck ended on its own (a transition, a focus loss, someone else's volume write): the line stops this instant. */
    private fun onDuckEnded(reason: String) {
        duckEnded = reason
        if (speaking) speaker.stop()
    }

    private fun cancelNow(reason: String) {
        if (!speaking) return
        if (reason == "pause") pausedByEvent = true
        speaker.stop()
    }

    // ── the world: calls and the audio route ─────────────────────────────────────────────────────────

    private val audioManager: AudioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private fun inCall(): Boolean = when (audioManager.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE, AudioManager.MODE_CALL_SCREENING -> true
        else -> false
    }

    private var lastRouteChangeAtMs = 0L
    private var routeIgnoreUntilMs = 0L
    private var routeCallback: AudioDeviceCallback? = null

    private fun watchRoute() {
        if (routeCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = routeChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = routeChanged()
        }
        routeCallback = callback
        // Registering reports every device already there as "added": that is not a change.
        routeIgnoreUntilMs = SystemClock.elapsedRealtime() + ROUTE_REGISTER_GRACE_MS
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun unwatchRoute() {
        routeCallback?.let { runCatching { audioManager.unregisterAudioDeviceCallback(it) } }
        routeCallback = null
    }

    private fun routeChanged() {
        val now = SystemClock.elapsedRealtime()
        if (now < routeIgnoreUntilMs) return
        lastRouteChangeAtMs = now
        if (speaking) {
            routeChangedAtSpeech = true
            speaker.stop()
        }
    }

    // ── "Test the voice" and the log ─────────────────────────────────────────────────────────────────

    /** How "Test the voice" ended. */
    enum class TestResult { SPOKE, NO_VOICE, BUSY, IN_CALL, ERROR }

    @Volatile private var testing = false

    /**
     * Says a short sample so the person hears the voice: the one place the system voice is started because the person asked. Brings the music
     * down like a note does when it can, and does not touch it otherwise. Never over a call; refused while a note is being said.
     */
    suspend fun testVoice(): TestResult = withContext(Dispatchers.Main) {
        if (speaking || testing) return@withContext TestResult.BUSY
        if (inCall()) return@withContext TestResult.IN_CALL
        testing = true
        val line = HostVoice.line(context.getString(R.string.endless_voice_test_line))
        var ducked = false
        var result = TestResult.ERROR
        try {
            speaker.ensure()
            val ready = withTimeoutOrNull(VOICE_START_TIMEOUT_MS) {
                while (speaker.voice == HostVoice.VOICE_PENDING) delay(100L)
                speaker.voice == HostVoice.VOICE_READY
            } == true
            if (!ready || line.isEmpty()) {
                result = TestResult.NO_VOICE
                record(spoke = false, cancelled = false, reason = "no_voice", text = "test")
            } else {
                duckEnded = null
                if (engine.canDuckForHost() && engine.hostDuck.begin()) {
                    ducked = true
                    delay(HostVoice.RAMP_DOWN_MS)
                }
                val outcome = withTimeoutOrNull(HostVoice.MAX_SPEECH_MS) { speaker.speak(line).await() }
                result = if (outcome == HostSpeaker.Outcome.DONE) TestResult.SPOKE else TestResult.ERROR
                record(spoke = result == TestResult.SPOKE, cancelled = false, reason = "test", text = line)
            }
        } finally {
            speaker.stop()
            if (ducked) engine.hostDuck.end(quick = result != TestResult.SPOKE)
            testing = false
            // The voice is given back unless the host is armed and may use it.
            if (!(spokenNotes.value && hostNotes.value && stations.current.value?.isEndless == true)) speaker.shutdown()
        }
        result
    }

    private fun record(spoke: Boolean, cancelled: Boolean, reason: String, text: String) {
        val entry = HostVoiceLogEntry(System.currentTimeMillis(), spoke, cancelled, reason, text.take(LOG_TEXT_CHARS))
        _log.value = HostVoiceLog.add(_log.value, entry)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val POLL_MS = 100L
        const val NEVER_COUNTER = Int.MIN_VALUE
        const val LONG_AGO_MS = 3_600_000L
        const val ROUTE_REGISTER_GRACE_MS = 1_000L
        const val VOICE_START_TIMEOUT_MS = 6_000L
        const val LOG_TEXT_CHARS = 80
    }
}
