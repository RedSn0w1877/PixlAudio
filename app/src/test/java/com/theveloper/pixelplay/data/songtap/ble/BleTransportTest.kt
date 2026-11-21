package com.theveloper.pixelplay.data.songtap.ble

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.songtap.SongTapSigner
import com.theveloper.pixelplay.data.songtap.TransportEvent
import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SignedOffer
import com.theveloper.pixelplay.songtap.SongTapPayload
import com.theveloper.pixelplay.songtap.SongTapPayloads
import com.theveloper.pixelplay.songtap.SongTapSigning
import com.theveloper.pixelplay.songtap.SongTapWire
import com.theveloper.pixelplay.songtap.SongTapNames
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class BleTransportTest {

    private val payload = SongTapPayload(
        v = 1, ts = 1_763_600_000_000L, ytVideoId = "dQw4w9WgXcQ", spotifyId = "4uLU6hMCjMI75M1A2tKUQC", isrc = null,
        title = "Never Gonna Give You Up", artist = "Rick Astley", durationMs = 213_000, artUrl = "https://i.ytimg.com/vi/x/y.jpg",
    )
    private val payloadBytes = SongTapPayloads.encode(payload)!!

    /** What a sender's GATT server would answer an Offer read with. */
    private fun frame(challenge: ByteArray, ts: Long, signer: SongTapSigner = SongTapSigner.create(), body: ByteArray = payloadBytes): ByteArray {
        val message = SongTapSigning.signingBytes(body, challenge, ts)!!
        return SongTapSigning.encodeFrame(SignedOffer(body, ts, signer.publicKey, signer.sign(message)))!!
    }

    private fun lastHello(rig: BleRig): ByteArray {
        val hello = rig.controlWritesDecoded().filterIsInstance<SongTapWire.ControlMessage.Hello>().last()
        return hello.challenge
    }

    /** A receiver that has found a sender, got near, and connected: the state a tap happens in. */
    private fun TestScope.connectedReceiver(): BleRig {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND)
        rig.radio.listener.onConnected("S", true)
        return rig
    }

    // ── Receiver ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a receiver follows a sender, announces it, and connects only inside the strict near gate`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        assertThat(rig.transport.status.value).isEqualTo(BleStatus.ACTIVE)
        assertThat(rig.impulses.running).isTrue()

        // Found from a few metres (inside the list gate, outside the strict one): followed, but never connected to.
        rig.settle("S", Role.SEND, BleRig.LIST_ONLY_DBM)
        assertThat(rig.of<TransportEvent.PeerFound>()).hasSize(1)
        assertThat(rig.of<TransportEvent.PeerNear>()).isEmpty()
        assertThat(rig.radio.count("connect")).isEqualTo(0)

        val near = BleRig(this)
        near.transport.start(Role.RECEIVE)
        near.settle("S", Role.SEND)
        assertThat(near.of<TransportEvent.PeerNear>()).isNotEmpty()
        assertThat(near.radio.calls).contains("connect:S")
        assertThat(near.transport.glow.value).isGreaterThan(0)

        near.radio.listener.onConnected("S", true)
        val hello = near.lastControl() as SongTapWire.ControlMessage.Hello
        assertThat(hello.challenge).hasLength(SongTapSigning.CHALLENGE_BYTES)
        // Connected: the slow work happens now, before any tap.
        assertThat(near.radio.calls).containsAtLeast("readName:S", "readOffer:S")
    }

    @Test
    fun `a signal that is only just inside the old loose gate cannot connect`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND, -62)
        rig.touch("S", Role.SEND, -58)
        assertThat(rig.radio.count("connect")).isEqualTo(0)
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
    }

    @Test
    fun `a steady signal fires nothing, a jump that holds fires a tap with the sender's role first`() = runTest {
        val rig = connectedReceiver()
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        rig.touch("S", Role.SEND)
        val roles = rig.of<TransportEvent.PeerRole>()
        val taps = rig.of<TransportEvent.Tap>()
        assertThat(roles).hasSize(1)
        assertThat(roles[0].role).isEqualTo(Role.SEND)
        assertThat(taps).hasSize(1)
        assertThat(rig.events.indexOf(roles[0])).isLessThan(rig.events.indexOf(taps[0]))
        // The receiver tells the sender, in the same connection, that it felt a tap.
        assertThat(rig.controlWritesDecoded().filterIsInstance<SongTapWire.ControlMessage.Tap>()).hasSize(1)
    }

    @Test
    fun `phones brought together gently and held fire without any bump`() = runTest {
        val rig = connectedReceiver()
        // A slow rise from the near edge to a few centimetres: no 15 dB jump at any moment, no jerk.
        for (rssi in listOf(-54, -53, -52, -51, -50, -49, -48)) rig.see("S", Role.SEND, rssi)
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        repeat(6) { rig.see("S", Role.SEND, -48) }
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
        rig.settle("S", Role.SEND, -48, samples = 25)
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
    }

    @Test
    fun `a jerk makes the same jump count sooner than the dwell does`() = runTest {
        val dwell = connectedReceiver()
        dwell.touch("S", Role.SEND, samples = 4)
        assertThat(dwell.of<TransportEvent.Tap>()).isEmpty()

        val jerk = connectedReceiver()
        jerk.clock.advance(100)
        jerk.impulses.jerk(jerk.clock.elapsed)
        jerk.touch("S", Role.SEND, samples = 4)
        assertThat(jerk.of<TransportEvent.Tap>()).hasSize(1)
    }

    @Test
    fun `two receivers beside each other do nothing for each other`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.settle("R", Role.RECEIVE)
        rig.touch("R", Role.RECEIVE)
        assertThat(rig.of<TransportEvent.PeerRole>().map { it.role }).containsExactly(Role.RECEIVE)
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        assertThat(rig.radio.count("connect")).isEqualTo(0)
    }

    @Test
    fun `the offer is fetched and checked before the tap, and the tap reads nothing`() = runTest {
        val rig = connectedReceiver()
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        assertThat(rig.radio.count("readOffer")).isEqualTo(1)
        // The sender's answer to the read that went out when the connection came up.
        rig.radio.listener.onOfferRead("S", frame(lastHelloBefore(rig), rig.clock.wall))
        val ahead = rig.of<TransportEvent.OfferPrefetched>().single()
        assertThat(ahead.peer).isEqualTo(peer)
        assertThat(ahead.payload).isEqualTo(payloadBytes)
        assertThat(ahead.senderKey).isNotEqualTo(0)
        assertThat(rig.of<TransportEvent.Offer>()).isEmpty()
        // The next challenge is already out for the beam after this one.
        assertThat(rig.controlWritesDecoded().filterIsInstance<SongTapWire.ControlMessage.Hello>()).hasSize(2)

        // The tap: the session already holds the offer (it has the event above), so nothing is read and nothing is waited for.
        rig.touch("S", Role.SEND)
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
        assertThat(rig.events.indexOf(ahead)).isLessThan(rig.events.indexOf(rig.of<TransportEvent.Tap>().single()))
        assertThat(rig.radio.count("readOffer")).isEqualTo(1)
        assertThat(rig.of<TransportEvent.Offer>()).isEmpty()
        // And a late fallback read after the tap does not hand out the same offer twice.
        rig.transport.readOffer(peer)
        assertThat(rig.of<TransportEvent.Offer>()).isEmpty()
    }

    @Test
    fun `the sender's notification that it has an offer starts the fetch, and a quiet sender is asked again later`() = runTest {
        val rig = connectedReceiver()
        // It had nothing the first time.
        rig.radio.listener.onOfferRead("S", null)
        assertThat(rig.of<TransportEvent.OfferPrefetched>()).isEmpty()
        assertThat(rig.radio.count("readOffer")).isEqualTo(1)
        rig.radio.listener.onNotified("S", SongTapWire.encodeOfferReady())
        assertThat(rig.radio.count("readOffer")).isEqualTo(2)
        rig.radio.listener.onOfferRead("S", null)

        // No notification: it is asked again after a pause (the sender's word is the fast path, not the only one).
        rig.clock.advance(BleTuning.PREFETCH_RETRY_MS + 100)
        rig.see("S", Role.SEND, BleRig.NEAR_DBM, stepMs = 0)
        advanceTimeBy(BleTuning.TICK_MS + 1)
        assertThat(rig.radio.count("readOffer")).isEqualTo(3)
    }

    @Test
    fun `an offer in hand is let go when the connection goes or the phone is replaced`() = runTest {
        val rig = connectedReceiver()
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.radio.listener.onOfferRead("S", frame(lastHelloBefore(rig), rig.clock.wall))
        assertThat(rig.of<TransportEvent.OfferPrefetched>()).hasSize(1)
        rig.radio.listener.onDisconnected("S")
        assertThat(rig.of<TransportEvent.OfferGone>().map { it.peer }).containsExactly(peer)
        // And the tap after that has nothing in hand: the offer is read the slow way.
        rig.touch("S", Role.SEND)
        rig.transport.readOffer(peer)
        assertThat(rig.of<TransportEvent.Offer>()).isEmpty()
    }

    @Test
    fun `an offer that fails its check ahead of the tap is not held`() = runTest {
        val rig = connectedReceiver()
        rig.radio.listener.onOfferRead("S", frame(ByteArray(16) { 9 }, rig.clock.wall))
        assertThat(rig.of<TransportEvent.OfferPrefetched>()).isEmpty()
        assertThat(rig.of<TransportEvent.OfferInvalid>()).isEmpty()
    }

    @Test
    fun `the sender's name comes from its advertisement and then from its Name characteristic`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND, name = "Zed", samples = 2)
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        assertThat(rig.of<TransportEvent.PeerName>().last().name).isEqualTo("Zed")
        assertThat(rig.transport.nameOf(peer)).isEqualTo("Zed")
        rig.radio.listener.onConnected("S", true)
        rig.radio.listener.onNameRead("S", SongTapWire.encodeName("Zed's Pixel 10 Pro"))
        assertThat(rig.of<TransportEvent.PeerName>().last().name).isEqualTo("Zed's Pixel 10 Pro")
        assertThat(rig.transport.nameOf(peer)).isEqualTo("Zed's Pixel 10 Pro")
        // A hostile name value changes nothing.
        rig.radio.listener.onNameRead("S", byteArrayOf(1, 0x20))
        rig.radio.listener.onNameRead("S", null)
        assertThat(rig.transport.nameOf(peer)).isEqualTo("Zed's Pixel 10 Pro")
    }

    @Test
    fun `reading an offer checks the signature against the challenge this receiver gave`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.transport.readOffer(peer)
        assertThat(rig.radio.calls).contains("readOffer:S")

        rig.radio.listener.onOfferRead("S", frame(lastHelloBefore(rig), rig.clock.wall))
        val offer = rig.of<TransportEvent.Offer>().single()
        assertThat(offer.payload).isEqualTo(payloadBytes)
        assertThat(offer.senderKey).isNotEqualTo(0)
        assertThat(rig.of<TransportEvent.OfferInvalid>()).isEmpty()
        // A fresh challenge is put out for the next beam.
        assertThat(rig.controlWritesDecoded().filterIsInstance<SongTapWire.ControlMessage.Hello>()).hasSize(2)
    }

    /** The challenge the receiver gave before the offer was read: the first Hello. */
    private fun lastHelloBefore(rig: BleRig): ByteArray =
        rig.controlWritesDecoded().filterIsInstance<SongTapWire.ControlMessage.Hello>().first().challenge

    @Test
    fun `an offer signed for another challenge is refused`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        rig.transport.readOffer(rig.of<TransportEvent.PeerFound>().last().peer)
        rig.radio.listener.onOfferRead("S", frame(ByteArray(16) { 9 }, rig.clock.wall))
        assertThat(rig.of<TransportEvent.Offer>()).isEmpty()
        assertThat(rig.of<TransportEvent.OfferInvalid>()).hasSize(1)
    }

    @Test
    fun `an offer replayed after its challenge was used is refused`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        val challenge = lastHelloBefore(rig)
        val signed = frame(challenge, rig.clock.wall)
        rig.transport.readOffer(peer)
        rig.radio.listener.onOfferRead("S", signed)
        assertThat(rig.of<TransportEvent.Offer>()).hasSize(1)
        // The same bytes again: the challenge is spent, the receiver has given a new one.
        rig.transport.readOffer(peer)
        rig.radio.listener.onOfferRead("S", signed)
        assertThat(rig.of<TransportEvent.Offer>()).hasSize(1)
        assertThat(rig.of<TransportEvent.OfferInvalid>()).hasSize(1)
    }

    @Test
    fun `an offer made a minute ago, an altered payload and garbage are refused`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        val peer = rig.of<TransportEvent.PeerFound>().last().peer

        fun attempt(bytes: ByteArray) {
            rig.transport.readOffer(peer)
            rig.radio.listener.onOfferRead("S", bytes)
        }
        var challenge = lastHelloBefore(rig)
        attempt(frame(challenge, rig.clock.wall - 60_000L))
        challenge = lastHello(rig)
        val good = frame(challenge, rig.clock.wall)
        val altered = good.copyOf().also { it[10] = (it[10] + 1).toByte() }
        attempt(altered)
        attempt(ByteArray(40) { 1 })
        assertThat(rig.of<TransportEvent.Offer>()).isEmpty()
        assertThat(rig.of<TransportEvent.OfferInvalid>()).hasSize(3)
    }

    @Test
    fun `a sender that has no offer yet is asked again, then given up on`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        rig.transport.readOffer(rig.of<TransportEvent.PeerFound>().last().peer)
        repeat(BleTuning.MAX_READ_ATTEMPTS) {
            rig.radio.listener.onOfferRead("S", null)
            advanceTimeBy(BleTuning.READ_RETRY_MS + 1)
        }
        // One read went out when the connection came up (the fetch ahead of the tap), then the tap's own.
        assertThat(rig.radio.count("readOffer")).isEqualTo(BleTuning.MAX_READ_ATTEMPTS + 1)
        assertThat(rig.of<TransportEvent.OfferInvalid>()).hasSize(1)
    }

    @Test
    fun `ack goes out as a write and the old go notification is ignored`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.transport.sendAck(peer)
        assertThat(rig.lastControl()).isEqualTo(SongTapWire.ControlMessage.Ack)
        val before = rig.events.size
        rig.radio.listener.onNotified("S", SongTapWire.encodeGo())
        rig.radio.listener.onNotified("S", byteArrayOf(0x55))
        assertThat(rig.events).hasSize(before)
    }

    @Test
    fun `losing the connection in the middle of a beam is a lost peer, and a duplicate go is harmless`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.transport.readOffer(peer)
        rig.radio.listener.onDisconnected("S")
        assertThat(rig.of<TransportEvent.PeerLost>().map { it.peer }).contains(peer)

        // Late and duplicate notifications after the drop are ignored or passed once each, never crash.
        rig.radio.listener.onNotified("S", SongTapWire.encodeGo())
        rig.radio.listener.onNotified("S", byteArrayOf(0x55))
        rig.radio.listener.onOfferRead("S", ByteArray(3))
        assertThat(rig.of<TransportEvent.Offer>()).isEmpty()
    }

    @Test
    fun `cancelling mid-transfer closes the connection and stops asking for the offer`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.transport.readOffer(peer)
        rig.transport.abort(peer)
        assertThat(rig.radio.calls).contains("disconnect:S")
        rig.radio.listener.onOfferRead("S", frame(lastHelloBefore(rig), rig.clock.wall))
        assertThat(rig.of<TransportEvent.Offer>()).isEmpty()
    }

    @Test
    fun `a failed connection is retried after a pause, not at once`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND)
        assertThat(rig.radio.count("connect")).isEqualTo(1)
        rig.radio.listener.onConnected("S", false)
        rig.see("S", Role.SEND, BleRig.NEAR_DBM, stepMs = 100)
        assertThat(rig.radio.count("connect")).isEqualTo(1)
        rig.see("S", Role.SEND, BleRig.NEAR_DBM, stepMs = BleTuning.RECONNECT_MS)
        assertThat(rig.radio.count("connect")).isEqualTo(2)
    }

    @Test
    fun `the phone being followed leaving is a lost peer, and a quiet connection is closed`() = runTest {
        val rig = connectedReceiver()
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.clock.advance(BleTuning.PEER_TIMEOUT_MS + 1000)
        advanceTimeBy(BleTuning.TICK_MS * 2)
        assertThat(rig.of<TransportEvent.PeerLost>().map { it.peer }).contains(peer)
        assertThat(rig.radio.calls).contains("disconnect:S")
        assertThat(rig.transport.glow.value).isEqualTo(0)
    }

    @Test
    fun `resync announces the phone that is close again`() = runTest {
        val rig = connectedReceiver()
        val before = rig.of<TransportEvent.PeerFound>().size
        rig.transport.resync()
        assertThat(rig.of<TransportEvent.PeerFound>()).hasSize(before + 1)
        assertThat(rig.of<TransportEvent.PeerNear>()).isNotEmpty()
    }

    @Test
    fun `the diagnostics see the signal while the screen is open and cost nothing otherwise`() = runTest {
        val diag = SongTapDiagnostics()
        val rig = BleRig(this, diag)
        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND)
        assertThat(diag.snapshot.value.rssi).isEqualTo(0)
        diag.setEnabled(true)
        rig.see("S", Role.SEND, -58)
        assertThat(diag.snapshot.value.rssi).isEqualTo(-58)
        assertThat(diag.snapshot.value.focus).contains("sending")
        // The gates are on the screen: near stays "strict near" until it falls below the exit level.
        assertThat(diag.snapshot.value.gate).isEqualTo("strict near")
        assertThat(diag.snapshot.value.mayConnect).isTrue()
        rig.settle("S", Role.SEND, BleRig.LIST_ONLY_DBM, samples = 6)
        assertThat(diag.snapshot.value.gate).isEqualTo("list only")
        assertThat(diag.snapshot.value.mayConnect).isFalse()
        assertThat(diag.snapshot.value.listed).isTrue()
        assertThat(diag.copyLog()).contains("gate:")
        assertThat(diag.events.value.size).isAtMost(SongTapDiagnostics.EVENT_CAPACITY)
        assertThat(diag.copyLog()).contains("recent signal")
    }

    // ── Sender ───────────────────────────────────────────────────────────────────────

    private fun TestScope.sender(): BleRig {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R", Role.RECEIVE)
        return rig
    }

    private fun BleRig.peerOf(): Int = of<TransportEvent.PeerFound>().last().peer

    @Test
    fun `a sender gives nothing until a receiver has said hello and an offer is published for a phone`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        val client = "client-1"
        assertThat(rig.transport.serverOffer(client)).isNull()
        // An offer for nobody (no yes to any phone) is served to nobody.
        rig.transport.publishOffer(0, payloadBytes)
        val challenge = ByteArray(16) { (it + 1).toByte() }
        assertThat(rig.transport.serverControl(client, SongTapWire.encodeHello(challenge)!!)).isTrue()
        assertThat(rig.transport.serverOffer(client)).isNull()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.withdrawOffer()
        rig.transport.publishOffer(peer, payloadBytes)
        val signed = rig.transport.serverOffer(client)!!
        val offer = SongTapSigning.decodeFrame(signed)!!
        assertThat(SongTapSigning.check(offer, challenge, rig.clock.wall, com.theveloper.pixelplay.data.songtap.SongTapVerifier)).isEqualTo(com.theveloper.pixelplay.songtap.OfferCheck.OK)
        assertThat(rig.of<TransportEvent.OfferRead>().map { it.peer }).containsExactly(peer)
        // A challenge answers one read.
        assertThat(rig.transport.serverOffer(client)).isNull()
    }

    @Test
    fun `a sender signs with a key of its own for each session`() = runTest {
        val a = sender()
        val b = sender()
        val challenge = ByteArray(16) { 7 }
        for (rig in listOf(a, b)) {
            rig.transport.publishOffer(rig.peerOf(), payloadBytes)
            rig.transport.serverControl("c", SongTapWire.encodeHello(challenge)!!)
        }
        val keyA = SongTapSigning.decodeFrame(a.transport.serverOffer("c")!!)!!.publicKey
        val keyB = SongTapSigning.decodeFrame(b.transport.serverOffer("c")!!)!!.publicKey
        assertThat(keyA).isNotEqualTo(keyB)
    }

    @Test
    fun `the info a sender serves is how long ago it was jerked`() = runTest {
        val rig = sender()
        assertThat(SongTapWire.decodeInfo(rig.transport.serverInfo())).isEqualTo(-1L)
        rig.clock.advance(50)
        rig.impulses.jerk(rig.clock.elapsed)
        rig.clock.advance(120)
        assertThat(SongTapWire.decodeInfo(rig.transport.serverInfo())).isEqualTo(120L)
    }

    @Test
    fun `the sender serves its own name, cleaned`() = runTest {
        val rig = BleRig(this)
        rig.transport.setOwnName("  Hoa's    Pixel \u202e ")
        rig.transport.start(Role.SEND)
        assertThat(SongTapWire.decodeName(rig.transport.serverName())).isEqualTo("Hoa's Pixel")
        assertThat(rig.radio.advertisedName).isEqualTo("Hoa's Pixel")
        // Nothing usable: the fallback, never an empty name.
        val blank = BleRig(this)
        blank.transport.setOwnName("\u202e  ")
        blank.transport.start(Role.RECEIVE)
        assertThat(blank.radio.advertisedName).isEqualTo(SongTapNames.FALLBACK)
    }

    @Test
    fun `ack and decline from the receiver reach the session`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        assertThat(rig.transport.serverOffer("c")).isNotNull()
        rig.transport.serverControl("c", SongTapWire.encodeAck())
        assertThat(rig.of<TransportEvent.Ack>().single().peer).isEqualTo(peer)
        rig.transport.serverControl("c", SongTapWire.encodeDecline())
        assertThat(rig.of<TransportEvent.Declined>().single().peer).isEqualTo(peer)
    }

    @Test
    fun `ack and decline belong to the phone that read the offer and nobody else`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("reader", SongTapWire.encodeHello(ByteArray(16) { 1 })!!)
        rig.transport.serverControl("stranger", SongTapWire.encodeHello(ByteArray(16) { 2 })!!)
        assertThat(rig.transport.serverOffer("reader")).isNotNull()
        // Once somebody has read it, nobody else is given it, and nobody else can answer for it.
        assertThat(rig.transport.serverOffer("stranger")).isNull()
        rig.transport.serverControl("stranger", SongTapWire.encodeAck())
        rig.transport.serverControl("stranger", SongTapWire.encodeDecline())
        assertThat(rig.of<TransportEvent.Ack>()).isEmpty()
        assertThat(rig.of<TransportEvent.Declined>()).isEmpty()
        rig.transport.serverControl("reader", SongTapWire.encodeAck())
        assertThat(rig.of<TransportEvent.Ack>()).hasSize(1)
    }

    @Test
    fun `a late ack from the last offer's receiver is not an ack for the next offer`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("first", SongTapWire.encodeHello(ByteArray(16) { 1 })!!)
        assertThat(rig.transport.serverOffer("first")).isNotNull()
        rig.transport.withdrawOffer() // timed out
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("first", SongTapWire.encodeAck())
        assertThat(rig.of<TransportEvent.Ack>()).isEmpty()
        rig.transport.serverControl("second", SongTapWire.encodeHello(ByteArray(16) { 3 })!!)
        assertThat(rig.transport.serverOffer("second")).isNotNull()
        rig.transport.serverControl("second", SongTapWire.encodeAck())
        assertThat(rig.of<TransportEvent.Ack>()).hasSize(1)
    }

    @Test
    fun `publishing tells the phones that are connected there is something to read`() = runTest {
        val rig = sender()
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        assertThat(rig.radio.notifications).isEmpty()
        rig.transport.publishOffer(rig.peerOf(), payloadBytes)
        assertThat(rig.radio.notifications.map { it.first }).containsExactly("c")
        assertThat(SongTapWire.isOfferReady(rig.radio.notifications.single().second)).isTrue()
    }

    @Test
    fun `a receiver's tap message counts only from the phone that read the offer, and only when the sender's own signal jumped too`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("reader", SongTapWire.encodeHello(ByteArray(16) { 1 })!!)
        rig.transport.serverControl("other", SongTapWire.encodeHello(ByteArray(16) { 2 })!!)
        // The sender's own signal has not jumped: the message is not believed.
        assertThat(rig.transport.serverOffer("reader")).isNotNull()
        rig.transport.serverControl("reader", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        // The jump has started but has not held long enough to fire alone.
        rig.touch("R", Role.RECEIVE, samples = 3)
        rig.transport.serverControl("other", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        rig.transport.serverControl("reader", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>().map { it.peer }).containsExactly(peer)
    }

    @Test
    fun `another phone's jump does not make a receiver's tap message count`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        assertThat(rig.transport.serverOffer("c")).isNotNull()
        rig.settle("Q", Role.RECEIVE, rssi = -90)
        rig.touch("Q", Role.RECEIVE, rssi = -66, samples = 4) // Q jumps; R, the phone being followed, does not
        rig.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
    }

    @Test
    fun `a jerk the other phone claims shortens the hold when it is close`() = runTest {
        val close = BleRig(this)
        close.transport.start(Role.RECEIVE)
        close.settle("S", Role.SEND)
        close.radio.listener.onConnected("S", true)
        close.touch("S", Role.SEND, samples = 2)
        close.radio.listener.onInfoRead("S", SongTapWire.encodeInfo(0))
        close.touch("S", Role.SEND, samples = 2)
        assertThat(close.of<TransportEvent.Tap>()).hasSize(1) // the fourth reading: the hold path needs a fifth
    }

    @Test
    fun `a sender's own detector fires only for the phone the listener said yes to`() = runTest {
        val unchosen = sender()
        unchosen.touch("R", Role.RECEIVE)
        assertThat(unchosen.of<TransportEvent.PeerRole>().single().role).isEqualTo(Role.RECEIVE)
        assertThat(unchosen.of<TransportEvent.Tap>()).isEmpty()

        val chosen = sender()
        val peer = chosen.peerOf()
        chosen.transport.publishOffer(peer, payloadBytes)
        chosen.touch("R", Role.RECEIVE)
        assertThat(chosen.of<TransportEvent.Tap>().map { it.peer }).containsExactly(peer)
    }

    @Test
    fun `two senders touching raise the both-sending role event for the session`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("X", Role.SEND)
        rig.touch("X", Role.SEND)
        assertThat(rig.of<TransportEvent.PeerRole>().map { it.role }).contains(Role.SEND)
    }

    @Test
    fun `the reader leaving before the beam is a lost read and a lost peer, a bystander or an acknowledged reader is nothing`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        rig.transport.serverClientGone("c")
        assertThat(rig.of<TransportEvent.PeerLost>()).isEmpty()

        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        rig.transport.serverControl("bystander", SongTapWire.encodeHello(ByteArray(16))!!)
        rig.transport.publishOffer(peer, payloadBytes)
        assertThat(rig.transport.serverOffer("c")).isNotNull()
        // A connection that never read the offer is not the receiver: its leaving is nothing.
        rig.transport.serverClientGone("bystander")
        assertThat(rig.of<TransportEvent.PeerLost>()).isEmpty()
        rig.transport.serverClientGone("c")
        assertThat(rig.of<TransportEvent.OfferReadLost>().map { it.peer }).containsExactly(peer)
        assertThat(rig.of<TransportEvent.PeerLost>()).hasSize(1)

        // After its answer, a receiver leaving is nothing at all.
        rig.transport.serverControl("d", SongTapWire.encodeHello(ByteArray(16))!!)
        assertThat(rig.transport.serverOffer("d")).isNotNull()
        rig.transport.serverControl("d", SongTapWire.encodeAck())
        rig.transport.serverClientGone("d")
        assertThat(rig.of<TransportEvent.OfferReadLost>()).hasSize(1)
        assertThat(rig.of<TransportEvent.PeerLost>()).hasSize(1)
    }

    @Test
    fun `a frame that would not fit one attribute is sent without its art`() = runTest {
        val rig = sender()
        // A payload at the cap: 400 bytes; the frame is 142 bytes more, over the 512 of one attribute.
        val big = SongTapPayloads.make(
            nowMs = payload.ts, ytVideoId = "dQw4w9WgXcQ", spotifyId = "4uLU6hMCjMI75M1A2tKUQC", isrc = "GBARL9300135",
            title = "\u6771".repeat(33), artist = "\u6771".repeat(26), durationMs = 200_000L, artUrl = "https://i.ytimg.com/" + "p".repeat(50),
        )!!
        val bytes = SongTapPayloads.encode(big)!!
        assertThat(bytes.size + BleTuning.FRAME_OVERHEAD_BYTES).isGreaterThan(SongTapSigning.MAX_FRAME_BYTES)
        rig.transport.publishOffer(rig.peerOf(), bytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16) { 3 })!!)
        val signed = rig.transport.serverOffer("c")!!
        val decoded = SongTapSigning.decodeFrame(signed)!!
        val inside = (SongTapPayloads.decode(decoded.payload) as com.theveloper.pixelplay.songtap.PayloadDecode.Ok).payload
        assertThat(inside.artUrl).isNull()
        assertThat(inside.title).isEqualTo(big.title)
    }

    @Test
    fun `a receiver does not serve and a sender with a withdrawn offer gives nothing`() = runTest {
        val receiver = BleRig(this)
        receiver.transport.start(Role.RECEIVE)
        receiver.transport.publishOffer(0, payloadBytes)
        assertThat(receiver.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)).isFalse()
        assertThat(receiver.transport.serverOffer("c")).isNull()

        val s = sender()
        s.transport.publishOffer(s.peerOf(), payloadBytes)
        s.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        s.transport.withdrawOffer()
        assertThat(s.transport.serverOffer("c")).isNull()
    }

    @Test
    fun `malformed control writes are refused and change nothing`() = runTest {
        val rig = sender()
        assertThat(rig.transport.serverControl("c", ByteArray(0))).isFalse()
        assertThat(rig.transport.serverControl("c", byteArrayOf(0x7f, 1, 2))).isFalse()
        assertThat(rig.transport.serverControl("c", byteArrayOf(SongTapWire.OP_HELLO.toByte(), 1, 2))).isFalse()
        assertThat(rig.events.filterNot { it is TransportEvent.PeerFound || it is TransportEvent.PeerNear || it is TransportEvent.PeerName }).isEmpty()
    }

    // ── Song Drop: range, the picker, the target ─────────────────────────────────────

    @Test
    fun `the offer is served only while the phone it is for is inside the strict near gate`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.see("R", Role.RECEIVE, BleRig.LIST_ONLY_DBM)
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16) { 5 })!!)
        // A few metres: listed for the picker, but the offer is not given out.
        assertThat(rig.transport.serverOffer("c")).isNull()
        assertThat(rig.of<TransportEvent.OfferRead>()).isEmpty()
        // It comes close (no jump: a few readings working up to the near edge).
        for (rssi in listOf(-66, -62, -58, -55, -54, -54, -54)) rig.see("R", Role.RECEIVE, rssi)
        assertThat(rig.transport.serverOffer("c")).isNotNull()
    }

    @Test
    fun `when two phones that could be the reader are close, nobody is served`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R1", Role.RECEIVE)
        val chosen = rig.peerOf()
        rig.settle("R2", Role.RECEIVE)
        rig.transport.publishOffer(chosen, payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16) { 5 })!!)
        assertThat(rig.transport.serverOffer("c")).isNull()
        // The other one moves away: now there is no doubt about who is reading.
        repeat(6) { rig.see("R2", Role.RECEIVE, -90) }
        rig.see("R1", Role.RECEIVE, BleRig.NEAR_DBM)
        assertThat(rig.transport.serverOffer("c")).isNotNull()
    }

    @Test
    fun `the phone armed to is followed even when another comes in stronger`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R1", Role.RECEIVE)
        val chosen = rig.peerOf()
        rig.transport.publishOffer(chosen, payloadBytes)
        rig.settle("R2", Role.RECEIVE, rssi = -40)
        assertThat(rig.of<TransportEvent.PeerFound>().map { it.peer }).containsExactly(chosen)
    }

    @Test
    fun `the nearby list is built from advertisements only, strongest first, with the names they carried`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.see("R1", Role.RECEIVE, BleRig.LIST_ONLY_DBM, stepMs = 300, name = "Ann")
        rig.see("R2", Role.RECEIVE, -60, stepMs = 300, name = "Bo")
        rig.see("S1", Role.SEND, -50, stepMs = 300, name = "Cy")
        rig.see("R3", Role.RECEIVE, -90, stepMs = 300, name = "Di")
        rig.see("R1", Role.RECEIVE, BleRig.LIST_ONLY_DBM, stepMs = 300, name = "Ann")
        val list = rig.transport.nearby.value
        assertThat(list.map { it.name }).containsExactly("Bo", "Ann").inOrder()
        assertThat(list.map { it.near }).containsExactly(false, false)
        // Listing never connects.
        assertThat(rig.radio.count("connect")).isEqualTo(0)
        // A phone that stops advertising drops out of the list.
        rig.clock.advance(5_000)
        rig.see("R2", Role.RECEIVE, -60, stepMs = 300, name = "Bo")
        assertThat(rig.transport.nearby.value.map { it.name }).containsExactly("Bo")
        // A receiver's list is empty: only a sender picks.
        val receiver = BleRig(this)
        receiver.transport.start(Role.RECEIVE)
        receiver.see("R1", Role.RECEIVE, -60, stepMs = 300, name = "Ann")
        assertThat(receiver.transport.nearby.value).isEmpty()
    }

    @Test
    fun `the prompt appears for a phone inside the strict gate and not before, and no silences it for a minute`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.see("R", Role.RECEIVE, BleRig.LIST_ONLY_DBM, stepMs = 300, name = "Ann")
        assertThat(rig.transport.prompt.value).isNull()
        for (rssi in listOf(-66, -62, -58, -55, -54, -54, -54, -54)) rig.see("R", Role.RECEIVE, rssi, stepMs = 300, name = "Ann")
        val asked = rig.transport.prompt.value!!
        assertThat(asked.name).isEqualTo("Ann")
        assertThat(asked.near).isTrue()

        rig.transport.ignorePrompt(asked.id)
        assertThat(rig.transport.prompt.value).isNull()
        rig.see("R", Role.RECEIVE, BleRig.NEAR_DBM, stepMs = 30_000, name = "Ann")
        assertThat(rig.transport.prompt.value).isNull()
        rig.see("R", Role.RECEIVE, BleRig.NEAR_DBM, stepMs = 31_000, name = "Ann")
        assertThat(rig.transport.prompt.value?.id).isEqualTo(asked.id)
    }

    @Test
    fun `the radio is told to advertise the clean short name, and the power is the low one`() = runTest {
        val rig = BleRig(this)
        rig.transport.setOwnName("\u00e9".repeat(25))
        rig.transport.start(Role.RECEIVE)
        assertThat(rig.radio.advertisedName).isEqualTo("\u00e9".repeat(20))
        assertThat(BleTuning.ADVERT_TX_POWER).isEqualTo(1)
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────────

    @Test
    fun `stopping stops the radio and the sensor and forgets everyone`() = runTest {
        val rig = connectedReceiver()
        rig.transport.stop()
        assertThat(rig.radio.calls.last()).isEqualTo("stop")
        assertThat(rig.impulses.running).isFalse()
        assertThat(rig.transport.status.value).isEqualTo(BleStatus.IDLE)
        assertThat(rig.transport.glow.value).isEqualTo(0)
        // Scan results that arrive late are ignored.
        val before = rig.events.size
        rig.see("S", Role.SEND, -40)
        assertThat(rig.events).hasSize(before)
    }

    @Test
    fun `without the permission nothing runs, and it starts when the permission arrives`() = runTest {
        val rig = BleRig(this)
        rig.radio.startStatus = BleStatus.NO_PERMISSION
        rig.transport.start(Role.RECEIVE)
        assertThat(rig.transport.status.value).isEqualTo(BleStatus.NO_PERMISSION)
        assertThat(rig.impulses.running).isFalse()

        rig.radio.startStatus = BleStatus.ACTIVE
        rig.transport.restart()
        assertThat(rig.transport.status.value).isEqualTo(BleStatus.ACTIVE)
        assertThat(rig.impulses.running).isTrue()
    }

    @Test
    fun `bluetooth switched off mid-beam loses the peer and stops the sensor, and switched on again resumes`() = runTest {
        val rig = connectedReceiver()
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.radio.listener.onStatus(BleStatus.BLUETOOTH_OFF)
        assertThat(rig.of<TransportEvent.PeerLost>().map { it.peer }).contains(peer)
        assertThat(rig.transport.status.value).isEqualTo(BleStatus.BLUETOOTH_OFF)
        assertThat(rig.impulses.running).isFalse()

        rig.radio.listener.onStatus(BleStatus.ACTIVE)
        assertThat(rig.transport.status.value).isEqualTo(BleStatus.ACTIVE)
        assertThat(rig.impulses.running).isTrue()
    }

    @Test
    fun `changing role starts the radio again with the new role`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.transport.start(Role.RECEIVE)
        assertThat(rig.radio.calls.filter { it.startsWith("start") }).containsExactly("start:send", "start:receive").inOrder()
        assertThat(rig.radio.calls).contains("stop")
    }
}
