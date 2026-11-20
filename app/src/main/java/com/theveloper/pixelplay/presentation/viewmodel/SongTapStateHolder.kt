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
import com.theveloper.pixelplay.data.songtap.ble.BleStatus
import com.theveloper.pixelplay.data.songtap.ble.BleTransport
import com.theveloper.pixelplay.data.songtap.ble.SongTapDiagnostics
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
import com.theveloper.pixelplay.songtap.SongTapTimeline
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
    /** The current beam plays the short cross-fade (animations are off on this phone), not the flight. */
    val beamReduced: Boolean = false,
    /** Whether Bluetooth is running, and if not why. The code route works either way. */
    val bluetooth: BleStatus = BleStatus.IDLE,
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
    private val ble: BleTransport,
    private val diagnostics: SongTapDiagnostics,
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

    /** The approach glow for the screen edge, 0..500 permille; read in the draw phase, so it is a flow of its own. */
    val glow: StateFlow<Int> get() = ble.glow

    /** The numbers behind the tap detector, for the Developer mode diagnostics screen. */
    val diag: SongTapDiagnostics get() = diagnostics

    /** True when the phone's animations are off (animator scale 0): the beam then cross-fades instead of flying. Replaceable in tests. */
    internal var reducedMotion: () -> Boolean = { !android.animation.ValueAnimator.areAnimatorsEnabled() }

    private var beamJob: Job? = null
    private var observing: Job? = null
    private val timeouts = HashMap<TimeoutTag, Job>()
    private var dismissJob: Job? = null
    private var refreshJob: Job? = null
    private var saveJob: Job? = null
    private var pendingOffer: SongTapPayload? = null

    /** The offer the session accepted and is acting on (set only when the session took it, never by a mere arrival). */
    private var incoming: SongTapPayload? = null

    /** An offer that has arrived and is being offered to the session: it becomes [incoming] only if the session takes it. */
    private var candidate: SongTapPayload? = null

    /** The video the last successful save added, so Undo takes back exactly that. */
    private var savedKey: String = ""
    private var savedVideoId: String = ""

    /** Set synchronously by [arm] and cleared by [disarm]: an arm that was overtaken by a disarm must not start the radio. */
    @Volatile private var wanted = false
    private val owners = HashSet<Any>()
    private var lastScanText: String? = null
    private var lastScanAtMs = 0L

    private fun now() = System.currentTimeMillis()

    /**
     * Called by a screen when it opens: starts watching the settings, the playing song and the transports. Two screens
     * (the Song Tap screen and the diagnostics) may be open at once, and one closing must not stop the other's observers,
     * so each is counted by [owner] and the observers go only when the last one leaves.
     */
    fun attach(owner: Any = this) {
        synchronized(owners) {
            owners.add(owner)
            if (observing?.isActive == true) return
        }
        observing = scope.launch {
            launch { settings.allowed.collect { on -> _ui.update { it.copy(allowed = on) }; if (!on) disarm() } }
            launch {
                playback.stablePlayerState.map { it.currentSong }.distinctUntilChanged { a, b -> a?.id == b?.id }.collectLatest { song ->
                    _ui.update { it.copy(nowPlaying = song?.let(::nowPlayingOf)) }
                    // A code that is up is for the song that plays: when the track changes, the code changes with it.
                    if (session.role == Role.SEND && session.phase == Phase.OFFERED && session.peer == 0 && qr.code.value != null) {
                        hideCode()
                        showCode()
                    }
                }
            }
            launch { qr.code.collect { code -> _ui.update { it.copy(code = code) } } }
            launch { qr.events.collect { handle(it) } }
            launch { ble.events.collect { handle(it) } }
            launch { ble.status.collect { status -> _ui.update { it.copy(bluetooth = status) } } }
        }
    }

    /** Called when a screen closes for good (not on a configuration change). */
    fun detach(owner: Any = this) {
        synchronized(owners) {
            owners.remove(owner)
            if (owners.isNotEmpty()) return
        }
        disarm()
        diagnostics.setEnabled(false)
        observing?.cancel()
        observing = null
    }

    private fun nowPlayingOf(song: Song) = SongTapNowPlaying(song.title, song.artist, song.albumArtUriString, canSend = song.spotifyId != null)

    // ── What the listener does ────────────────────────────────────────────────────────

    fun allow(on: Boolean) {
        scope.launch { settings.setAllowed(on) }
    }

    fun arm(role: Role) {
        wanted = true
        scope.launch {
            if (!settings.isAllowed()) return@launch
            val askFirst = settings.doesFirstBeamAsk()
            // The settings reads suspend: if the screen paused meanwhile, the arm is dropped (checked again inside dispatch).
            if (!wanted) return@launch
            dispatch(SessionEvent.Arm(role, askFirst))
        }
    }

    /** The Bluetooth permission was just granted, or Bluetooth was turned on: try the radio again. */
    fun bluetoothChanged() = ble.restart()

    /** Developer mode diagnostics: write the current signal into the log under [label] ("touching", "1 cm"...). */
    fun mark(label: String) = ble.mark(label)

    fun disarm() {
        wanted = false
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
            if (payload != null) startRefresh()
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
        val before: Phase
        synchronized(lock) {
            // An Arm that lost the race against a Disarm does nothing.
            if (event is SessionEvent.Arm && !wanted) return
            before = session.phase
            val previous = session
            val r = SongTapSession.reduce(session, event)
            session = r.state
            effects = r.effects
            // The offer on the table is written down only now that the session has taken it: an offer the session ignored
            // (one already being asked about, or being saved) must not replace the card or the song that Undo would remove.
            if (event is SessionEvent.OfferReceived) {
                val offered = candidate
                candidate = null
                val taken = offered != null && offered.songKey == event.videoId && previous.videoId != event.videoId &&
                    session.videoId == event.videoId && (session.phase == Phase.OFFERED || session.phase == Phase.TRANSFERRING)
                if (taken) {
                    incoming = offered
                    _ui.update { it.copy(card = cardOf(offered!!)) }
                }
            }
            publish(event)
        }
        for (effect in effects) perform(effect)
        // A code is up, and an offer readable over Bluetooth, only while an offer is on the table.
        if (session.phase != Phase.OFFERED) { qr.withdrawOffer(); ble.withdrawOffer() }
        // Back to waiting after a result: the phone that is close has to be announced again to be tappable.
        if (before != Phase.ARMED && before != Phase.IDLE && session.phase == Phase.ARMED) ble.resync()
        watchTerminal()
    }

    private fun publish(event: SessionEvent) {
        _ui.update { current ->
            val clearCard = session.phase == Phase.ARMED || session.phase == Phase.IDLE
            current.copy(
                session = session,
                card = if (clearCard) null else current.card,
                notice = when {
                    event is SessionEvent.Dismiss || event is SessionEvent.Arm || event is SessionEvent.Disarm -> null
                    // A failure the session reports without a notice of its own (a lost peer, a timeout) is worded from its reason.
                    session.phase == Phase.FAILED && session.fail != null -> noticeOf(session.fail!!)
                    else -> current.notice
                },
            )
        }
        // A beam belongs to a session that is sending or saving; anything else (a failure, a cancel, Undo) ends it at once.
        if (session.phase != Phase.TRANSFERRING && session.phase != Phase.SAVED) clearBeam()
    }

    private fun clearBeam() {
        beamJob?.cancel()
        beamJob = null
        _ui.update { if (it.beamRole == null) it else it.copy(beamRole = null, beamStartMs = 0L) }
    }

    private fun handle(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerFound -> dispatch(SessionEvent.PeerFound(event.peer))
            is TransportEvent.PeerLost -> dispatch(SessionEvent.PeerLost(event.peer))
            is TransportEvent.PeerNear -> dispatch(SessionEvent.PeerNear(event.peer))
            is TransportEvent.PeerFar -> dispatch(SessionEvent.PeerFar(event.peer))
            is TransportEvent.PeerRole -> dispatch(SessionEvent.PeerRole(event.peer, event.role))
            is TransportEvent.Tap -> scope.launch {
                if (session.role == Role.RECEIVE) {
                    // The receiver sends nothing: the tap only means "read the offer now".
                    dispatch(SessionEvent.TapDetected(event.peer, ""))
                } else {
                    val song = playback.stablePlayerState.value.currentSong
                    val payload = song?.let { sender.payloadFor(it, now()) }
                    pendingOffer = payload
                    dispatch(SessionEvent.TapDetected(event.peer, payload?.songKey ?: ""))
                }
            }
            is TransportEvent.Offer -> {
                val decoded = SongTapPayloads.decode(event.payload)
                val fresh = decoded is PayloadDecode.Ok &&
                    SongTapSigning.isFresh(decoded.payload.ts, now(), if (event.peer == 0) SongTapSigning.QR_WINDOW_MS else SongTapSigning.OFFER_WINDOW_MS)
                if (decoded is PayloadDecode.Ok && fresh) {
                    synchronized(lock) { candidate = decoded.payload }
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

    /** A code has no peer (0); a phone found over Bluetooth has a number. */
    private fun transportFor(peer: Int): SongTapTransport = if (peer == 0) qr else ble

    private fun perform(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.StartTransport -> { qr.start(effect.role); ble.start(effect.role) }
            SessionEffect.StopTransport -> { qr.stop(); ble.stop(); cancelJobs() }
            is SessionEffect.PublishOffer -> pendingOffer?.let { p ->
                SongTapPayloads.encode(p)?.let { transportFor(effect.peer).publishOffer(effect.peer, it) }
            }
            is SessionEffect.ReadOffer -> transportFor(effect.peer).readOffer(effect.peer)
            is SessionEffect.SendAck -> transportFor(effect.peer).sendAck(effect.peer)
            is SessionEffect.SendGo -> transportFor(effect.peer).sendGo(effect.peer, effect.startMs)
            is SessionEffect.SendDecline -> transportFor(effect.peer).sendDecline(effect.peer)
            is SessionEffect.AbortTransfer -> transportFor(effect.peer).abort(effect.peer)
            is SessionEffect.StartBeam -> startBeam(effect.role, effect.startMs)
            is SessionEffect.SaveSong -> startSave()
            is SessionEffect.UndoSave -> startUndo(effect.videoId)
            // A beam (playing, or about to: any phone found over Bluetooth) does its own vibrating from the timeline's cue
            // list; the session's "success" would buzz twice.
            is SessionEffect.Haptic -> if (!(effect.cue == HapticCue.SUCCESS && (_ui.value.beamRole != null || session.peer != 0))) _haptics.tryEmit(effect.cue)
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

    // ── The beam ──────────────────────────────────────────────────────────────────────

    private fun startBeam(role: Role, startMs: Long) {
        val reduced = reducedMotion()
        beamJob?.cancel()
        // From here the beam owns the end of the result (the receiver's card goes with the beam's own end).
        dismissJob?.cancel()
        dismissJob = null
        _ui.update { it.copy(beamStartMs = startMs, beamRole = role, beamReduced = reduced) }
        beamJob = scope.launch {
            delay((startMs + SongTapTimeline.totalMs(role, reduced) - now()).coerceAtLeast(0L))
            // The sender's beam is the whole send: when it is over, the session is done. The receiver's card
            // is over when the Undo window is, which is also when the saved result is put away.
            if (role == Role.SEND) dispatch(SessionEvent.BeamFinished) else dispatch(SessionEvent.Dismiss)
            clearBeam()
        }
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
                    savedKey = payload.songKey
                    savedVideoId = result.card.videoId
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
        // What this session's save added, by the session's own song key; never whatever the card happens to show.
        val videoId = savedVideoId.takeIf { savedKey == songKey && it.isNotEmpty() }
            ?: incoming?.takeIf { it.songKey == songKey }?.ytVideoId
            ?: songKey
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
        // A saved card that came with a beam is put away by the beam's own end (its Undo chip lives inside it).
        if (phase == Phase.SAVED && _ui.value.beamRole == Role.RECEIVE) return
        val stay = if (phase == Phase.SAVED) SongTapSession.UNDO_WINDOW_MS else RESULT_MS
        // Over Bluetooth the receiver saves first and the sender's go (which starts the beam) arrives a moment later: the
        // Undo window must not begin before the beam does, or the card would be taken away in the middle of it.
        val waitForBeam = phase == Phase.SAVED && session.role == Role.RECEIVE && session.peer != 0
        dismissJob = scope.launch {
            if (waitForBeam) {
                delay(GO_GRACE_MS)
                if (_ui.value.beamRole != null) return@launch
            }
            delay(stay)
            dispatch(SessionEvent.Dismiss)
        }
    }

    /** A code expires after five minutes; while it is up, make a fresh one every four, for the song that plays at that moment. */
    private fun startRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(QR_REFRESH_MS)
                val song = playback.stablePlayerState.value.currentSong
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

        /** After a save over Bluetooth, how long to wait for the sender's go before the Undo window starts without a beam. */
        const val GO_GRACE_MS = 3_000L
    }
}
