package com.theveloper.pixelplay.data.songtap.ble

import com.theveloper.pixelplay.data.songtap.SongTapSigner
import com.theveloper.pixelplay.data.songtap.SongTapTransport
import com.theveloper.pixelplay.data.songtap.SongTapVerifier
import com.theveloper.pixelplay.data.songtap.TransportEvent
import com.theveloper.pixelplay.di.AppScope
import com.theveloper.pixelplay.songtap.DetectorState
import com.theveloper.pixelplay.songtap.FireReason
import com.theveloper.pixelplay.songtap.NearbyBook
import com.theveloper.pixelplay.songtap.NearbyEntry
import com.theveloper.pixelplay.songtap.OfferCheck
import com.theveloper.pixelplay.songtap.PeerTapVerdict
import com.theveloper.pixelplay.songtap.PayloadDecode
import com.theveloper.pixelplay.songtap.RSSI_SCALE
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.RssiSample
import com.theveloper.pixelplay.songtap.SignedOffer
import com.theveloper.pixelplay.songtap.SongTapDetector
import com.theveloper.pixelplay.songtap.SongTapHandshake
import com.theveloper.pixelplay.songtap.SongTapPeerTap
import com.theveloper.pixelplay.songtap.SongTapSensitivity
import com.theveloper.pixelplay.songtap.SongTapTuning
import com.theveloper.pixelplay.songtap.SongTapNames
import com.theveloper.pixelplay.songtap.SongTapPayloads
import com.theveloper.pixelplay.songtap.SongTapSignatureVerifier
import com.theveloper.pixelplay.songtap.SongTapSigning
import com.theveloper.pixelplay.songtap.SongTapWire
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** "No usable signal" for the one-sided tap rule: far below any gate. */
private const val NO_SIGNAL_DBM = -200

/** The numbers of the Bluetooth route that are not the tap detector's (those are `SongTapTuning`, in the core). */
object BleTuning {
    /** How often the housekeeping runs while armed. */
    const val TICK_MS = 250L

    /** A phone not heard from for this long has left. */
    const val PEER_TIMEOUT_MS = 2_500L

    /** The detector follows one phone; another takes over only when it is this many dB stronger. */
    const val FOCUS_SWITCH_DB = 6

    /** After a failed or dropped connection, wait this long before trying again. */
    const val RECONNECT_MS = 1_500L

    /** A connection to a phone that has not been near for this long is closed. */
    const val IDLE_DISCONNECT_MS = 4_000L

    /** The sender may not have its offer ready when the receiver first reads it; ask again this often. */
    const val READ_RETRY_MS = 250L
    const val MAX_READ_ATTEMPTS = 10

    /**
     * A jerk the other phone reports only counts when its (normalised, iPhone scale) signal is at least this strong. The other phone's word
     * cannot be checked (anything that advertises our service can claim it was jerked just now), so it may shorten the hold only for a
     * phone that is close, never for one that is merely in the room. (-62 on the raw scale before spec 6; resting 20 to 30 cm apart is
     * about -60 normalised, touching about -33.)
     */
    const val PEER_IMPULSE_MIN_DBM = -55

    /** At most this many phones are tracked at once; a flood of made-up addresses pushes out the one heard longest ago (never the one being followed). */
    const val MAX_PEERS = 24

    /** A signed frame is the payload plus this many bytes (see `SongTapSigning.encodeFrame`). */
    const val FRAME_OVERHEAD_BYTES = 142

    /** The phone a sender is armed to is followed (and its offer served) even if its advertisements thin out for a while. */
    const val PINNED_PEER_TIMEOUT_MS = 10_000L

    /** A receiver that is connected and near but holds no offer asks again this often (the sender's notification is the fast path). */
    const val PREFETCH_RETRY_MS = 2_000L

    /** A prefetched offer older than this is read again (the signed frame and the payload carry times, the sender refreshes too). */
    const val PREFETCH_MAX_AGE_MS = 20_000L

    /** The nearby list and the prompt are republished at most this often. */
    const val NEARBY_PUBLISH_MS = 250L

    /**
     * The advertising transmit power as `AdvertiseSettings.ADVERTISE_TX_POWER_*`: 1 is LOW (-15 dBm). ULTRA_LOW (0, -21 dBm) is the next step
     * down if phones are still found too easily; the tap near gate (`SongTapTuning.NEAR_ENTER_DBM`, on the iPhone scale after the offset) was calibrated with LOW, so lowering this
     * means calibrating that gate again. Used by `AndroidBleRadio`.
     */
    const val ADVERT_TX_POWER = 1

    /** A connection attempt that has not finished after this long is given up (same as the iPhone), so a stalled one cannot hold the queue. */
    const val CONNECT_TIMEOUT_MS = 3_000L

    /** A tap that is ignored (no yes yet, or not the phone said yes to) is written to the log at most this often. */
    const val TAP_IGNORED_LOG_MS = 2_000L

    /** The "Song drop to X?" prompt stays up this long while the same named phone shows up under a new address. */
    const val PROMPT_GRACE_MS = 3_000L

    /** A recent ignored tap with no yes is shown as a hint on the screens for this long. */
    const val NEEDS_YES_HINT_MS = 15_000L
}

/**
 * Song Tap over Bluetooth LE: finds the other phone, decides they touched, and carries the offer.
 *
 * All decisions live here and in the pure core; the platform is behind [BleRadio] and the accelerometer behind
 * [ImpulseSource], so the whole route is tested over fakes (drop, duplicate, late, cancel, both sending). What
 * the wire looks like is `SongTapWire` in the core, and the iOS port speaks the same.
 *
 * Nothing runs until [start], and [start] is only called while the Song Tap screen is armed in the foreground:
 * no scanning or advertising otherwise, and no sensor either.
 *
 * Spec 5, in short. A receiver connects only to a sender inside the STRICT near gate and, still before any tap, reads the
 * sender's name and its signed offer (when the sender's listener has said yes to that phone), so when the detector fires the
 * data is in hand and the beam starts at the first haptic, with nothing left to wait for. A sender serves the offer to the one
 * phone it was armed to, only while that phone is near, and lists phones for the picker from advertisements alone.
 *
 * Roles. A phone sending advertises the send service and serves the offer; a phone receiving advertises the
 * receive service and connects, as a client, to the sender it is near. A beam therefore always travels one way over
 * one connection. Both phones scan for both services, so each learns the other's role from the advertisement
 * alone.
 */
