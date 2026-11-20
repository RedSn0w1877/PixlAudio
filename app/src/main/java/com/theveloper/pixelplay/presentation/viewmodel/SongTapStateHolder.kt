package com.theveloper.pixelplay.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.songtap.QrTransport
import com.theveloper.pixelplay.data.songtap.SongTapCard
import com.theveloper.pixelplay.data.songtap.SongTapReceiver
import com.theveloper.pixelplay.data.songtap.SongTapSaveResult
import com.theveloper.pixelplay.data.songtap.SongTapSender
import com.theveloper.pixelplay.data.songtap.SongTapSettings
import com.theveloper.pixelplay.data.songtap.SongTapTransport
import com.theveloper.pixelplay.data.songtap.TransportEvent
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.songtap.FailReason
import com.theveloper.pixelplay.songtap.HapticCue
import com.theveloper.pixelplay.songtap.PayloadDecode
import com.theveloper.pixelplay.songtap.Phase
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SaveOutcome
import com.theveloper.pixelplay.songtap.SessionEffect
import com.theveloper.pixelplay.songtap.SessionEvent
import com.theveloper.pixelplay.songtap.SessionState
import com.theveloper.pixelplay.songtap.SongTapPayload
import com.theveloper.pixelplay.songtap.SongTapPayloads
import com.theveloper.pixelplay.songtap.SongTapSession
import com.theveloper.pixelplay.songtap.SongTapSigning
import com.theveloper.pixelplay.songtap.TimeoutTag
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The song that is playing, as the Send side shows it. */
@Immutable
data class SongTapNowPlaying(val title: String, val artist: String, val artUrl: String?, val canSend: Boolean)

/** Why the last attempt did not go through, as something the screen can word. */
enum class SongTapNotice { INVALID_CODE, NOTHING_TO_SEND, NOT_FOUND, SAVE_FAILED, TIMEOUT, TOO_SOON, LIMIT, DUPLICATE, MUTED, BOTH_SENDING, PEER_LOST, INVALID_OFFER }

@Immutable
data class SongTapUiState(
    /** "Allow Song Tap" is on. */
    val allowed: Boolean = false,
    val session: SessionState = SessionState.initial(),
    val nowPlaying: SongTapNowPlaying? = null,
    /** The text of the QR code while the sender shows one. */
    val code: String? = null,
    /** The song on the table: the offer waiting for Add / Not now, or what was just saved. */
    val card: SongTapCard? = null,
    val notice: SongTapNotice? = null,
    /** When the animation of the current beam starts on this phone's clock, and which side it plays (0 when none). */
    val beamStartMs: Long = 0L,
    val beamRole: Role? = null,
) {
    val phase: Phase get() = session.phase
    val role: Role get() = session.role
}

/**
 * Song Tap's controller (a process-wide state holder, like the other `*StateHolder`s): it owns the session
 * state machine from the `:songtap` core, feeds it what the transports and the listener do, and carries out
 * what it asks for (save, undo, publish, haptics, timeouts).
 *
 * Nothing runs until the Song Tap screen arms it, and [disarm] stops everything: no scanning, advertising
 * or camera outside the screen.
 */
