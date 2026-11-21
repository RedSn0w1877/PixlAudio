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
import com.theveloper.pixelplay.songtap.PayloadDecode
import com.theveloper.pixelplay.songtap.RSSI_SCALE
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.RssiSample
import com.theveloper.pixelplay.songtap.SignedOffer
import com.theveloper.pixelplay.songtap.SongTapDetector
import com.theveloper.pixelplay.songtap.SongTapHandshake
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

    /** A sender takes a receiver's tap message as a tap when its own detector saw that receiver's signal jump within this long. */
    const val PEER_TAP_JUMP_WINDOW_MS = 1_500L

    /**
     * A jerk the other phone reports only counts when its signal is at least this strong. The other phone's word cannot
     * be checked (anything that advertises our service can claim it was jerked just now), so it may shorten the hold
     * only for a phone that is close, never for one that is merely in the room.
     */
    const val PEER_IMPULSE_MIN_DBM = -62

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
     * down if phones are still found too easily; the strict near gate (`SongTapTuning.NEAR_ENTER_DBM`) was set for LOW, so lowering this
     * means calibrating that gate again. Used by `AndroidBleRadio`.
     */
    const val ADVERT_TX_POWER = 1
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

    private class Peer(val id: Int, val address: String) {
        var role = Role.SEND
        var lastSeenMs = 0L
        var lastNearMs = 0L
        var rssi = 0
        var detector = DetectorState.INITIAL
        var nearReported = false
        var glow = 0
        var peerImpulseLocalMs = -1L
        var infoAskedAtMs = 0L
        var conn = Conn.NONE
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

    /** Sender: the phone the listener said yes to (the transport's number), 0 when none. */
    private var pinnedId = 0

    private val book = NearbyBook()
    private var lastNearbyMs = -1_000_000L
    private val _nearby = MutableStateFlow<List<NearbyEntry>>(emptyList())

    /** Sender: the phones advertising as receivers inside the list gate, strongest first, from advertisements only (spec 5). */
    val nearby: StateFlow<List<NearbyEntry>> = _nearby.asStateFlow()

    private val _prompt = MutableStateFlow<NearbyEntry?>(null)

    /** Sender: the phone to ask "Song drop to X?" about (inside the strict near gate, not told no), or null. */
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

    /** "No" on the prompt: that phone is not asked about for a minute. */
    fun ignorePrompt(peer: Int) {
        synchronized(lock) {
            book.ignore(peer, clock.elapsedMs())
            publishNearbyLocked(clock.elapsedMs(), force = true)
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

    private fun startLocked(newRole: Role) {
        if (started) stopLocked()
        role = newRole
        started = true
        signer = if (newRole == Role.SEND) newSigner() else null
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
        ticker = scope.launch {
            while (isActive) {
                delay(BleTuning.TICK_MS)
                synchronized(lock) { if (started) tickLocked() }
            }
        }
        diag.log(clock.elapsedMs(), "armed to ${if (role == Role.SEND) "send" else "receive"}")
    }

    private fun stopLocked() {
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
        _nearby.value = emptyList()
        _prompt.value = null
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
        // followed AND inside the strict near gate, and no other phone that could be the reader is inside it too (a connection's own
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

    /** True when a phone other than [target] that advertises as a receiver is inside the strict near gate right now. */
    private fun anotherReceiverNear(target: Peer): Boolean {
        val t = clock.elapsedMs()
        for (q in peers.values) {
            if (q === target || q.role != Role.RECEIVE) continue
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
                // The tap is believed when it comes from the phone that read the offer (the one the listener said yes to) and this
                // phone saw the signal of the phone it is following jump just now.
                val sawJump = f != null && f.lastJumpMs >= 0L && t - f.lastJumpMs <= BleTuning.PEER_TAP_JUMP_WINDOW_MS
                diag.log(t, "receiver reports a tap (jerk ${message.ageMs} ms ago), own jump ${if (sawJump) "seen" else "not seen"}")
                if (f != null && f.id == pinnedId && f.role == Role.RECEIVE && sawJump && offer != null && client == offerClient) {
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
                if (!p.inTransfer) return // a prefetch with nothing to give yet: the sender's notification (or the retry) comes later
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
            // The only event is "the sender has an offer to read" (EVENT_GO of spec 4 is gone and ignored).
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
            if (name != null && name != p.name) {
                p.name = name
                if (p.fullName == null) emit(TransportEvent.PeerName(p.id, name))
            }

            val impulse = impulseFor(p, ownImpulseMs)
            val wasHeld = p.detector.heldSinceMs >= 0L
            val step = SongTapDetector.step(p.detector, RssiSample(t, rssi), impulse)
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
        if (pinnedId != 0 && p.id != pinnedId) return
        if (p !== focus) setFocus(p, t)
        p.lastFire = reason
        p.lastFireMs = t
        diag.log(t, "tap detected (${reason.code}) with #${p.id}${if (p.prefetched != null) ", offer already in hand" else ""}")
        emit(TransportEvent.PeerRole(p.id, p.role))
        if (role == Role.RECEIVE && p.role == Role.RECEIVE) return
        // The phone that is not the one the listener said yes to cannot start a beam (the session ignores it too).
        if (role == Role.SEND && p.id != pinnedId) return
        emit(TransportEvent.Tap(p.id))
        // The session holds the offer from here on (it took it when the fetch finished); the next beam needs a new one.
        if (role == Role.RECEIVE) p.prefetched = null
        if (role == Role.RECEIVE && p.role == Role.SEND && p.conn == Conn.READY) {
            val age = SongTapHandshake.impulseAgeMs(t, ownImpulseMs)
            queueWrite(p, Write.TAP, SongTapWire.encodeTap(age))
        }
    }

    private fun maybeConnect(p: Peer, t: Long) {
        if (role != Role.RECEIVE || p.role != Role.SEND || p !== focus) return
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
        _prompt.value = if (pinnedId != 0) null else book.promptFor(Role.RECEIVE, t)
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
                s.near -> "strict near"
                book.get(f.id)?.listed == true -> "list only"
                else -> "outside"
            },
            mayConnect = s?.near == true,
            listed = f != null && book.get(f.id)?.listed == true,
            offerInHand = f?.prefetched != null,
            names = if (f == null) "" else "${f.name ?: "-"} / ${f.fullName ?: "-"}",
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