@Singleton
class BleTransport internal constructor(
    private val radio: BleRadio,
    private val impulses: ImpulseSource,
    private val clock: SongTapClock,
    private val diag: SongTapDiagnostics,
    private val scope: CoroutineScope,
    private val newSigner: () -> SongTapSigner,
    private val verifier: SongTapSignatureVerifier,
    private val random: (ByteArray) -> Unit,
) : SongTapTransport, BleRadioListener {

    @Inject constructor(
        radio: BleRadio,
        impulses: ImpulseSource,
        clock: SongTapClock,
        diag: SongTapDiagnostics,
        @AppScope scope: CoroutineScope,
    ) : this(radio, impulses, clock, diag, scope, { SongTapSigner.create() }, SongTapVerifier, SecureRandom()::nextBytes)

    override val id: String = "ble"

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow(BleStatus.IDLE)

    /** Whether Bluetooth is running, and if not why (the screen words it). */
    val status: StateFlow<BleStatus> = _status.asStateFlow()

    private val _glow = MutableStateFlow(0)

    /** The approach glow, 0..500 permille, from the smoothed signal of the phone being followed. */
    val glow: StateFlow<Int> = _glow.asStateFlow()

    private enum class Conn { NONE, CONNECTING, READY }
    private enum class Write { HELLO, TAP, ACK, DECLINE }

    private class Peer(var id: Int, val address: String) {
        var role = Role.SEND
        var lastSeenMs = 0L
        var lastNearMs = 0L
        /** The last raw reading, and the same shifted by the rssi offset (the iPhone scale every gate and the detector use). */
        var rssi = 0
        var rssiNorm = 0
        var detector = DetectorState.INITIAL
        var nearReported = false
        var glow = 0
        var peerImpulseLocalMs = -1L
        var infoAskedAtMs = 0L
        var conn = Conn.NONE
        var connectStartMs = 0L
        /** A connection attempt was given up on; a late answer from it must not be taken for the next one. */
        var staleConnect = false
        var nextConnectAtMs = 0L
        var challenge: ByteArray? = null
        val writes = ArrayDeque<Write>()
        var ackAtMs = 0L
        var rttMs = 0L
        var wantRead = false
        var readInFlight = false
        var readAttempts = 0
        var inTransfer = false
        var lastFire = FireReason.NONE
        var lastFireMs = -1L
        var lastJumpMs = -1L

        /** The short name this phone advertised, and its full name once read from its Name characteristic. */
        var name: String? = null
        var fullName: String? = null
        var nameAsked = false

        /** Receiver: the offer read and checked before any tap, and when; null when none. */
        var prefetched: Prefetch? = null
        var lastPrefetchTryMs = 0L
    }

    private class Prefetch(val payload: ByteArray, val senderKey: Int, val atMs: Long)

    private val lock = Any()
    private var started = false
    private var role = Role.SEND
    private var ticker: Job? = null
    private var nextPeerId = 1
    private val peers = HashMap<String, Peer>()
    private val byId = HashMap<Int, Peer>()
    private var focus: Peer? = null
    private var ownImpulseMs = -1L

    /** The name this phone shows other phones (set by the controller before arming). */
    @Volatile private var ownName = SongTapNames.FALLBACK

    /**
     * Added to every raw reading before the detector, the gates and the nearby book (spec 6), so this phone reads on the iPhone's scale.
     * 0 until the controller sets it from the settings (a Pixel: -15).
     */
    @Volatile private var rssiOffsetDb = 0
    @Volatile private var profile = SongTapSensitivity.DEFAULT

    /** When this phone last fired itself or took a peer's tap, and when it last took a peer's tap (for no echo). -1 = never. */
    private var lastTapMs = -1L
    private var lastPeerTapMs = -1L

    private var calibStartMs = -1L
    private val calibSamples = ArrayList<Int>()
    private val _calibration = MutableStateFlow<CalibrationState>(CalibrationState.Idle)

    /** What the Calibrate button is doing (diagnostics screen). */
    val calibration: StateFlow<CalibrationState> = _calibration.asStateFlow()

    /** Sender: the phone the listener said yes to (the transport's number), 0 when none. */
    private var pinnedId = 0

    /** How often the radio was started since [start], and the name and role it runs with (a second start with the same ones changes nothing). */
    private var radioStarts = 0
    private var startedName = ""

    /** Sender: the last tap that was ignored because there was no yes (or it was not the phone said yes to), for the log and the diagnostics hint. */
    private var ignoredTaps = 0
    private var ignoredTapMs = -1L
    private var ignoredTapNoYes = true
    private var lastIgnoredLogMs = -1_000_000L

    /** Names that were told no, and until when: a phone that comes back under a new address with the same name stays silenced. */
    private val ignoredNames = HashMap<String, Long>()

    /** The prompt last shown, so the same named phone changing its address does not make it vanish and come back. */
    private var lastPrompt: NearbyEntry? = null
    private var lastPromptMs = -1L

    private val book = NearbyBook()
    private var lastNearbyMs = -1_000_000L
    private val _nearby = MutableStateFlow<List<NearbyEntry>>(emptyList())

    /** Sender: the phones advertising as receivers inside the list gate, strongest first, from advertisements only (spec 5). */
    val nearby: StateFlow<List<NearbyEntry>> = _nearby.asStateFlow()

    private val _prompt = MutableStateFlow<NearbyEntry?>(null)

    /** Sender: the phone to ask "Song drop to X?" about (inside the tap near gate, not told no), or null. */
    val prompt: StateFlow<NearbyEntry?> = _prompt.asStateFlow()

    // The sender's side. Three separate facts about who is talking to our GATT server, because a control write is only
    // believed from the phone it is about: [tapClient] wrote the tap message, [offerClient] read the offer, [ackClient]
    // acknowledged it. An acknowledgement or a decline from any other connection is ignored, and Go goes to [ackClient].
    private var signer: SongTapSigner? = null
    private var offer: ByteArray? = null
    private val challenges = HashMap<String, ByteArray>()
    private var tapClient: String? = null
    private var offerClient: String? = null
    private var ackClient: String? = null

    /** Sets the name shown to other phones; it takes effect the next time the transport starts. */
    fun setOwnName(name: String) {
        ownName = SongTapNames.orFallback(name)
    }

    /**
     * Sets the shift added to every raw reading (spec 6). Live: the detectors restart from the next reading, because what they remember
     * (baseline, smoothed level) was on the old scale.
     */
    fun setRssiOffset(db: Int) {
        synchronized(lock) {
            val clamped = RssiCalibration.clamp(db)
            if (clamped == rssiOffsetDb) return
            rssiOffsetDb = clamped
            for (p in peers.values) {
                p.detector = DetectorState.INITIAL.copy(lastFireMs = p.detector.lastFireMs)
                p.nearReported = false
            }
            diag.log(clock.elapsedMs(), "signal offset set to $clamped dB")
        }
    }

    fun rssiOffset(): Int = rssiOffsetDb

    /** Sets how sensitive the tap is (spec 6). Live: the next reading and the next jerk already use it. */
    fun setSensitivity(value: SongTapSensitivity) {
        synchronized(lock) {
            if (value == profile) return
            profile = value
            impulses.setSensitivity(value)
            diag.log(clock.elapsedMs(), "sensitivity set to ${value.code}")
        }
    }

    fun sensitivity(): SongTapSensitivity = profile

    // ── Calibration (diagnostics: phones touching, button held) ──────────────────────

    /**
     * The Calibrate button went down with the phones touching: for [RssiCalibration.HOLD_MS] the smoothed raw readings of the phone being
     * followed are collected; then offset = -(their 90th percentile - [RssiCalibration.TOUCH_REFERENCE_DBM]), clamped, is set live and
     * [calibration] says Done (the controller saves it). Letting go earlier cancels.
     */
    fun startCalibration() {
        synchronized(lock) {
            val f = focus
            if (!started || f == null) {
                _calibration.value = CalibrationState.Failed("no_phone")
                return
            }
            calibStartMs = clock.elapsedMs()
            calibSamples.clear()
            _calibration.value = CalibrationState.Running(0L, 0)
            diag.log(calibStartMs, "calibrating: keep the phones touching")
        }
    }

    /** The button went up: a calibration that has not finished is dropped. */
    fun endCalibration() {
        synchronized(lock) {
            if (calibStartMs < 0L) return
            calibStartMs = -1L
            calibSamples.clear()
            _calibration.value = CalibrationState.Failed("released")
        }
    }

    fun clearCalibration() {
        synchronized(lock) {
            calibStartMs = -1L
            calibSamples.clear()
            _calibration.value = CalibrationState.Idle
        }
    }

    /** One smoothed reading of the followed phone while calibrating (called with the lock held). */
    private fun calibrationSample(t: Long, smoothedNormDbm: Int) {
        if (calibStartMs < 0L) return
        calibSamples.add(smoothedNormDbm - rssiOffsetDb)
        calibrationProgress(t)
    }

    private fun calibrationProgress(t: Long) {
        if (calibStartMs < 0L) return
        val held = t - calibStartMs
        if (held < RssiCalibration.HOLD_MS) {
            _calibration.value = CalibrationState.Running(held, calibSamples.size)
            return
        }
        val samples = calibSamples.size
        val p90 = RssiCalibration.percentile90(calibSamples)
        calibStartMs = -1L
        calibSamples.clear()
        if (p90 == null || samples < RssiCalibration.MIN_SAMPLES) {
            _calibration.value = CalibrationState.Failed("too_few")
            diag.log(t, "calibration failed: only $samples readings")
            return
        }
        val offset = RssiCalibration.offsetFor(p90)
        _calibration.value = CalibrationState.Done(offset, p90, samples)
        diag.log(t, "calibrated: touching reads $p90 dBm raw, offset $offset dB ($samples readings)")
        if (offset != rssiOffsetDb) {
            rssiOffsetDb = offset
            for (p in peers.values) {
                p.detector = DetectorState.INITIAL.copy(lastFireMs = p.detector.lastFireMs)
                p.nearReported = false
            }
        }
    }

    /** "No" on the prompt: that phone is not asked about for a minute. */
    fun ignorePrompt(peer: Int) {
        synchronized(lock) {
            val t = clock.elapsedMs()
            book.ignore(peer, t)
            // A phone that changes its address is still the same phone: every number it had, and any it takes later, stays silenced.
            val name = byId[peer]?.name
            if (name != null) {
                ignoredNames[name] = t + NearbyBook.IGNORE_MS
                for (q in peers.values) if (q.role == Role.RECEIVE && q.name == name) book.ignore(q.id, t)
            }
            lastPrompt = null
            publishNearbyLocked(t, force = true)
        }
    }

    /** The name of [peer] for a card: its full name when it gave one, else the short one it advertised. */
    fun nameOf(peer: Int): String? = synchronized(lock) { byId[peer]?.let { it.fullName ?: it.name } }

    private fun emit(e: TransportEvent) {
        _events.tryEmit(e)
    }

    private fun setStatus(s: BleStatus) {
        _status.value = s
    }

    // ── SongTapTransport ──────────────────────────────────────────────────────────────

    override fun start(role: Role) {
        synchronized(lock) { startLocked(role) }
    }

    override fun stop() {
        synchronized(lock) {
            if (started) diag.log(clock.elapsedMs(), "disarmed")
            stopLocked()
        }
    }

    /** The permission or Bluetooth state changed while armed: try again. */
    fun restart() {
        synchronized(lock) {
            val s = _status.value
            if (started && (s == BleStatus.NO_PERMISSION || s == BleStatus.BLUETOOTH_OFF || s == BleStatus.UNSUPPORTED)) startLocked(role)
        }
    }

    /** The session is back to waiting: say again which phone is close, so it can be tapped. */
    fun resync() {
        synchronized(lock) {
            val f = focus ?: return
            emit(TransportEvent.PeerFound(f.id))
            if (f.detector.near) emit(TransportEvent.PeerNear(f.id))
            f.nearReported = f.detector.near
        }
    }

    private fun roleWord(r: Role) = if (r == Role.SEND) "send" else "receive"

    private fun startLocked(newRole: Role) {
        // Already running as this role, with this name: starting again would only stop and start the advertising for nothing
        // (and a phone that restarts its advertising shows up under a new address every time).
        if (started && role == newRole && startedName == ownName && _status.value == BleStatus.ACTIVE) {
            diag.log(clock.elapsedMs(), "radio already running (${roleWord(newRole)}), not restarted")
            return
        }
        if (started) stopLocked()
        role = newRole
        started = true
        startedName = ownName
        signer = if (newRole == Role.SEND) newSigner() else null
        radioStarts++
        diag.log(clock.elapsedMs(), "advertising start (${roleWord(newRole)}), start number $radioStarts")
        val result = radio.start(newRole, this, ownName)
        setStatus(result)
        if (result != BleStatus.ACTIVE) {
            diag.log(clock.elapsedMs(), "bluetooth unavailable: ${result.name.lowercase()}")
            return
        }
        beginActiveLocked()
    }

    /** The radio is running: listen to the accelerometer and start the housekeeping. */
    private fun beginActiveLocked() {
        if (ticker != null) return
        impulses.start(::onImpulse)
        impulses.setSensitivity(profile)
        ticker = scope.launch {
            while (isActive) {
                delay(BleTuning.TICK_MS)
                synchronized(lock) { if (started) tickLocked() }
            }
        }
        diag.log(clock.elapsedMs(), "armed to ${if (role == Role.SEND) "send" else "receive"}")
    }

    private fun stopLocked() {
        if (started) diag.log(clock.elapsedMs(), "advertising stop (${roleWord(role)})")
        started = false
        ticker?.cancel()
        ticker = null
        impulses.stop()
        radio.stop()
        peers.clear()
        byId.clear()
        focus = null
        signer = null
        offer = null
        challenges.clear()
        tapClient = null
        offerClient = null
        ackClient = null
        pinnedId = 0
        book.clear()
        ignoredNames.clear()
        lastPrompt = null
        clearIgnoredTapLocked()
        _nearby.value = emptyList()
        _prompt.value = null
        calibStartMs = -1L
        calibSamples.clear()
        _calibration.value = CalibrationState.Idle
        lastTapMs = -1L
        lastPeerTapMs = -1L
        ownImpulseMs = -1L
        _glow.value = 0
        setStatus(BleStatus.IDLE)
    }

    // ── Sender ────────────────────────────────────────────────────────────────────────

    override fun publishOffer(peer: Int, payload: ByteArray) {
        synchronized(lock) {
            if (!started || role != Role.SEND) return
            offer = fit(payload)
            // A new offer starts with nobody having read or acknowledged it: a late answer to the last one is not an answer to this.
            offerClient = null
            ackClient = null
            tapClient = null
            val first = pinnedId != peer
            pinnedId = peer
            if (peer != 0) clearIgnoredTapLocked()
            val target = byId[peer]
            if (first && target != null) setFocus(target, clock.elapsedMs())
            diag.log(clock.elapsedMs(), if (offer == null) "offer does not fit one frame" else "offer ready for #$peer (${payload.size} bytes)")
            // The phones already connected (they said Hello) are told there is something to read; others read when they connect.
            if (offer != null) for (client in challenges.keys.toList()) radio.notify(client, SongTapWire.encodeOfferReady())
            publishNearbyLocked(clock.elapsedMs(), force = true)
        }
    }

    override fun withdrawOffer() {
        synchronized(lock) {
            // A receiver that fetched the offer ahead of the tap must not keep it once the sender has let go of it.
            if (offer != null) for (client in challenges.keys.toList()) radio.notify(client, SongTapWire.encodeOfferWithdrawn())
            offer = null
            offerClient = null
            ackClient = null
            tapClient = null
            val wasPinned = pinnedId != 0
            pinnedId = 0
            if (wasPinned && started) publishNearbyLocked(clock.elapsedMs(), force = true)
        }
    }

    /** A signed frame has to fit one attribute: drop the optional parts, art first, until it does. */
    private fun fit(payload: ByteArray): ByteArray? {
        fun fits(b: ByteArray) = b.size + BleTuning.FRAME_OVERHEAD_BYTES <= SongTapSigning.MAX_FRAME_BYTES
        if (fits(payload)) return payload
        val decoded = (SongTapPayloads.decode(payload) as? PayloadDecode.Ok)?.payload ?: return null
        var p = decoded.copy(artUrl = null)
        SongTapPayloads.encode(p)?.let { if (fits(it)) return it }
        p = p.copy(isrc = null)
        SongTapPayloads.encode(p)?.let { if (fits(it)) return it }
        if (p.ytVideoId != null) {
            p = p.copy(spotifyId = null)
            SongTapPayloads.encode(p)?.let { if (fits(it)) return it }
        }
        return null
    }

    // Server callbacks (a receiver talking to our GATT server).

    override fun serverInfo(): ByteArray = synchronized(lock) {
        SongTapWire.encodeInfo(SongTapHandshake.impulseAgeMs(clock.elapsedMs(), ownImpulseMs))
    }

    override fun serverName(): ByteArray = SongTapWire.encodeName(ownName)

    override fun serverOffer(client: String): ByteArray? = synchronized(lock) {
        if (!started || role != Role.SEND) return null
        val payload = offer ?: return null
        // Spec 5: the offer exists only for the phone the listener said yes to, and is served only while that phone is the one being
        // followed AND inside the tap near gate, and no other phone that could be the reader is inside it too (a connection's own
        // address is not the advertising address, so "which phone is this client" cannot be known: when two are close, serve nobody).
        val target = byId[pinnedId] ?: return null
        val f = focus
        if (f !== target || !target.detector.near) return null
        if (anotherReceiverNear(target)) return null
        // Once somebody has read it, only that reader is given it again.
        val reader = offerClient
        if (reader != null && reader != client) return null
        val challenge = challenges[client] ?: return null
        val key = signer ?: return null
        val ts = clock.wallMs()
        val message = SongTapSigning.signingBytes(payload, challenge, ts) ?: return null
        val frame = SongTapSigning.encodeFrame(SignedOffer(payload, ts, key.publicKey, key.sign(message)))
        // A challenge answers one read: the receiver sends a new one for the next beam.
        challenges.remove(client)
        if (frame != null) {
            offerClient = client
            emit(TransportEvent.OfferRead(target.id))
        }
        diag.log(clock.elapsedMs(), if (frame == null) "frame too big" else "offer read by a receiver (${frame.size} bytes)")
        frame
    }

    /** True when a phone other than [target] that advertises as a receiver is inside the tap near gate right now. */
    private fun anotherReceiverNear(target: Peer): Boolean {
        val t = clock.elapsedMs()
        val targetName = target.name
        for (q in peers.values) {
            if (q === target || q.role != Role.RECEIVE) continue
            // The same name is the same phone under another address (it changes every few seconds): never a second reader.
            if (targetName != null && q.name == targetName) continue
            if (t - q.lastSeenMs <= BleTuning.PEER_TIMEOUT_MS && q.detector.near) return true
        }
        return false
    }

    override fun serverControl(client: String, value: ByteArray): Boolean = synchronized(lock) {
        if (!started || role != Role.SEND) return false
        val message = SongTapWire.decodeControl(value) ?: return false
        val t = clock.elapsedMs()
        when (message) {
            is SongTapWire.ControlMessage.Hello -> challenges[client] = message.challenge
            SongTapWire.ControlMessage.Ack -> {
                if (offer == null || client != offerClient) {
                    diag.log(t, "ack from a phone that did not read the offer, ignored")
                } else {
                    ackClient = client
                    diag.log(t, "ack from receiver")
                    focus?.let { emit(TransportEvent.Ack(it.id)) }
                }
            }
            SongTapWire.ControlMessage.Decline -> {
                if (offer == null || client != offerClient) {
                    diag.log(t, "decline from a phone that did not read the offer, ignored")
                } else {
                    diag.log(t, "declined by receiver")
                    focus?.let { emit(TransportEvent.Declined(it.id)) }
                }
            }
            is SongTapWire.ControlMessage.Tap -> {
                val f = focus
                // One-sided tap (spec 6): the receiver felt the tap first and says so; this phone commits at once, without waiting for its own
                // detector, when [SongTapPeerTap.judge] accepts: the message is from the phone being followed (the one the listener said yes to),
                // it is the reader of this offer, this phone's own normalised signal for it is at least the tap near gate, and nothing was taken lately.
                val followed = f != null && f.id == pinnedId && f.role == Role.RECEIVE
                val saidYes = offer != null && client == offerClient
                val own = if (f == null || !f.detector.started) NO_SIGNAL_DBM else dbm(f.detector.fastQ)
                val verdict = SongTapPeerTap.judge(own, followed, saidYes, t, lastTapMs)
                diag.log(t, "receiver reports a tap (jerk ${message.ageMs} ms ago): ${verdict.code}, own signal $own dBm")
                if (verdict == PeerTapVerdict.ACCEPT && f != null) {
                    lastTapMs = t
                    lastPeerTapMs = t
                    tapClient = client
                    emit(TransportEvent.Tap(f.id))
                }
            }
        }
        true
    }

    override fun serverClientGone(client: String) {
        synchronized(lock) {
            challenges.remove(client)
            val acked = ackClient == client
            val read = offerClient == client
            if (acked) ackClient = null
            if (read) offerClient = null
            if (tapClient == client) tapClient = null
            // A reader that leaves before the beam is only a read lost (the session stays armed to that phone); in the middle of a
            // beam, before its answer, it is a lost peer; after its answer it is nothing.
            if (read && !acked && offer != null) {
                val target = byId[pinnedId]
                if (target != null) emit(TransportEvent.OfferReadLost(target.id))
                focus?.let { if (it.id == pinnedId) emit(TransportEvent.PeerLost(it.id)) }
            }
        }
    }

    // ── Receiver ──────────────────────────────────────────────────────────────────────

    override fun readOffer(peer: Int) {
        synchronized(lock) {
            val p = byId[peer]
            if (p == null || role != Role.RECEIVE || p.role != Role.SEND) {
                emit(TransportEvent.OfferInvalid)
                return
            }
            // The offer was fetched before the tap: hand it over now (this is the fast path; the session normally already holds it).
            val held = p.prefetched
            if (held != null) {
                p.prefetched = null
                emit(TransportEvent.Offer(p.id, held.payload, held.senderKey, p.fullName ?: p.name))
                return
            }
            p.inTransfer = true
            p.wantRead = true
            p.readAttempts = 0
            when (p.conn) {
                Conn.READY -> startRead(p)
                Conn.NONE -> connect(p, clock.elapsedMs())
                Conn.CONNECTING -> Unit
            }
        }
    }

    private fun startRead(p: Peer) {
        p.wantRead = false
        p.readInFlight = true
        radio.readOffer(p.address)
    }

    override fun sendAck(peer: Int) {
        synchronized(lock) {
            val p = byId[peer]
            if (p == null || p.conn != Conn.READY) { emit(TransportEvent.PeerLost(peer)); return }
            queueWrite(p, Write.ACK, SongTapWire.encodeAck())
            // The beam is saved and acknowledged: this phone is free again (nothing waits for a `go` any more).
            p.inTransfer = false
            p.prefetched = null
        }
    }

    override fun sendDecline(peer: Int) {
        synchronized(lock) {
            val p = byId[peer] ?: return
            p.inTransfer = false
            p.prefetched = null
            if (p.conn == Conn.READY) queueWrite(p, Write.DECLINE, SongTapWire.encodeDecline())
        }
    }

    override fun abort(peer: Int) {
        synchronized(lock) {
            if (role == Role.SEND) {
                (ackClient ?: offerClient)?.let { radio.dropClient(it) }
                return
            }
            val p = byId[peer] ?: return
            p.inTransfer = false
            p.wantRead = false
            p.readInFlight = false
            p.prefetched = null
            if (p.conn != Conn.NONE) {
                radio.disconnect(p.address)
                p.conn = Conn.NONE
                p.writes.clear()
                p.challenge = null
                p.nextConnectAtMs = clock.elapsedMs() + BleTuning.RECONNECT_MS
            }
        }
    }

    private fun queueWrite(p: Peer, kind: Write, bytes: ByteArray) {
        p.writes.addLast(kind)
        if (kind == Write.ACK) p.ackAtMs = clock.elapsedMs()
        radio.writeControl(p.address, bytes)
    }

    private fun sendHello(p: Peer) {
        val challenge = ByteArray(SongTapSigning.CHALLENGE_BYTES)
        random(challenge)
        p.challenge = challenge
        queueWrite(p, Write.HELLO, SongTapWire.encodeHello(challenge) ?: return)
    }

    private fun connect(p: Peer, t: Long) {
        p.conn = Conn.CONNECTING
        p.connectStartMs = t
        p.staleConnect = false
        radio.connect(p.address)
        diag.log(t, "connecting to #${p.id}")
    }

    private fun failTransfer(p: Peer) {
        p.inTransfer = false
        p.wantRead = false
        emit(TransportEvent.PeerLost(p.id))
    }

    override fun onConnected(address: String, ok: Boolean) {
        synchronized(lock) {
            val t = clock.elapsedMs()
            val p = peers[address]
            if (p == null || !started) { radio.disconnect(address); return }
            // An answer to an attempt that was already given up on (timeout): close it, it is not the connection being waited for.
            if (p.staleConnect && p.conn != Conn.CONNECTING) {
                p.staleConnect = false
                if (ok) radio.disconnect(address)
                return
            }
            if (!ok) {
                p.conn = Conn.NONE
                p.nextConnectAtMs = t + BleTuning.RECONNECT_MS
                diag.log(t, "connection to #${p.id} failed")
                if (p.inTransfer) failTransfer(p)
                return
            }
            p.conn = Conn.READY
            diag.log(t, "connected to #${p.id}")
            sendHello(p)
            // Spec 5: everything slow happens now, before any tap: the sender's name, then its offer (when it has one for us).
            if (!p.nameAsked) { p.nameAsked = true; radio.readName(p.address) }
            if (p.wantRead) startRead(p) else startPrefetch(p, t)
        }
    }

    override fun onDisconnected(address: String) {
        synchronized(lock) {
            val p = peers[address] ?: return
            val t = clock.elapsedMs()
            p.conn = Conn.NONE
            p.writes.clear()
            p.readInFlight = false
            p.challenge = null
            p.nextConnectAtMs = t + BleTuning.RECONNECT_MS
            diag.log(t, "disconnected from #${p.id}")
            dropPrefetch(p)
            p.nameAsked = false
            if (p.inTransfer) failTransfer(p)
        }
    }

    override fun onInfoRead(address: String, value: ByteArray?) {
        synchronized(lock) {
            val p = peers[address] ?: return
            val t = clock.elapsedMs()
            val age = value?.let { SongTapWire.decodeInfo(it) } ?: -1L
            p.peerImpulseLocalMs = SongTapHandshake.peerImpulseLocalMs(t, age, t - p.infoAskedAtMs)
            diag.log(t, "sender's last jerk ${if (age < 0) "none" else "$age ms ago"}")
        }
    }

    override fun onOfferRead(address: String, value: ByteArray?) {
        synchronized(lock) {
            val p = peers[address] ?: return
            p.readInFlight = false
            val t = clock.elapsedMs()
            if (value == null) {
                if (!p.inTransfer) {
                    // A prefetch with nothing to give: the sender's notification (or the retry) comes later, and an offer held from before is no good now.
                    dropPrefetch(p)
                    return
                }
                p.readAttempts++
                if (p.readAttempts < BleTuning.MAX_READ_ATTEMPTS) {
                    scope.launch {
                        delay(BleTuning.READ_RETRY_MS)
                        synchronized(lock) {
                            if (started && p.inTransfer && p.conn == Conn.READY && !p.readInFlight) startRead(p)
                        }
                    }
                } else {
                    diag.log(t, "the sender had no offer")
                    p.inTransfer = false
                    emit(TransportEvent.OfferInvalid)
                    if (p.conn == Conn.READY) sendHello(p)
                }
                return
            }
            val challenge = p.challenge
            val frame = SongTapSigning.decodeFrame(value)
            val verdict = if (frame == null) OfferCheck.BAD_FRAME
            else if (challenge == null) OfferCheck.BAD_FRAME
            else SongTapSigning.check(frame, challenge, clock.wallMs(), verifier)
            p.challenge = null
            if (p.conn == Conn.READY) sendHello(p)
            if (verdict == OfferCheck.OK && frame != null) {
                val name = p.fullName ?: p.name
                if (p.inTransfer) {
                    diag.log(t, "offer read and checked")
                    emit(TransportEvent.Offer(p.id, frame.payload, SongTapSigning.senderKey(frame.publicKey), name))
                } else {
                    p.prefetched = Prefetch(frame.payload, SongTapSigning.senderKey(frame.publicKey), t)
                    diag.log(t, "offer fetched ahead of the tap and checked (${frame.payload.size} bytes)")
                    emit(TransportEvent.OfferPrefetched(p.id, frame.payload, SongTapSigning.senderKey(frame.publicKey), name))
                }
            } else {
                diag.log(t, "offer refused: ${verdict.code}")
                if (p.inTransfer) {
                    p.inTransfer = false
                    emit(TransportEvent.OfferInvalid)
                } else {
                    dropPrefetch(p)
                }
            }
        }
    }

    /** Receiver: ask the sender for its offer ahead of the tap (nothing happens when it has none yet). */
    private fun startPrefetch(p: Peer, t: Long) {
        if (role != Role.RECEIVE || p.role != Role.SEND || p.conn != Conn.READY || p.readInFlight || p.inTransfer) return
        p.lastPrefetchTryMs = t
        p.readInFlight = true
        radio.readOffer(p.address)
    }

    /** Receiver: whatever was fetched ahead is no longer good. */
    private fun dropPrefetch(p: Peer) {
        if (p.prefetched != null) {
            p.prefetched = null
            emit(TransportEvent.OfferGone(p.id))
        }
    }

    override fun onNameRead(address: String, value: ByteArray?) {
        synchronized(lock) {
            val p = peers[address] ?: return
            val name = value?.let { SongTapWire.decodeName(it) } ?: return
            p.fullName = name
            diag.log(clock.elapsedMs(), "#${p.id} is called $name")
            emit(TransportEvent.PeerName(p.id, name))
        }
    }

    override fun onControlWritten(address: String, ok: Boolean) {
        synchronized(lock) {
            val p = peers[address] ?: return
            val kind = p.writes.removeFirstOrNull() ?: return
            val t = clock.elapsedMs()
            if (ok) {
                if (kind == Write.ACK) p.rttMs = t - p.ackAtMs
                return
            }
            diag.log(t, "write ${kind.name.lowercase()} failed")
            if (kind == Write.ACK || kind == Write.DECLINE || kind == Write.HELLO) {
                if (p.inTransfer) failTransfer(p)
            }
        }
    }

    override fun onNotified(address: String, value: ByteArray) {
        synchronized(lock) {
            val p = peers[address] ?: return
            // One-sided tap (spec 6): the sender's detector fired and it says so. This phone commits at once, without waiting for its own
            // detector, only when [SongTapPeerTap.judge] accepts it: the notification is from the sender being followed, an offer fetched ahead
            // is in hand (the sender said yes), this phone's own normalised signal for the sender is at least the tap near gate, nothing was taken lately.
            if (SongTapWire.isTapEvent(value)) {
                val t = clock.elapsedMs()
                val followed = role == Role.RECEIVE && p === focus && p.role == Role.SEND && p.conn == Conn.READY
                val saidYes = p.prefetched != null
                val own = if (!p.detector.started) NO_SIGNAL_DBM else dbm(p.detector.fastQ)
                val verdict = SongTapPeerTap.judge(own, followed, saidYes, t, lastTapMs)
                diag.log(t, "the sender reports a tap: ${verdict.code}, own signal $own dBm")
                if (verdict == PeerTapVerdict.ACCEPT) {
                    lastTapMs = t
                    lastPeerTapMs = t
                    emit(TransportEvent.Tap(p.id))
                    // The session holds the offer from here on, as after this phone's own tap.
                    p.prefetched = null
                }
                return
            }
            // The events are "the sender has an offer to read" and "the sender has none any more" (EVENT_GO of spec 4 is gone and ignored).
            if (SongTapWire.isOfferWithdrawn(value)) {
                diag.log(clock.elapsedMs(), "the sender withdrew its offer")
                dropPrefetch(p)
                return
            }
            if (!SongTapWire.isOfferReady(value)) return
            diag.log(clock.elapsedMs(), "the sender has an offer ready")
            if (p.detector.near) startPrefetch(p, clock.elapsedMs())
        }
    }

    override fun onStatus(status: BleStatus) {
        synchronized(lock) {
            if (!started) return
            if (status == BleStatus.ACTIVE) {
                setStatus(status)
                // Bluetooth was off when Song Tap armed and has come back on.
                beginActiveLocked()
                return
            }
            // Bluetooth went away under us: whatever was in flight is lost.
            val f = focus
            if (f != null) emit(TransportEvent.PeerLost(f.id))
            peers.clear()
            byId.clear()
            focus = null
            offer = null
            challenges.clear()
            ackClient = null
            offerClient = null
            tapClient = null
            pinnedId = 0
            book.clear()
            _nearby.value = emptyList()
            _prompt.value = null
            _glow.value = 0
            ticker?.cancel()
            ticker = null
            impulses.stop()
            setStatus(status)
            diag.log(clock.elapsedMs(), "bluetooth status: ${status.name.lowercase()}")
        }
    }

    // ── Detection ─────────────────────────────────────────────────────────────────────

    override fun onSeen(address: String, role: Role, rssi: Int, name: String?) {
        synchronized(lock) {
            if (!started) return
            val t = clock.elapsedMs()
            var p = peers[address]
            if (p == null) {
                if (peers.size >= BleTuning.MAX_PEERS) evictOldestLocked()
                p = Peer(nextPeerId++, address)
                peers[address] = p
                byId[p.id] = p
                diag.log(t, "phone #${p.id} appears, ${if (role == Role.SEND) "sending" else "receiving"}")
            }
            p.role = role
            p.lastSeenMs = t
            p.rssi = rssi
            // Everything from here on (smoothing, baseline, gates, the list, the detector) is on the iPhone's scale: the raw reading plus the offset.
            val norm = rssi + rssiOffsetDb
            p.rssiNorm = norm
            if (name != null && name != p.name) {
                p.name = name
                if (p.fullName == null) emit(TransportEvent.PeerName(p.id, name))
            }
            if (this.role == Role.SEND && role == Role.RECEIVE) {
                // A name that was told no stays told no under a new address (the silence lasts as long as it was given for).
                val n = p.name
                val until = if (n == null) null else ignoredNames[n]
                if (until != null) {
                    if (until > t) { if (!book.isIgnored(p.id, t)) book.ignore(p.id, until - NearbyBook.IGNORE_MS) } else ignoredNames.remove(n)
                }
                if (pinnedId != 0 && p.id != pinnedId) followSamePhoneLocked(p, t)
            }

            val impulse = impulseFor(p, ownImpulseMs)
            val wasHeld = p.detector.heldSinceMs >= 0L
            val step = SongTapDetector.step(p.detector, RssiSample(t, norm), impulse, profile)
            p.detector = step.state
            p.glow = step.glowPermille
            if (step.state.near) p.lastNearMs = t
            val jumping = step.state.heldSinceMs >= 0L
            if (jumping || step.fire != FireReason.NONE) p.lastJumpMs = t
            if (jumping && !wasHeld) onJumpStart(p, t)

            book.update(p.id, p.fullName ?: p.name ?: SongTapNames.FALLBACK, p.role, step.state.fastQ, step.state.near, t)
            publishNearbyLocked(t, force = false)
            updateFocus(t, p)
            if (p === focus) {
                _glow.value = p.glow
                if (step.state.near != p.nearReported) {
                    p.nearReported = step.state.near
                    emit(if (step.state.near) TransportEvent.PeerNear(p.id) else TransportEvent.PeerFar(p.id))
                    diag.log(t, if (step.state.near) "#${p.id} is near" else "#${p.id} moved away")
                }
                diag.sample(t, rssi, dbm(step.state.fastQ), dbm(step.state.baselineQ))
                calibrationSample(t, dbm(step.state.fastQ))
            }
            if (step.fire != FireReason.NONE) onFire(p, step.fire, t)
            maybeConnect(p, t)
            if (diag.isEnabled()) diag.publish(t) { snapshotLocked(t) }
        }
    }

    private fun dbm(q: Int): Int = SongTapDetector.floorDiv(q, RSSI_SCALE)

    /** The latest jerk the detector may use for [p]: this phone's own, or the peer's word when it is close enough to be believed. */
    private fun impulseFor(p: Peer, own: Long): Long {
        val peer = p.peerImpulseLocalMs
        if (own >= peer) return own
        val close = p.detector.started && dbm(p.detector.fastQ) >= BleTuning.PEER_IMPULSE_MIN_DBM
        return if (close) peer else own
    }

    private fun strength(p: Peer): Int = dbm(p.detector.fastQ)

    /** The jump has started: a receiver asks the sender how long ago it was jerked. */
    private fun onJumpStart(p: Peer, t: Long) {
        diag.log(t, "signal of #${p.id} jumped")
        if (role == Role.RECEIVE && p.role == Role.SEND && p.conn == Conn.READY) {
            p.infoAskedAtMs = t
            radio.readInfo(p.address)
        }
    }

    private fun updateFocus(t: Long, seen: Peer) {
        val current = focus
        if (current === seen) return
        // A sender armed to a phone follows that phone and nobody else.
        if (pinnedId != 0) {
            if (seen.id == pinnedId) setFocus(seen, t)
            return
        }
        if (current != null) {
            val busy = current.inTransfer || offer != null
            val gone = t - current.lastSeenMs > BleTuning.PEER_TIMEOUT_MS
            if (busy && !gone) return
            if (!gone && strength(seen) < strength(current) + BleTuning.FOCUS_SWITCH_DB) return
        }
        setFocus(seen, t)
    }

    private fun setFocus(next: Peer?, t: Long) {
        val old = focus
        if (old === next) return
        if (old != null) {
            emit(TransportEvent.PeerLost(old.id))
            dropPrefetch(old)
            if (old.conn != Conn.NONE) {
                radio.disconnect(old.address)
                old.conn = Conn.NONE
                old.writes.clear()
            }
        }
        focus = next
        if (next == null) { _glow.value = 0; return }
        next.nearReported = next.detector.near
        _glow.value = next.glow
        emit(TransportEvent.PeerFound(next.id))
        if (next.nearReported) emit(TransportEvent.PeerNear(next.id))
        diag.log(t, "following #${next.id}")
    }

    private fun onFire(p: Peer, reason: FireReason, t: Long) {
        // A sender armed to a phone has no business with a stranger's tap: it does not even follow it.
        if (pinnedId != 0 && p.id != pinnedId) {
            if (role == Role.SEND && p.role == Role.RECEIVE) noteIgnoredTapLocked(t, noYes = false)
            return
        }
        if (p !== focus) setFocus(p, t)
        p.lastFire = reason
        p.lastFireMs = t
        diag.log(t, "tap detected (${reason.code}) with #${p.id}${if (p.prefetched != null) ", offer already in hand" else ""}")
        emit(TransportEvent.PeerRole(p.id, p.role))
        if (role == Role.RECEIVE && p.role == Role.RECEIVE) return
        // The phone that is not the one the listener said yes to cannot start a beam (the session ignores it too): say so, never silently.
        if (role == Role.SEND && p.id != pinnedId) {
            if (p.role == Role.RECEIVE) noteIgnoredTapLocked(t, noYes = true)
            return
        }
        // This phone already committed on the other phone's tap a moment ago: the session ignores a second tap, and nothing is sent back.
        val echo = lastPeerTapMs >= 0L && t - lastPeerTapMs < SongTapPeerTap.MIN_GAP_MS
        lastTapMs = t
        emit(TransportEvent.Tap(p.id))
        // The session holds the offer from here on (it took it when the fetch finished); the next beam needs a new one.
        if (role == Role.RECEIVE) p.prefetched = null
        if (echo) return
        // One-sided tap (spec 6): the first haptic tick is also the message to the other phone, sent at once and before anything else.
        if (role == Role.RECEIVE && p.role == Role.SEND && p.conn == Conn.READY) {
            val age = SongTapHandshake.impulseAgeMs(t, ownImpulseMs)
            queueWrite(p, Write.TAP, SongTapWire.encodeTap(age))
        } else if (role == Role.SEND) {
            val reader = offerClient
            if (offer != null && reader != null) {
                radio.notify(reader, SongTapWire.encodeTapEvent())
                diag.log(t, "tap told to the receiver")
            }
        }
    }

    /** A tap this phone felt but may not act on (the listener has not said yes): logged (at most every 2 s) and shown on the diagnostics screen. */
    private fun noteIgnoredTapLocked(t: Long, noYes: Boolean) {
        ignoredTaps++
        ignoredTapMs = t
        ignoredTapNoYes = noYes
        if (t - lastIgnoredLogMs < BleTuning.TAP_IGNORED_LOG_MS) return
        lastIgnoredLogMs = t
        diag.log(t, if (noYes) "tap ignored: no yes yet" else "tap ignored: not the phone you said yes to")
    }

    private fun clearIgnoredTapLocked() {
        ignoredTaps = 0
        ignoredTapMs = -1L
        ignoredTapNoYes = true
        lastIgnoredLogMs = -1_000_000L
    }

    /**
     * Sender: the phone the listener said yes to has been silent for a moment and a phone with the same short name is being heard under
     * another address. It is the same phone (the address changes every few seconds), so it takes over the old one's number: the session,
     * the pin, the focus and the prompt carry on as if nothing happened, and the phones that are connected are told again there is an
     * offer. Two different names never merge.
     */
    private fun followSamePhoneLocked(p: Peer, t: Long) {
        val old = byId[pinnedId] ?: return
        if (old === p || old.role != Role.RECEIVE || p.role != Role.RECEIVE) return
        val name = p.name ?: return
        if (old.name != name) return
        if (t - old.lastSeenMs <= NearbyBook.SAME_PHONE_GAP_MS) return
        val temporary = p.id
        peers.remove(old.address)
        byId.remove(old.id)
        byId.remove(temporary)
        book.remove(old.id)
        book.remove(temporary)
        if (old.conn != Conn.NONE) radio.disconnect(old.address)
        p.id = old.id
        byId[p.id] = p
        p.detector = p.detector.copy(lastFireMs = maxOf(p.detector.lastFireMs, old.detector.lastFireMs))
        p.lastNearMs = maxOf(p.lastNearMs, old.lastNearMs)
        if (focus === old) {
            focus = p
            p.nearReported = old.nearReported
        }
        diag.log(t, "#${p.id} changed its address (same name, $name), following the new one")
        if (offer != null) for (client in challenges.keys.toList()) radio.notify(client, SongTapWire.encodeOfferReady())
    }

    private fun maybeConnect(p: Peer, t: Long) {
        if (role != Role.RECEIVE || p.role != Role.SEND || p !== focus) return
        // A connection that stalls must not hold the queue until Android's own (much longer) timeout.
        if (p.conn == Conn.CONNECTING && t - p.connectStartMs >= BleTuning.CONNECT_TIMEOUT_MS) {
            radio.disconnect(p.address)
            p.conn = Conn.NONE
            p.staleConnect = true
            p.writes.clear()
            p.nextConnectAtMs = t + BleTuning.RECONNECT_MS
            diag.log(t, "connecting to #${p.id} took too long, giving up for now")
            if (p.inTransfer) failTransfer(p)
        }
        if (p.conn != Conn.NONE || !p.detector.near || t < p.nextConnectAtMs) return
        connect(p, t)
    }

    private fun evictOldestLocked() {
        var oldest: Peer? = null
        for (q in peers.values) if (q !== focus && q.id != pinnedId && (oldest == null || q.lastSeenMs < oldest.lastSeenMs)) oldest = q
        val gone = oldest ?: return
        peers.remove(gone.address)
        byId.remove(gone.id)
        book.remove(gone.id)
        if (gone.conn != Conn.NONE) radio.disconnect(gone.address)
    }

    private fun tickLocked() {
        val t = clock.elapsedMs()
        calibrationProgress(t)
        val it = peers.values.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val limit = if (p.id == pinnedId) BleTuning.PINNED_PEER_TIMEOUT_MS else BleTuning.PEER_TIMEOUT_MS
            if (t - p.lastSeenMs <= limit) continue
            it.remove()
            byId.remove(p.id)
            book.remove(p.id)
            if (p.conn != Conn.NONE) radio.disconnect(p.address)
            if (p === focus) {
                focus = null
                _glow.value = 0
                emit(TransportEvent.PeerLost(p.id))
                diag.log(t, "#${p.id} is gone")
            }
        }
        if (focus == null && pinnedId == 0) {
            var best: Peer? = null
            for (p in peers.values) if (best == null || strength(p) > strength(best)) best = p
            if (best != null) setFocus(best, t)
        }
        val f = focus
        if (f != null) {
            if (f.conn == Conn.READY && !f.inTransfer && !f.detector.near && t - f.lastNearMs > BleTuning.IDLE_DISCONNECT_MS) {
                radio.disconnect(f.address)
                f.conn = Conn.NONE
                f.writes.clear()
                f.challenge = null
                dropPrefetch(f)
                f.nameAsked = false
            }
            maybeConnect(f, t)
            // Keep the offer in hand fresh, and ask again when the sender had nothing the first time.
            if (role == Role.RECEIVE && f.conn == Conn.READY && f.detector.near && !f.inTransfer) {
                val held = f.prefetched
                val due = if (held == null) t - f.lastPrefetchTryMs >= BleTuning.PREFETCH_RETRY_MS
                else t - held.atMs >= BleTuning.PREFETCH_MAX_AGE_MS && t - f.lastPrefetchTryMs >= BleTuning.PREFETCH_RETRY_MS
                if (due) startPrefetch(f, t)
            }
        }
        publishNearbyLocked(t, force = false)
        if (diag.isEnabled()) diag.publish(t) { snapshotLocked(t) }
    }

    /** The list and the prompt for the Song Drop picker: only a sender uses them, and only from advertisements. */
    private fun publishNearbyLocked(t: Long, force: Boolean) {
        if (!force && t - lastNearbyMs < BleTuning.NEARBY_PUBLISH_MS) return
        lastNearbyMs = t
        if (role != Role.SEND || !started) {
            _nearby.value = emptyList()
            _prompt.value = null
            return
        }
        _nearby.value = book.visible(Role.RECEIVE, t)
        _prompt.value = stickyPromptLocked(if (pinnedId != 0) null else book.promptFor(Role.RECEIVE, t), t)
    }

    /**
     * The prompt, kept up for a moment while the phone it is about changes its address: the old row goes stale and the new one is not
     * inside the gate yet, but the question is still about the same named phone, so it neither vanishes nor comes back.
     */
    private fun stickyPromptLocked(next: NearbyEntry?, t: Long): NearbyEntry? {
        if (next != null) {
            lastPrompt = next
            lastPromptMs = t
            return next
        }
        val last = lastPrompt ?: return null
        if (pinnedId != 0 || t - lastPromptMs > BleTuning.PROMPT_GRACE_MS) {
            lastPrompt = null
            return null
        }
        for (e in book.visible(Role.RECEIVE, t)) {
            if (e.name == last.name && !book.isIgnored(e.id, t)) return e
        }
        return null
    }

    private fun onImpulse(tMs: Long) {
        synchronized(lock) {
            if (!started) return
            ownImpulseMs = tMs
            if (focus?.detector?.near == true) diag.log(tMs, "this phone was jerked")
        }
    }

    private fun snapshotLocked(t: Long): DiagSnapshot {
        val f = focus
        val s = f?.detector
        val label = when {
            f == null -> "no phone"
            s!!.heldSinceMs >= 0L -> "jump building"
            s.near -> "near"
            else -> "far"
        }
        return DiagSnapshot(
            status = _status.value,
            role = if (role == Role.SEND) "send" else "receive",
            peersSeen = peers.size,
            focus = if (f == null) "" else "#${f.id} ${if (f.role == Role.SEND) "sending" else "receiving"}",
            rssi = f?.rssi ?: 0,
            offsetDb = rssiOffsetDb,
            normalised = if (f == null) 0 else f.rssiNorm,
            profile = profile.code,
            smoothed = if (s == null) 0 else dbm(s.fastQ),
            baseline = if (s == null) 0 else dbm(s.baselineQ),
            jump = if (s == null) 0 else dbm(s.fastQ - s.baselineQ),
            heldMs = if (s == null || s.heldSinceMs < 0L) -1L else t - s.heldSinceMs,
            near = s?.near == true,
            connection = when (f?.conn) { null, Conn.NONE -> "none"; Conn.CONNECTING -> "connecting"; Conn.READY -> "connected" },
            ownJerkAgeMs = SongTapHandshake.impulseAgeMs(t, ownImpulseMs),
            peerJerkAgeMs = if (f == null) -1L else SongTapHandshake.impulseAgeMs(t, f.peerImpulseLocalMs),
            detector = label,
            lastFire = if (f == null || f.lastFireMs < 0L) "-" else "${f.lastFire.code} ${t - f.lastFireMs} ms ago",
            samples = diag.sampleCount(),
            gate = when {
                f == null || s == null -> "none"
                s.near -> "tap near"
                book.get(f.id)?.listed == true -> "list only"
                else -> "outside"
            },
            mayConnect = s?.near == true,
            listed = f != null && book.get(f.id)?.listed == true,
            offerInHand = f?.prefetched != null,
            names = if (f == null) "" else "${f.name ?: "-"} / ${f.fullName ?: "-"}",
            tapNote = if (ignoredTaps == 0) "" else
                "${if (ignoredTapNoYes) "tap ignored: no yes yet" else "tap ignored: not the phone you said yes to"} (${ignoredTaps}x, last ${t - ignoredTapMs} ms ago)",
            needsYes = role == Role.SEND && pinnedId == 0 && ignoredTaps > 0 && ignoredTapNoYes && t - ignoredTapMs < BleTuning.NEEDS_YES_HINT_MS,
            radioStarts = radioStarts,
        )
    }

    /** For the diagnostics screen's "mark" buttons: write the current signal into the log under [label]. */
    fun mark(label: String) {
        synchronized(lock) {
            val t = clock.elapsedMs()
            val f = focus
            diag.log(t, if (f == null) "mark $label: no phone" else "mark $label: rssi ${f.rssi}, smoothed ${dbm(f.detector.fastQ)}, baseline ${dbm(f.detector.baselineQ)}")
        }
    }
}
