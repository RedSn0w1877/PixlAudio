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
import com.theveloper.pixelplay.data.songtap.ble.CalibrationState
import com.theveloper.pixelplay.data.songtap.ble.SongTapDiagnostics
import com.theveloper.pixelplay.data.songtap.ring.RingCodeDiagnostics
import com.theveloper.pixelplay.data.songtap.ring.RingCodeResolver
import com.theveloper.pixelplay.data.songtap.ring.RingCodeSlots
import com.theveloper.pixelplay.data.songtap.ring.RingScanSink
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.songtap.FailReason
import com.theveloper.pixelplay.songtap.HapticCue
import com.theveloper.pixelplay.songtap.NearbyEntry
import com.theveloper.pixelplay.songtap.PayloadDecode
import com.theveloper.pixelplay.songtap.Phase
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SaveOutcome
import com.theveloper.pixelplay.songtap.SessionEffect
import com.theveloper.pixelplay.songtap.SessionEvent
import com.theveloper.pixelplay.songtap.SessionState
import com.theveloper.pixelplay.songtap.SongTapPayload
import com.theveloper.pixelplay.songtap.SongTapPayloads
import com.theveloper.pixelplay.songtap.SongTapSensitivity
import com.theveloper.pixelplay.songtap.SongTapSession
import com.theveloper.pixelplay.songtap.SongTapTimeline
import com.theveloper.pixelplay.songtap.SongTapSigning
import com.theveloper.pixelplay.songtap.TimeoutTag
import com.theveloper.pixelplay.songtap.ring.RingCodePayload
import com.theveloper.pixelplay.songtap.ring.RingCodePayloads
import com.theveloper.pixelplay.songtap.ring.RingReceiveFlow
import com.theveloper.pixelplay.songtap.ring.RingScanView
import com.theveloper.pixelplay.songtap.ring.RingSendRules
import com.theveloper.pixelplay.songtap.ring.RingStep
import com.theveloper.pixelplay.songtap.ring.image.RingFrame
import com.theveloper.pixelplay.songtap.ring.image.RingScanResult
import com.theveloper.pixelplay.songtap.ring.image.RingScanner
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
import kotlinx.coroutines.withTimeoutOrNull

/** The song that is playing, as the Send side shows it. */
@Immutable
data class SongTapNowPlaying(val title: String, val artist: String, val artUrl: String?, val canSend: Boolean)

/** Which code a sender is showing: the Pixl ring code (the default) or the plain QR (last resort, or for a song with no video yet). */
enum class SongTapCodeKind { RING, QR }