@Singleton
class SongTapStateHolder @Inject constructor(
    private val settings: SongTapSettings,
    private val receiver: SongTapReceiver,
    private val sender: SongTapSender,
    private val qr: QrTransport,
    private val playback: PlaybackStateHolder,
    @AppScope private val scope: CoroutineScope,
) {
    private val lock = Any()
    @Volatile private var session = SessionState.initial()

    private val _ui = MutableStateFlow(SongTapUiState())
    val ui: StateFlow<SongTapUiState> = _ui.asStateFlow()

    private val _haptics = MutableSharedFlow<HapticCue>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The vibrations the screen performs (it owns the View and the user's haptics setting). */
    val haptics: SharedFlow<HapticCue> = _haptics.asSharedFlow()

    private var observing: Job? = null
    private val timeouts = HashMap<TimeoutTag, Job>()
    private var dismissJob: Job? = null
    private var refreshJob: Job? = null
    private var saveJob: Job? = null
    private var pendingOffer: SongTapPayload? = null
    private var incoming: SongTapPayload? = null
    private var lastScanText: String? = null
    private var lastScanAtMs = 0L

    private fun now() = System.currentTimeMillis()

    /** Called by the screen when it opens: starts watching the settings, the playing song and the transports. */
    fun attach() {
        if (observing?.isActive == true) return
        observing = scope.launch {
            launch { settings.allowed.collect { on -> _ui.update { it.copy(allowed = on) }; if (!on) disarm() } }
            launch {
                playback.stablePlayerState.map { it.currentSong }.distinctUntilChanged { a, b -> a?.id == b?.id }.collectLatest { song ->
                    _ui.update { it.copy(nowPlaying = song?.let(::nowPlayingOf)) }
                }
            }
            launch { qr.code.collect { code -> _ui.update { it.copy(code = code) } } }
            launch { qr.events.collect { handle(it) } }
        }
    }

    /** Called when the screen closes for good (not on a configuration change). */
    fun detach() {
        disarm()
        observing?.cancel()
        observing = null
    }

    private fun nowPlayingOf(song: Song) = SongTapNowPlaying(song.title, song.artist, song.albumArtUriString, canSend = song.spotifyId != null)

    // ── What the listener does ────────────────────────────────────────────────────────

    fun allow(on: Boolean) {
        scope.launch { settings.setAllowed(on) }
    }

    fun arm(role: Role) {
        scope.launch {
            if (!settings.isAllowed()) return@launch
            dispatch(SessionEvent.Arm(role, settings.doesFirstBeamAsk()))
        }
    }

    fun disarm() {
        dispatch(SessionEvent.Disarm)
        cancelJobs()
    }

    fun setRole(role: Role) = dispatch(SessionEvent.SetRole(role))

    /** The listener flipped Send / Receive: whatever was on the table is put away first. */
    fun switchRole(role: Role) {
        refreshJob?.cancel()
        dispatch(SessionEvent.Cancel)
        dispatch(SessionEvent.Dismiss)
        dispatch(SessionEvent.SetRole(role))
    }

    /** Sender: put the song that is playing up as a code. */
    fun showCode() {
        scope.launch {
            val song = playback.stablePlayerState.value.currentSong
            val payload = song?.let { sender.payloadFor(it, now()) }
            pendingOffer = payload
            dispatch(SessionEvent.QrShown(payload?.songKey ?: ""))
            if (payload != null) startRefresh(song)
        }
    }

    fun hideCode() {
        refreshJob?.cancel()
        dispatch(SessionEvent.Cancel)
        dispatch(SessionEvent.Dismiss)
    }

    /** The camera read [text]. The same code seen again within a few seconds is the same beam. */
    fun onScanned(text: String) {
        val t = now()
        if (text == lastScanText && t - lastScanAtMs < SAME_SCAN_MS) return
        lastScanText = text
        lastScanAtMs = t
        qr.onScanned(text)
    }

    fun accept() {
        scope.launch { settings.markFirstBeamAccepted() }
        dispatch(SessionEvent.Accept(now()))
    }

    fun decline() = dispatch(SessionEvent.Decline)
    fun undo() = dispatch(SessionEvent.Undo(now()))
    fun cancel() = dispatch(SessionEvent.Cancel)
    fun dismiss() = dispatch(SessionEvent.Dismiss)

    // ── The session ───────────────────────────────────────────────────────────────────

    private fun dispatch(event: SessionEvent) {
        val effects: List<SessionEffect>
        synchronized(lock) {
            val r = SongTapSession.reduce(session, event)
            session = r.state
            effects = r.effects
            publish(event)
        }
        for (effect in effects) perform(effect)
        // A code is up only while an offer is on the table.
        if (session.phase != Phase.OFFERED) qr.withdrawOffer()
        watchTerminal()
    }

    private fun publish(event: SessionEvent) {
        _ui.update { current ->
            val clearCard = session.phase == Phase.ARMED || session.phase == Phase.IDLE
            current.copy(
                session = session,
                card = if (clearCard) null else current.card,
                notice = if (event is SessionEvent.Dismiss || event is SessionEvent.Arm || event is SessionEvent.Disarm) null else current.notice,
            )
        }
    }

    private fun handle(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerFound -> dispatch(SessionEvent.PeerFound(event.peer))
            is TransportEvent.PeerLost -> dispatch(SessionEvent.PeerLost(event.peer))
            is TransportEvent.PeerNear -> dispatch(SessionEvent.PeerNear(event.peer))
            is TransportEvent.PeerFar -> dispatch(SessionEvent.PeerFar(event.peer))
            is TransportEvent.PeerRole -> dispatch(SessionEvent.PeerRole(event.peer, event.role))
            is TransportEvent.Tap -> scope.launch {
                val song = playback.stablePlayerState.value.currentSong
                val payload = song?.let { sender.payloadFor(it, now()) }
                pendingOffer = payload
                dispatch(SessionEvent.TapDetected(event.peer, payload?.songKey ?: ""))
            }
            is TransportEvent.Offer -> {
                val decoded = SongTapPayloads.decode(event.payload)
                val fresh = decoded is PayloadDecode.Ok &&
                    SongTapSigning.isFresh(decoded.payload.ts, now(), if (event.peer == 0) SongTapSigning.QR_WINDOW_MS else SongTapSigning.OFFER_WINDOW_MS)
                if (decoded is PayloadDecode.Ok && fresh) {
                    incoming = decoded.payload
                    _ui.update { it.copy(card = cardOf(decoded.payload)) }
                    dispatch(SessionEvent.OfferReceived(event.peer, decoded.payload.songKey, event.senderKey, now()))
                } else {
                    dispatch(SessionEvent.OfferRejected)
                }
            }
            TransportEvent.OfferInvalid -> dispatch(SessionEvent.OfferRejected)
            is TransportEvent.Ack -> dispatch(SessionEvent.AckReceived(now(), 0L))
            is TransportEvent.Go -> dispatch(SessionEvent.GoReceived(now(), event.rttMs))
            is TransportEvent.Declined -> dispatch(SessionEvent.PeerDeclined(event.peer))
        }
    }

    private fun cardOf(p: SongTapPayload) = SongTapCard(videoId = p.ytVideoId ?: "", songId = "", title = p.title, artist = p.artist, artUrl = p.artUrl)

    private fun transportFor(peer: Int): SongTapTransport = qr

    private fun perform(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.StartTransport -> qr.start(effect.role)
            SessionEffect.StopTransport -> { qr.stop(); cancelJobs() }
            is SessionEffect.PublishOffer -> pendingOffer?.let { p ->
                SongTapPayloads.encode(p)?.let { transportFor(effect.peer).publishOffer(effect.peer, it) }
            }
            is SessionEffect.ReadOffer -> transportFor(effect.peer).readOffer(effect.peer)
            is SessionEffect.SendAck -> transportFor(effect.peer).sendAck(effect.peer)
            is SessionEffect.SendGo -> transportFor(effect.peer).sendGo(effect.peer, effect.startMs)
            is SessionEffect.SendDecline -> transportFor(effect.peer).sendDecline(effect.peer)
            is SessionEffect.AbortTransfer -> transportFor(effect.peer).abort(effect.peer)
            is SessionEffect.StartBeam -> _ui.update { it.copy(beamStartMs = effect.startMs, beamRole = effect.role) }
            is SessionEffect.SaveSong -> startSave()
            is SessionEffect.UndoSave -> startUndo(effect.videoId)
            is SessionEffect.Haptic -> _haptics.tryEmit(effect.cue)
            is SessionEffect.ScheduleTimeout -> schedule(effect.tag, effect.afterMs)
            is SessionEffect.Notice -> _ui.update { it.copy(notice = noticeOf(effect.reason)) }
            SessionEffect.NoticeInvalidCode -> {
                _ui.update { it.copy(notice = SongTapNotice.INVALID_CODE) }
                // A stranger's code is a passing remark, not a result: it goes by itself.
                scope.launch {
                    delay(INVALID_NOTICE_MS)
                    _ui.update { if (it.notice == SongTapNotice.INVALID_CODE) it.copy(notice = null) else it }
                }
            }
        }
    }

    private fun noticeOf(reason: FailReason): SongTapNotice = when (reason) {
        FailReason.NOTHING_TO_SEND -> SongTapNotice.NOTHING_TO_SEND
        FailReason.BOTH_SENDING -> SongTapNotice.BOTH_SENDING
        FailReason.PEER_LOST -> SongTapNotice.PEER_LOST
        FailReason.TIMEOUT -> SongTapNotice.TIMEOUT
        FailReason.INVALID_OFFER -> SongTapNotice.INVALID_OFFER
        FailReason.NOT_FOUND -> SongTapNotice.NOT_FOUND
        FailReason.SAVE_FAILED -> SongTapNotice.SAVE_FAILED
        FailReason.TOO_SOON -> SongTapNotice.TOO_SOON
        FailReason.LIMIT -> SongTapNotice.LIMIT
        FailReason.DUPLICATE -> SongTapNotice.DUPLICATE
        FailReason.MUTED -> SongTapNotice.MUTED
    }

    // ── Save, undo ────────────────────────────────────────────────────────────────────

    private fun startSave() {
        val payload = incoming ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            // The import is not interrupted half way: if the session was cancelled meanwhile it is taken back below.
            val result = withContext(NonCancellable) { receiver.save(payload) }
            val stillWanted = isActive && session.phase == Phase.TRANSFERRING && session.videoId == payload.songKey
            when (result) {
                is SongTapSaveResult.Saved -> {
                    if (!stillWanted) { receiver.undo(result.card.videoId); return@launch }
                    _ui.update { it.copy(card = result.card) }
                    dispatch(SessionEvent.SaveResult(SaveOutcome.SAVED, now()))
                }
                is SongTapSaveResult.AlreadyHad -> {
                    if (!stillWanted) return@launch
                    _ui.update { it.copy(card = result.card) }
                    dispatch(SessionEvent.SaveResult(SaveOutcome.ALREADY_HAD, now()))
                }
                SongTapSaveResult.NotFound -> if (stillWanted) dispatch(SessionEvent.SaveResult(SaveOutcome.NOT_FOUND, now()))
                SongTapSaveResult.Failed -> if (stillWanted) dispatch(SessionEvent.SaveResult(SaveOutcome.FAILED, now()))
            }
        }
    }

    private fun startUndo(songKey: String) {
        saveJob?.cancel()
        val card = _ui.value.card
        val videoId = card?.videoId?.takeIf { it.isNotEmpty() } ?: incoming?.ytVideoId ?: songKey
        scope.launch { receiver.undo(videoId) }
    }

    // ── Timers and upkeep ─────────────────────────────────────────────────────────────

    private fun schedule(tag: TimeoutTag, afterMs: Long) {
        timeouts[tag]?.cancel()
        timeouts[tag] = scope.launch {
            delay(afterMs)
            dispatch(SessionEvent.Timeout(tag))
        }
    }

    /** A result stays on screen for a moment and then the screen goes back to waiting. */
    private fun watchTerminal() {
        val phase = session.phase
        val terminal = phase == Phase.SAVED || phase == Phase.DECLINED || phase == Phase.FAILED || phase == Phase.CANCELLED
        if (!terminal) { dismissJob?.cancel(); dismissJob = null; return }
        if (dismissJob?.isActive == true) return
        val stay = if (phase == Phase.SAVED) com.theveloper.pixelplay.songtap.SongTapSession.UNDO_WINDOW_MS else RESULT_MS
        dismissJob = scope.launch {
            delay(stay)
            dispatch(SessionEvent.Dismiss)
        }
    }

    /** A code expires after five minutes; while it is up, make a fresh one every four. */
    private fun startRefresh(song: Song?) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(QR_REFRESH_MS)
                if (session.phase != Phase.OFFERED || song == null) return@launch
                val fresh = sender.payloadFor(song, now()) ?: return@launch
                pendingOffer = fresh
                SongTapPayloads.encode(fresh)?.let { qr.publishOffer(0, it) }
            }
        }
    }

    private fun cancelJobs() {
        for (job in timeouts.values) job.cancel()
        timeouts.clear()
        dismissJob?.cancel()
        dismissJob = null
        refreshJob?.cancel()
        refreshJob = null
    }

    private companion object {
        const val RESULT_MS = 4_000L
        const val INVALID_NOTICE_MS = 2_500L
        const val QR_REFRESH_MS = 240_000L
        const val SAME_SCAN_MS = 10_000L
    }
}