/** Why a sender shows the QR code without being asked to. */
enum class SongTapRingNote { NO_VIDEO }

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
    /** Song Drop (sender): phones advertising as receivers inside the list gate, strongest first, from advertisements only. */
    val nearby: List<NearbyEntry> = emptyList(),
    /** Song Drop (sender): the phone inside the strict near gate to ask "Song drop to X?" about, or null. */
    val prompt: NearbyEntry? = null,
    /** The other phone's name for the cards: the sender on a receiver, the target on a sender; null when unknown. */
    val peerName: String? = null,
    /** True while the song being offered is a Song Drop song picked from a song's options (not just the one that plays). */
    val dropMode: Boolean = false,
    /** The shift added to every raw signal reading (spec 6, the iPhone scale), how sensitive the tap is, and what the Calibrate button is doing. */
    val offsetDb: Int = 0,
    val sensitivity: SongTapSensitivity = SongTapSensitivity.DEFAULT,
    val calibration: CalibrationState = CalibrationState.Idle,
    /** Sender: which code is on screen, the ring code's dots (a new one with a fresh tag every minute), and why a QR came up by itself. */
    val codeKind: SongTapCodeKind? = null,
    val ring: RingCodeSlots? = null,
    val ringNote: SongTapRingNote? = null,
    /** Receiver: what the ring code scan is doing, and whether "Use QR code instead" is on offer. */
    val ringScan: RingScanView = RingScanView.IDLE,
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
    private val ringResolver: RingCodeResolver,
    private val ringDiagnostics: RingCodeDiagnostics,
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

    /** The ring code receiver's rules over time (three reads, the freshness and replay guard, when the QR is offered). Touched under [ringLock]. */
    private val ringLock = Any()
    private val ringFlow = RingReceiveFlow()
    private var ringTick: Job? = null

    /** The sender chose "Use QR code instead": a rebuilt code stays a QR until the listener leaves. */
    @Volatile private var qrPreferred = false
    private var ringSerial = 0

    /** Receiver (spec 5): the checked offer fetched ahead of the tap, kept until the session commits to it or lets it go. */
    private var prefetched: SongTapPayload? = null

    /** Song Drop from a song's options: this song is offered instead of the one that plays, until the screen is left. */
    @Volatile private var dropSong: Song? = null

    private val _dropRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Asks the navigation layer to open the Song Drop picker (a song's options sheet raised it). */
    val dropRequests: SharedFlow<Unit> = _dropRequests.asSharedFlow()

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

    /** The wall clock, replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** A byte of randomness for a new ring code's tag, replaceable in tests. */
    internal var ringRandom: () -> Int = { ringSecureRandom.nextInt(256) }

    private fun now() = clock()

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
                    _ui.update { it.copy(nowPlaying = (dropSong ?: song)?.let(::nowPlayingOf)) }
                    // A code that is up is for the song that plays: when the track changes, the code changes with it.
                    if (session.role == Role.SEND && session.phase == Phase.OFFERED && session.peer == 0 && qr.code.value != null) {
                        hideCode(keepQrChoice = true)
                        showCode()
                    }
                    // A phone armed to a phone nearby offers the song that plays: when the track changes, the offer changes with it.
                    if (session.role == Role.SEND && session.phase == Phase.OFFERED && session.target != 0 && dropSong == null) republishTarget()
                }
            }
            launch { settings.rssiOffsetDb.collect { db -> ble.setRssiOffset(db); _ui.update { it.copy(offsetDb = db) } } }
            launch { settings.sensitivity.collect { value -> ble.setSensitivity(value); _ui.update { it.copy(sensitivity = value) } } }
            launch {
                ble.calibration.collect { c ->
                    // A finished calibration is kept for good; the transport has already applied it live.
                    if (c is CalibrationState.Done) settings.setRssiOffsetDb(c.offsetDb)
                    _ui.update { it.copy(calibration = c) }
                }
            }
            launch { ble.nearby.collect { list -> _ui.update { it.copy(nearby = list) } } }
            launch { ble.prompt.collect { entry -> _ui.update { it.copy(prompt = entry) } } }
            launch { qr.code.collect { code -> _ui.update { it.copy(code = code) } } }
            launch { qr.events.collect { handle(it) } }
            launch { ble.events.collect { handle(it) } }
            launch {
                ble.status.collect { status ->
                    _ui.update { it.copy(bluetooth = status) }
                    // Bluetooth went away under a sender that was armed to a phone: the offer is gone with it.
                    if (status != BleStatus.ACTIVE && status != BleStatus.IDLE && session.phase == Phase.OFFERED && session.target != 0) {
                        dispatch(SessionEvent.Cancel); dispatch(SessionEvent.Dismiss)
                    }
                }
            }
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
        // Already armed as this role (a resume that follows no pause, a second screen): nothing to start, and the radio must not restart.
        val current = session
        if (current.role == role && current.phase in ARMED_PHASES) return
        scope.launch {
            if (!settings.isAllowed()) return@launch
            val askFirst = settings.doesFirstBeamAsk()
            ble.setOwnName(settings.nameNow())
            ble.setRssiOffset(settings.rssiOffsetNow())
            ble.setSensitivity(settings.sensitivityNow())
            // The settings reads suspend: if the screen paused meanwhile, the arm is dropped (checked again inside dispatch).
            if (!wanted) return@launch
            dispatch(SessionEvent.Arm(role, askFirst))
        }
    }

    /** The Bluetooth permission was just granted, or Bluetooth was turned on: try the radio again. */
    fun bluetoothChanged() = ble.restart()

    /** Sensitivity (Normal or High), saved and applied live to the detector and the jerk threshold. */
    fun setSensitivity(value: SongTapSensitivity) {
        scope.launch { settings.setSensitivity(value) }
    }

    /** The Calibrate button went down (phones touching) or up; after [com.theveloper.pixelplay.data.songtap.ble.RssiCalibration.HOLD_MS] the offset is measured and saved. */
    fun startCalibration() = ble.startCalibration()
    fun endCalibration() = ble.endCalibration()

    /** Forget the measured offset: the model's default is back. */
    fun resetOffset() {
        ble.clearCalibration()
        scope.launch { settings.setRssiOffsetDb(null) }
    }

    /** Developer mode diagnostics: write the current signal into the log under [label] ("touching", "1 cm"...). */
    fun mark(label: String) = ble.mark(label)

    fun disarm() {
        wanted = false
        qrPreferred = false
        dispatch(SessionEvent.Disarm)
        cancelJobs()
        ringScanStop()
        // A Song Drop song is for the screen that was open; leaving it puts the playing song back.
        if (dropSong != null) {
            dropSong = null
            _ui.update { it.copy(dropMode = false, nowPlaying = playback.stablePlayerState.value.currentSong?.let(::nowPlayingOf)) }
        }
    }

    fun setRole(role: Role) = dispatch(SessionEvent.SetRole(role))

    /** The listener flipped Send / Receive: whatever was on the table is put away first. */
    fun switchRole(role: Role) {
        refreshJob?.cancel()
        dispatch(SessionEvent.Cancel)
        dispatch(SessionEvent.Dismiss)
        dispatch(SessionEvent.SetRole(role))
    }

    /**
     * Sender: put the song that is playing up as a code. A song with a video id gets the Pixl ring code, made again with a fresh tag
     * every minute. A song that was never matched to a video has nothing the ring code could carry, so it goes straight to the QR
     * code and the screen says why ([SongTapRingNote.NO_VIDEO]).
     */
    fun showCode() {
        scope.launch {
            val song = offeredSong()
            val payload = song?.let { sender.payloadFor(it, now()) }
            pendingOffer = payload
            val video = payload?.ytVideoId
            val ringable = RingSendRules.canRing(video)
            val ring = payload != null && ringable && !qrPreferred
            _ui.update {
                it.copy(
                    codeKind = if (payload == null) null else if (ring) SongTapCodeKind.RING else SongTapCodeKind.QR,
                    ringNote = if (payload != null && !ringable) SongTapRingNote.NO_VIDEO else null,
                    ring = null,
                )
            }
            if (ring) makeRing(video!!)
            dispatch(SessionEvent.QrShown(payload?.songKey ?: ""))
            if (payload != null) {
                if (ring) startRingRefresh() else startRefresh()
            }
        }
    }

    /** The listener is done with the code (or the song changed under it: then the choice of QR is kept). */
    fun hideCode(keepQrChoice: Boolean = false) {
        if (!keepQrChoice) qrPreferred = false
        refreshJob?.cancel()
        dispatch(SessionEvent.Cancel)
        dispatch(SessionEvent.Dismiss)
    }

    /**
     * "Use QR code instead": the ring code gives way to the plain QR code for the same song. Offered by the screen after the code has been up for
     * six seconds without being taken; the two are never shown together.
     */
    fun showQrInstead() {
        scope.launch {
            if (session.role != Role.SEND || session.phase != Phase.OFFERED || session.peer != 0) return@launch
            val payload = offeredSong()?.let { sender.payloadFor(it, now()) } ?: return@launch
            qrPreferred = true
            refreshJob?.cancel()
            pendingOffer = payload
            SongTapPayloads.encode(payload)?.let { qr.publishOffer(0, it) }
            _ui.update { it.copy(codeKind = SongTapCodeKind.QR, ring = null) }
            startRefresh()
        }
    }

    /** A ring code for [videoId] with a fresh tag: this minute's bucket and a random byte. */
    private fun makeRing(videoId: String) {
        val bits = RingCodePayloads.encodeSlots(videoId, RingCodePayloads.bucketOf(now()), ringRandom() and 0xFF) ?: return
        _ui.update { it.copy(ring = RingCodeSlots(bits, ++ringSerial)) }
    }

    /** While a ring code is up it is made again with a fresh tag every [RingSendRules.CODE_LIFE_MS], for the song that plays at that moment. */
    private fun startRingRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(RingSendRules.CODE_LIFE_MS)
                if (session.phase != Phase.OFFERED || session.peer != 0 || _ui.value.codeKind != SongTapCodeKind.RING) return@launch
                val video = offeredSong()?.let { sender.payloadFor(it, now()) }?.ytVideoId
                if (!RingSendRules.canRing(video)) return@launch
                makeRing(video!!)
            }
        }
    }

    // ── The ring code, receiving ──────────────────────────────────────────────────────

    /** Where the camera's analysis thread reports: the diagnostics (when that screen is open) and the scan itself. */
    val ringSink: RingScanSink = object : RingScanSink {
        override fun onFrame(nowMs: Long) = ringDiagnostics.onFrame(nowMs)

        override fun onScan(frame: RingFrame, result: RingScanResult, scanner: RingScanner, rotationDegrees: Int, nowMs: Long) {
            ringDiagnostics.record(frame, result, scanner, rotationDegrees, nowMs)
            onRingResult(result)
        }

        override fun onSlow() {
            ringDiagnostics.onSlow()
            publishRing(synchronized(ringLock) { ringFlow.scannerTooSlow() })
        }
    }

    /** The numbers behind the ring scanner, for Developer mode. */
    val ringDiag: RingCodeDiagnostics get() = ringDiagnostics

    /** The receiver opened the camera for a ring code. [cameraAvailable] is false on a phone with no camera. */
    fun ringScanStart(cameraAvailable: Boolean) {
        publishRing(synchronized(ringLock) { ringFlow.start(now(), cameraAvailable) })
        ringTick?.cancel()
        ringTick = scope.launch {
            while (isActive) {
                delay(RING_TICK_MS)
                val view = synchronized(ringLock) { if (ringFlow.tick(now())) ringFlow.view() else null }
                if (view != null) publishRing(view)
            }
        }
    }

    /** The camera closed (the screen was left, the listener went back, or a code was taken). */
    fun ringScanStop() {
        ringTick?.cancel()
        ringTick = null
        publishRing(synchronized(ringLock) { ringFlow.stop() })
    }

    /** The camera would not start. */
    fun ringCameraFailed() = publishRing(synchronized(ringLock) { ringFlow.cameraFailed() })

    /** One scanner result (analysis thread). Three equal fresh decodes in a row start the lookup of the song. */
    fun onRingResult(result: RingScanResult) {
        if (session.role != Role.RECEIVE) return
        val step: RingStep
        val view: RingScanView
        synchronized(ringLock) {
            step = ringFlow.offer(result, now())
            view = ringFlow.view()
        }
        when (step) {
            is RingStep.Resolve -> { publishRing(view); resolveRing(step.payload) }
            is RingStep.Rejected -> publishRing(view)
            RingStep.None -> Unit
        }
    }

    /** The song named by the code's video id is looked up (it needs the internet), then goes through the same consent, save and Undo as every other beam. */
    private fun resolveRing(code: RingCodePayload) {
        scope.launch {
            val song = withTimeoutOrNull(RING_RESOLVE_TIMEOUT_MS) { ringResolver.resolve(code.videoId, now()) }
            val view = synchronized(ringLock) {
                if (ringFlow.view().stage != com.theveloper.pixelplay.songtap.ring.RingScanStage.RESOLVING) return@launch
                ringFlow.resolved(code, song != null, now())
            }
            publishRing(view)
            if (song != null) {
                synchronized(lock) { candidate = song }
                dispatch(SessionEvent.OfferReceived(0, song.songKey, 0, now()))
            }
        }
    }

    private fun publishRing(view: RingScanView) {
        _ui.update { if (it.ringScan == view) it else it.copy(ringScan = view) }
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

    /** The song a sender offers: the one picked for Song Drop, else the one that plays. */
    private fun offeredSong(): Song? = dropSong ?: playback.stablePlayerState.value.currentSong

    /** A song's options raised "Song Drop": offer [song], and ask the navigation layer to open the picker. */
    fun beginSongDrop(song: Song) {
        dropSong = song
        _ui.update { it.copy(dropMode = true, nowPlaying = nowPlayingOf(song)) }
        _dropRequests.tryEmit(Unit)
    }

    /** The picker was opened without a song of its own (from the Song Tap screen): the song that plays is offered. */
    fun endSongDrop() {
        if (dropSong == null) return
        dropSong = null
        _ui.update { it.copy(dropMode = false, nowPlaying = playback.stablePlayerState.value.currentSong?.let(::nowPlayingOf)) }
    }

    /**
     * "Song drop to X?" answered yes for the phone [peer] (a number from [SongTapUiState.nearby] or the prompt): the offer is published for
     * that phone only and the beam starts when the two phones touch.
     */
    fun approve(peer: Int, name: String) {
        scope.launch {
            val song = offeredSong()
            val payload = song?.let { sender.payloadFor(it, now()) }
            pendingOffer = payload
            _ui.update { it.copy(peerName = name) }
            dispatch(SessionEvent.Approve(peer, payload?.songKey ?: ""))
            if (payload != null) startTargetRefresh()
        }
    }

    /** "No": that phone is not asked about for a minute. */
    fun decideNo(peer: Int) = ble.ignorePrompt(peer)

    /** Stop being armed to the phone that was said yes to. */
    fun cancelDrop() {
        refreshJob?.cancel()
        dispatch(SessionEvent.Cancel)
        dispatch(SessionEvent.Dismiss)
    }

    private fun republishTarget() {
        scope.launch {
            val song = offeredSong() ?: return@launch
            val payload = sender.payloadFor(song, now()) ?: return@launch
            pendingOffer = payload
            dispatch(SessionEvent.Republish(payload.songKey))
        }
    }

    /** The offer a sender holds for a phone has a time in it: while armed it is made again every [TARGET_REFRESH_MS]. */
    private fun startTargetRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(TARGET_REFRESH_MS)
                if (session.phase != Phase.OFFERED || session.target == 0) return@launch
                republishTarget()
            }
        }
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
            // A tap that committed to an offer that was fetched ahead: that offer is now the one on the table.
            if (event is SessionEvent.TapDetected && previous.pre && !session.pre &&
                (session.phase == Phase.OFFERED || session.phase == Phase.TRANSFERRING)
            ) {
                val held = prefetched
                if (held != null && held.songKey == session.videoId) {
                    incoming = held
                    _ui.update { it.copy(card = cardOf(held)) }
                }
            }
            if (!session.pre) prefetched = null
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
        // A code is up, and an offer readable over Bluetooth, only while an offer is on the table. A sender keeps its Bluetooth offer
        // through the beam (the receiver's answer still has to be matched to the phone that read it).
        if (session.phase != Phase.OFFERED) qr.withdrawOffer()
        // The ring code is on screen only while a code offer is on the table.
        if ((session.phase != Phase.OFFERED || session.target != 0) && _ui.value.codeKind != null) {
            _ui.update { it.copy(codeKind = null, ring = null, ringNote = null) }
        }
        if (session.phase != Phase.OFFERED && !(session.role == Role.SEND && session.phase == Phase.TRANSFERRING)) ble.withdrawOffer()
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
                peerName = if (clearCard) null else current.peerName,
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
            // The commit: no coroutine hop and no work before it, so the haptic and the beam start at the moment of the tap. The sender's
            // song is already made (at "Yes"), the receiver's offer is already in hand (prefetched).
            is TransportEvent.Tap -> dispatch(
                SessionEvent.TapDetected(event.peer, if (session.role == Role.RECEIVE) "" else pendingOffer?.songKey ?: "", now()),
            )
            is TransportEvent.OfferPrefetched -> {
                val decoded = SongTapPayloads.decode(event.payload)
                val fresh = decoded is PayloadDecode.Ok &&
                    SongTapSigning.isFresh(decoded.payload.ts, now(), PREFETCH_PAYLOAD_WINDOW_MS)
                if (decoded is PayloadDecode.Ok && fresh) {
                    synchronized(lock) { prefetched = decoded.payload }
                    event.name?.let { n -> _ui.update { it.copy(peerName = n) } }
                    dispatch(SessionEvent.OfferPrefetched(event.peer, decoded.payload.songKey, event.senderKey))
                }
            }
            is TransportEvent.OfferGone -> {
                dispatch(SessionEvent.OfferGone(event.peer))
            }
            is TransportEvent.OfferRead -> dispatch(SessionEvent.OfferRead(event.peer, now()))
            is TransportEvent.OfferReadLost -> dispatch(SessionEvent.OfferReadLost(event.peer))
            is TransportEvent.PeerName -> if (event.peer == session.peer && session.role == Role.RECEIVE) _ui.update { it.copy(peerName = event.name) }
            is TransportEvent.Offer -> {
                val decoded = SongTapPayloads.decode(event.payload)
                val fresh = decoded is PayloadDecode.Ok &&
                    SongTapSigning.isFresh(decoded.payload.ts, now(), if (event.peer == 0) SongTapSigning.QR_WINDOW_MS else PREFETCH_PAYLOAD_WINDOW_MS)
                if (decoded is PayloadDecode.Ok && fresh) {
                    synchronized(lock) { candidate = decoded.payload }
                    event.name?.let { n -> _ui.update { it.copy(peerName = n) } }
                    dispatch(SessionEvent.OfferReceived(event.peer, decoded.payload.songKey, event.senderKey, now()))
                } else {
                    dispatch(SessionEvent.OfferRejected)
                }
            }
            TransportEvent.OfferInvalid -> dispatch(SessionEvent.OfferRejected)
            is TransportEvent.Ack -> dispatch(SessionEvent.AckReceived(now(), 0L))
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
        dismissJob = scope.launch {
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

        /** A sender armed to a phone makes its offer again this often (the offer carries a time), and a receiver accepts one this old. */
        const val TARGET_REFRESH_MS = 20_000L
        const val PREFETCH_PAYLOAD_WINDOW_MS = 120_000L

        /** Phases in which Song Tap is already armed (arming again changes nothing). */
        val ARMED_PHASES = setOf(Phase.ARMED, Phase.FOUND, Phase.NEAR, Phase.OFFERED, Phase.TRANSFERRING)

        /** How often the receiver checks whether "Use QR code instead" is due, and how long a song may take to be looked up. */
        const val RING_TICK_MS = 500L
        const val RING_RESOLVE_TIMEOUT_MS = 10_000L
        val ringSecureRandom = java.security.SecureRandom()
    }
}
