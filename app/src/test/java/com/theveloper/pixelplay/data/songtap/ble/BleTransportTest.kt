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
import com.theveloper.pixelplay.songtap.SongTapPeerTap
import com.theveloper.pixelplay.songtap.SongTapSensitivity
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
    fun `a signal that is only inside the list gate cannot connect or tap, however it moves`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND, BleRig.LIST_ONLY_DBM)
        rig.touch("S", Role.SEND, -73)
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
        // A slow rise from resting distance to a few centimetres, half a dB a reading: no jump at any moment, no jerk, and still short of -45.
        for (rssi in (-60..-47).flatMap { listOf(it, it) }) rig.see("S", Role.SEND, rssi)
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        repeat(6) { rig.see("S", Role.SEND, -40) }
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
        rig.settle("S", Role.SEND, -40, samples = 25)
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
    }

    @Test
    fun `a jerk makes the same jump count sooner than the dwell does`() = runTest {
        // High: a jump held 200 ms fires, or 60 ms with a jerk of 900 milli g.
        val dwell = connectedReceiver()
        dwell.touch("S", Role.SEND, samples = 2)
        assertThat(dwell.of<TransportEvent.Tap>()).isEmpty()
        dwell.touch("S", Role.SEND, samples = 1)
        assertThat(dwell.of<TransportEvent.Tap>()).hasSize(1)

        val jerk = connectedReceiver()
        jerk.clock.advance(100)
        jerk.impulses.jerk(jerk.clock.elapsed)
        jerk.touch("S", Role.SEND, samples = 2)
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
    fun `an offer in hand is let go when the sender withdraws it, or answers a refresh with nothing`() = runTest {
        val rig = connectedReceiver()
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.radio.listener.onOfferRead("S", frame(lastHelloBefore(rig), rig.clock.wall))
        assertThat(rig.of<TransportEvent.OfferPrefetched>()).hasSize(1)
        rig.radio.listener.onNotified("S", SongTapWire.encodeOfferWithdrawn())
        assertThat(rig.of<TransportEvent.OfferGone>().map { it.peer }).containsExactly(peer)

        // The same when a refresh read finds the sender with nothing.
        rig.radio.listener.onNotified("S", SongTapWire.encodeOfferReady())
        rig.radio.listener.onOfferRead("S", frame(lastHello(rig), rig.clock.wall))
        assertThat(rig.of<TransportEvent.OfferPrefetched>()).hasSize(2)
        rig.radio.listener.onNotified("S", SongTapWire.encodeOfferReady())
        rig.radio.listener.onOfferRead("S", null)
        assertThat(rig.of<TransportEvent.OfferGone>()).hasSize(2)
    }

    @Test
    fun `withdrawing tells the phones that are connected to let the offer go`() = runTest {
        val rig = sender()
        rig.transport.publishOffer(rig.peerOf(), payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        rig.radio.notifications.clear()
        rig.transport.withdrawOffer()
        assertThat(rig.radio.notifications.map { it.first }).containsExactly("c")
        assertThat(SongTapWire.isOfferWithdrawn(rig.radio.notifications.single().second)).isTrue()
        // Nothing on offer, nothing to withdraw.
        rig.radio.notifications.clear()
        rig.transport.withdrawOffer()
        assertThat(rig.radio.notifications).isEmpty()
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
        // The gates are on the screen: near stays "tap near" until it falls below the exit level.
        assertThat(diag.snapshot.value.gate).isEqualTo("tap near")
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
    fun `a receiver's tap message is taken at once, without the sender's own detector, from the phone that read the offer`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("reader", SongTapWire.encodeHello(ByteArray(16) { 1 })!!)
        rig.transport.serverControl("other", SongTapWire.encodeHello(ByteArray(16) { 2 })!!)
        // Nobody has read the offer yet: the sender has not "said yes" to anybody's reading, so the message is not believed.
        rig.transport.serverControl("reader", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        assertThat(rig.transport.serverOffer("reader")).isNotNull()
        // A phone that did not read it is not the receiver.
        rig.transport.serverControl("other", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        // The reader's message counts although this phone's own signal for it has not moved at all (resting 20 to 30 cm apart).
        rig.transport.serverControl("reader", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>().map { it.peer }).containsExactly(peer)
    }

    @Test
    fun `a receiver's tap message is ignored when the sender's own signal for it is below the tap near gate, or the phone is not the followed one`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        assertThat(rig.transport.serverOffer("c")).isNotNull()
        // The followed phone drifts to a few metres (-80 is inside the list gate, outside the near gate): the message is not believed.
        repeat(6) { rig.see("R", Role.RECEIVE, BleRig.LIST_ONLY_DBM) }
        rig.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        assertThat(rig.diag.events.value.last().text).contains("too_weak")
        // Another phone coming in strongly does not make a message count either: the sender follows the phone it said yes to.
        rig.settle("Q", Role.RECEIVE, rssi = -40)
        rig.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        // Back close: it counts.
        rig.settle("R", Role.RECEIVE, BleRig.NEAR_DBM, samples = 6)
        rig.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>().map { it.peer }).containsExactly(peer)
    }

    @Test
    fun `the sender accepts one receiver tap per 1200 ms and no echo goes back`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        assertThat(rig.transport.serverOffer("c")).isNotNull()
        rig.radio.notifications.clear()
        rig.transport.serverControl("c", SongTapWire.encodeTap(30))
        rig.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
        // This phone's own detector firing a moment later (the touch is real on both) tells nobody again.
        rig.touch("R", Role.RECEIVE, samples = 4)
        assertThat(rig.radio.notifications.filter { SongTapWire.isTapEvent(it.second) }).isEmpty()
        rig.clock.advance(SongTapPeerTap.MIN_GAP_MS + 100)
        rig.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>().size).isAtLeast(2)
    }

    @Test
    fun `a sender's own tap is told to the phone that read the offer at once, as the single byte 04`() = runTest {
        val rig = sender()
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.transport.serverControl("reader", SongTapWire.encodeHello(ByteArray(16) { 1 })!!)
        // Nobody has read the offer: nobody is told.
        rig.touch("R", Role.RECEIVE, samples = 8)
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
        assertThat(rig.radio.notifications.filter { SongTapWire.isTapEvent(it.second) }).isEmpty()

        val rig2 = sender()
        val peer2 = rig2.peerOf()
        rig2.transport.publishOffer(peer2, payloadBytes)
        rig2.transport.serverControl("reader", SongTapWire.encodeHello(ByteArray(16) { 1 })!!)
        assertThat(rig2.transport.serverOffer("reader")).isNotNull()
        rig2.radio.notifications.clear()
        rig2.touch("R", Role.RECEIVE, samples = 8)
        assertThat(rig2.of<TransportEvent.Tap>()).hasSize(1)
        val told = rig2.radio.notifications.filter { SongTapWire.isTapEvent(it.second) }
        assertThat(told.map { it.first }).containsExactly("reader")
        assertThat(told.single().second.toList()).containsExactly(0x04.toByte())
        // Once only, however long the phones stay together.
        rig2.settle("R", Role.RECEIVE, BleRig.TOUCH_DBM, samples = 20)
        assertThat(rig2.radio.notifications.count { SongTapWire.isTapEvent(it.second) }).isEqualTo(1)
    }

    // ── The one-sided tap, receiver side ─────────────────────────────────────────────

    private fun TestScope.receiverWithOfferInHand(): BleRig {
        val rig = connectedReceiver()
        rig.radio.listener.onOfferRead("S", frame(lastHelloBefore(rig), rig.clock.wall))
        assertThat(rig.of<TransportEvent.OfferPrefetched>()).hasSize(1)
        return rig
    }

    @Test
    fun `the sender's tap event commits the receiver at once, with the phones only resting beside each other`() = runTest {
        val rig = receiverWithOfferInHand()
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        rig.radio.listener.onNotified("S", SongTapWire.encodeTapEvent())
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
        // The session holds the offer from here on; nothing is written back (the sender already knows).
        assertThat(rig.controlWritesDecoded().filterIsInstance<SongTapWire.ControlMessage.Tap>()).isEmpty()
        // The receiver's own detector firing a moment later does not tell the sender again, and the event is not repeated.
        rig.touch("S", Role.SEND, samples = 6)
        assertThat(rig.controlWritesDecoded().filterIsInstance<SongTapWire.ControlMessage.Tap>()).isEmpty()
        assertThat(rig.of<TransportEvent.Tap>().size).isAtMost(2)
    }

    @Test
    fun `the sender's tap event is ignored with no offer in hand, below the near gate, or from a phone that is not followed`() = runTest {
        // No offer in hand: the sender has not said yes.
        val noOffer = connectedReceiver()
        noOffer.radio.listener.onNotified("S", SongTapWire.encodeTapEvent())
        assertThat(noOffer.of<TransportEvent.Tap>()).isEmpty()
        assertThat(noOffer.diag.events.value.last().text).contains("no_yes")

        // The sender is a few metres away: this phone's own signal is below the gate.
        val far = receiverWithOfferInHand()
        repeat(5) { far.see("S", Role.SEND, BleRig.LIST_ONLY_DBM) }
        far.radio.listener.onNotified("S", SongTapWire.encodeTapEvent())
        assertThat(far.of<TransportEvent.Tap>()).isEmpty()
        assertThat(far.diag.events.value.last().text).contains("too_weak")

        // Not the followed phone: an address this phone never saw changes nothing, and another phone's event is not this one's.
        val rig = receiverWithOfferInHand()
        rig.radio.listener.onNotified("stranger", SongTapWire.encodeTapEvent())
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()

        // Garbage that merely starts with the byte is not an event.
        rig.radio.listener.onNotified("S", byteArrayOf(0x04, 0x00))
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
    }

    @Test
    fun `a second tap event inside 1200 ms is ignored`() = runTest {
        val rig = receiverWithOfferInHand()
        rig.radio.listener.onNotified("S", SongTapWire.encodeTapEvent())
        rig.radio.listener.onNotified("S", SongTapWire.encodeTapEvent())
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
    }

    // ── The signal scale and the sensitivity ─────────────────────────────────────────

    @Test
    fun `the offset is added to every raw reading before the gates and the detector`() = runTest {
        // A Pixel resting 20 to 30 cm away reads -44 raw, which is -59 on the iPhone scale: inside the gate, far from the hold level.
        val pixel = BleRig(this)
        pixel.transport.setRssiOffset(-15)
        pixel.transport.start(Role.RECEIVE)
        pixel.settle("S", Role.SEND, -44, samples = 40)
        assertThat(pixel.of<TransportEvent.Tap>()).isEmpty()
        assertThat(pixel.radio.calls).contains("connect:S")
        pixel.diag.setEnabled(true)
        pixel.see("S", Role.SEND, -44)
        assertThat(pixel.diag.snapshot.value.rssi).isEqualTo(-44)
        assertThat(pixel.diag.snapshot.value.offsetDb).isEqualTo(-15)
        assertThat(pixel.diag.snapshot.value.normalised).isEqualTo(-59)
        assertThat(pixel.diag.snapshot.value.smoothed).isEqualTo(-59)

        // The same phone with no offset reads -44 as "a few cm": the hold fires at rest, the old bug.
        val raw = BleRig(this)
        raw.transport.start(Role.RECEIVE)
        raw.settle("S", Role.SEND, -44, samples = 40)
        assertThat(raw.of<TransportEvent.Tap>()).isNotEmpty()
    }

    @Test
    fun `with the offset a phone a few metres away is outside the near gate, without it the same reading is near`() = runTest {
        // iPhone -76 (the lowest of its log, phones apart) is -61 raw on a Pixel.
        val with = BleRig(this)
        with.transport.setRssiOffset(-15)
        with.transport.start(Role.RECEIVE)
        with.settle("S", Role.SEND, -61, samples = 10)
        assertThat(with.of<TransportEvent.PeerNear>()).isEmpty()
        assertThat(with.radio.count("connect")).isEqualTo(0)
        val none = BleRig(this)
        none.transport.start(Role.RECEIVE)
        none.settle("S", Role.SEND, -61, samples = 10)
        assertThat(none.of<TransportEvent.PeerNear>()).isNotEmpty()
        assertThat(none.radio.count("connect")).isEqualTo(1)
    }

    @Test
    fun `changing the offset live restarts the detector on the new scale`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND, -44, samples = 4)
        assertThat(rig.transport.rssiOffset()).isEqualTo(0)
        rig.transport.setRssiOffset(-15)
        assertThat(rig.transport.rssiOffset()).isEqualTo(-15)
        rig.transport.setRssiOffset(-99)
        assertThat(rig.transport.rssiOffset()).isEqualTo(RssiCalibration.MIN_OFFSET_DB)
        rig.transport.setRssiOffset(7)
        assertThat(rig.transport.rssiOffset()).isEqualTo(0)
    }

    @Test
    fun `the sensitivity is applied live, normal needs a firmer jump than high`() = runTest {
        // A 13 dB step that holds: a tap for High (9 dB), nothing for Normal (15 dB).
        fun step(rig: BleRig) {
            rig.transport.start(Role.RECEIVE)
            rig.settle("S", Role.SEND, -64)
            rig.radio.listener.onConnected("S", true)
            repeat(10) { rig.see("S", Role.SEND, -51) }
        }
        val high = BleRig(this)
        high.transport.setSensitivity(SongTapSensitivity.HIGH)
        step(high)
        assertThat(high.of<TransportEvent.Tap>()).hasSize(1)

        val normal = BleRig(this)
        normal.transport.setSensitivity(SongTapSensitivity.NORMAL)
        step(normal)
        assertThat(normal.of<TransportEvent.Tap>()).isEmpty()
        // Live: switching to High while armed counts the next jump.
        normal.settle("S", Role.SEND, -64, samples = 10)
        normal.transport.setSensitivity(SongTapSensitivity.HIGH)
        assertThat(normal.impulses.profile).isEqualTo(SongTapSensitivity.HIGH)
        repeat(10) { normal.see("S", Role.SEND, -51) }
        assertThat(normal.of<TransportEvent.Tap>()).hasSize(1)
    }

    // ── Calibration ──────────────────────────────────────────────────────────────────

    private fun BleRig.hold(ms: Long, rssi: Int) {
        var t = 0L
        while (t < ms) { see("S", Role.SEND, rssi); t += 100L }
    }

    @Test
    fun `holding calibrate for two seconds with the phones touching sets the offset from the 90th percentile`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        // A Pixel touching an iPhone reads about -18 raw.
        rig.settle("S", Role.SEND, -18, samples = 6)
        rig.transport.startCalibration()
        assertThat(rig.transport.calibration.value).isInstanceOf(CalibrationState.Running::class.java)
        rig.hold(RssiCalibration.HOLD_MS + 200L, -18)
        val done = rig.transport.calibration.value as CalibrationState.Done
        assertThat(done.p90Raw).isEqualTo(-18)
        assertThat(done.offsetDb).isEqualTo(-15)
        assertThat(rig.transport.rssiOffset()).isEqualTo(-15)
        assertThat(done.samples).isAtLeast(RssiCalibration.MIN_SAMPLES)
    }

    @Test
    fun `letting go early, no phone, or too few readings is not a calibration`() = runTest {
        val rig = BleRig(this)
        rig.transport.startCalibration()
        assertThat(rig.transport.calibration.value).isEqualTo(CalibrationState.Failed("no_phone"))

        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND, -18, samples = 6)
        rig.transport.startCalibration()
        rig.hold(800, -18)
        rig.transport.endCalibration()
        assertThat(rig.transport.calibration.value).isEqualTo(CalibrationState.Failed("released"))
        assertThat(rig.transport.rssiOffset()).isEqualTo(0)

        // Held two seconds but the radio gave almost nothing.
        rig.transport.startCalibration()
        rig.see("S", Role.SEND, -18, stepMs = 100)
        rig.clock.advance(RssiCalibration.HOLD_MS + 100)
        advanceTimeBy(BleTuning.TICK_MS + 1)
        assertThat(rig.transport.calibration.value).isEqualTo(CalibrationState.Failed("too_few"))
        assertThat(rig.transport.rssiOffset()).isEqualTo(0)

        // A finished calibration is not undone by letting go afterwards.
        rig.transport.startCalibration()
        rig.hold(RssiCalibration.HOLD_MS + 200L, -18)
        rig.transport.endCalibration()
        assertThat(rig.transport.calibration.value).isInstanceOf(CalibrationState.Done::class.java)
    }

    @Test
    fun `the calibration maths`() {
        assertThat(RssiCalibration.percentile90(emptyList())).isNull()
        assertThat(RssiCalibration.percentile90(listOf(-20))).isEqualTo(-20)
        // 10 values: nearest rank 9 of 10.
        assertThat(RssiCalibration.percentile90(listOf(-40, -39, -38, -37, -36, -35, -34, -33, -32, -10))).isEqualTo(-32)
        assertThat(RssiCalibration.offsetFor(-18)).isEqualTo(-15)
        assertThat(RssiCalibration.offsetFor(-33)).isEqualTo(0)
        assertThat(RssiCalibration.offsetFor(-40)).isEqualTo(0) // a weaker reading than the iPhone's: never boosted
        assertThat(RssiCalibration.offsetFor(5)).isEqualTo(-35)
        assertThat(RssiCalibration.offsetFor(0)).isEqualTo(-33)
        assertThat(RssiCalibration.defaultOffsetFor("Pixel 10 Pro")).isEqualTo(-15)
        assertThat(RssiCalibration.defaultOffsetFor("Pixel 7a")).isEqualTo(-15)
        assertThat(RssiCalibration.defaultOffsetFor("SM-S928B")).isEqualTo(-12)
        assertThat(RssiCalibration.defaultOffsetFor(null)).isEqualTo(-12)
    }

    @Test
    fun `a jerk the other phone claims shortens the hold when it is close`() = runTest {
        val close = BleRig(this)
        close.transport.start(Role.RECEIVE)
        close.settle("S", Role.SEND)
        close.radio.listener.onConnected("S", true)
        close.touch("S", Role.SEND, samples = 1)
        close.radio.listener.onInfoRead("S", SongTapWire.encodeInfo(0))
        close.touch("S", Role.SEND, samples = 1)
        assertThat(close.of<TransportEvent.Tap>()).hasSize(1) // the second reading: the dwell needs the third

        // The same word from a phone that is only resting beside this one (-60) is not believed.
        val resting = BleRig(this)
        resting.transport.start(Role.RECEIVE)
        resting.settle("S", Role.SEND)
        resting.radio.listener.onConnected("S", true)
        resting.radio.listener.onInfoRead("S", SongTapWire.encodeInfo(0))
        resting.touch("S", Role.SEND, samples = 2, rssi = -50)
        assertThat(resting.of<TransportEvent.Tap>()).isEmpty()
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
        for (rssi in listOf(-78, -76, -74, -72, -70, -68, -66, -64, -62, -60)) rig.see("R", Role.RECEIVE, rssi)
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
        assertThat(list.map { it.near }).containsExactly(true, false).inOrder() // Bo rests inside the tap near gate, Ann is a few metres away
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
        for (rssi in listOf(-78, -76, -74, -72, -70, -68, -66, -64, -62, -60)) rig.see("R", Role.RECEIVE, rssi, stepMs = 300, name = "Ann")
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
    // ── Follow-up 2: the phone, not the address ──────────────────────────────────────

    private fun BleRig.logged(text: String) = diag.events.value.count { it.text.contains(text) }

    @Test
    fun `a phone that changes its address while armed keeps its number, still gets the offer and its tap is accepted`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R1", Role.RECEIVE, name = "Ann")
        val peer = rig.peerOf()
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16) { 1 })!!)
        rig.transport.publishOffer(peer, payloadBytes)
        assertThat(rig.radio.notifications).hasSize(1)

        // The old address goes quiet and the same name shows up under a new one.
        rig.clock.advance(2_000)
        rig.settle("R2", Role.RECEIVE, name = "Ann")

        assertThat(rig.of<TransportEvent.PeerFound>().map { it.peer }).containsExactly(peer)
        assertThat(rig.of<TransportEvent.PeerLost>()).isEmpty()
        assertThat(rig.logged("changed its address")).isEqualTo(1)
        assertThat(rig.transport.nearby.value.map { it.id }).containsExactly(peer)
        // The phones that are connected are told again there is an offer.
        assertThat(rig.radio.notifications).hasSize(2)
        assertThat(SongTapWire.isOfferReady(rig.radio.notifications.last().second)).isTrue()
        // It is served, and its tap message is believed.
        assertThat(rig.transport.serverOffer("c")).isNotNull()
        rig.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(rig.of<TransportEvent.Tap>().map { it.peer }).containsExactly(peer)
    }

    @Test
    fun `the new address's own tap counts for the pinned phone`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R1", Role.RECEIVE, name = "Ann")
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        rig.clock.advance(2_000)
        rig.settle("R2", Role.RECEIVE, name = "Ann")
        rig.touch("R2", Role.RECEIVE)
        assertThat(rig.of<TransportEvent.Tap>().map { it.peer }).containsExactly(peer)
    }

    @Test
    fun `the old address is not given up for a phone that is still being heard`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R1", Role.RECEIVE, name = "Ann")
        val peer = rig.peerOf()
        rig.transport.publishOffer(peer, payloadBytes)
        // The twin shows up at once, while the pinned address is still talking: nothing is re-pinned.
        rig.settle("R2", Role.RECEIVE, name = "Ann")
        assertThat(rig.logged("changed its address")).isEqualTo(0)
    }

    @Test
    fun `a stale twin of the same phone does not stop the offer being served`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R1", Role.RECEIVE, name = "Ann")
        val chosen = rig.peerOf()
        rig.settle("R2", Role.RECEIVE, name = "Ann")
        rig.transport.publishOffer(chosen, payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16) { 5 })!!)
        assertThat(rig.transport.serverOffer("c")).isNotNull()
    }

    @Test
    fun `two different names near still serve nobody`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R1", Role.RECEIVE, name = "Ann")
        val chosen = rig.peerOf()
        rig.settle("R2", Role.RECEIVE, name = "Bo")
        rig.transport.publishOffer(chosen, payloadBytes)
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16) { 5 })!!)
        assertThat(rig.transport.serverOffer("c")).isNull()
        // Even when one of them changes address and the other stays: a different name is a different phone.
        rig.clock.advance(2_000)
        rig.settle("R3", Role.RECEIVE, name = "Bo")
        assertThat(rig.logged("changed its address")).isEqualTo(0)
        assertThat(rig.transport.serverOffer("c")).isNull()
    }

    @Test
    fun `the prompt stays up while the same named phone changes address, and no silences every address it has`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.SEND)
        rig.settle("R1", Role.RECEIVE, name = "Ann")
        val first = rig.transport.prompt.value!!
        assertThat(first.name).isEqualTo("Ann")
        rig.clock.advance(2_000)
        val seen = ArrayList<String?>()
        repeat(6) {
            rig.see("R2", Role.RECEIVE, BleRig.NEAR_DBM, name = "Ann")
            seen += rig.transport.prompt.value?.name
        }
        assertThat(seen).containsNoneIn(listOf<String?>(null))

        rig.transport.ignorePrompt(rig.transport.prompt.value!!.id)
        assertThat(rig.transport.prompt.value).isNull()
        // A third address of the same phone is not asked about either.
        rig.clock.advance(2_000)
        repeat(6) { rig.see("R3", Role.RECEIVE, BleRig.NEAR_DBM, name = "Ann") }
        assertThat(rig.transport.prompt.value).isNull()
    }

    // ── Follow-up 2: taps with no yes are never silent ───────────────────────────────

    @Test
    fun `a sender that felt a tap with no yes says so in the log and the diagnostics, at most every two seconds, and a yes clears it`() = runTest {
        val diag = SongTapDiagnostics()
        val rig = BleRig(this, diag)
        diag.setEnabled(true)
        rig.transport.start(Role.SEND)
        rig.settle("R", Role.RECEIVE, name = "Ann")
        rig.touch("R", Role.RECEIVE)
        assertThat(rig.of<TransportEvent.Tap>()).isEmpty()
        assertThat(rig.logged("tap ignored: no yes yet")).isEqualTo(1)
        rig.see("R", Role.RECEIVE, BleRig.TOUCH_DBM)
        assertThat(diag.snapshot.value.needsYes).isTrue()
        assertThat(diag.snapshot.value.tapNote).contains("tap ignored: no yes yet")

        // Pull apart and touch again inside two seconds: counted, not logged again.
        rig.settle("R", Role.RECEIVE, samples = 8)
        rig.touch("R", Role.RECEIVE, samples = 4)
        assertThat(rig.logged("tap ignored: no yes yet")).isEqualTo(1)

        // Saying yes clears the hint.
        rig.transport.publishOffer(rig.peerOf(), payloadBytes)
        rig.see("R", Role.RECEIVE, BleRig.TOUCH_DBM, stepMs = 200)
        assertThat(diag.snapshot.value.needsYes).isFalse()
        assertThat(diag.snapshot.value.tapNote).isEmpty()
    }

    // ── Follow-up 2: the radio is not restarted, and a stalled connection gives up ───

    @Test
    fun `starting again with the same role and name does not restart the radio`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.transport.start(Role.RECEIVE)
        rig.transport.start(Role.RECEIVE)
        assertThat(rig.radio.count("start")).isEqualTo(1)
        assertThat(rig.radio.count("stop")).isEqualTo(0)
        assertThat(rig.logged("advertising start (receive)")).isEqualTo(1)
        assertThat(rig.logged("not restarted")).isEqualTo(2)

        // A different role or name is a real change.
        rig.transport.start(Role.SEND)
        assertThat(rig.radio.count("start")).isEqualTo(2)
        assertThat(rig.logged("advertising stop (receive)")).isEqualTo(1)
        assertThat(rig.logged("advertising start (send)")).isEqualTo(1)
        rig.transport.setOwnName("Zed")
        rig.transport.start(Role.SEND)
        assertThat(rig.radio.count("start")).isEqualTo(3)
        assertThat(rig.radio.advertisedName).isEqualTo("Zed")
        // Bluetooth that never came up is tried again.
        val off = BleRig(this)
        off.radio.startStatus = BleStatus.BLUETOOTH_OFF
        off.transport.start(Role.RECEIVE)
        off.radio.startStatus = BleStatus.ACTIVE
        off.transport.start(Role.RECEIVE)
        assertThat(off.radio.count("start")).isEqualTo(2)
    }

    @Test
    fun `a connection that stalls is given up after three seconds, the queue is free, and a late answer is closed`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        rig.settle("S", Role.SEND)
        assertThat(rig.radio.count("connect")).isEqualTo(1)
        // Nothing answers. Before the timeout it is still waited for.
        rig.see("S", Role.SEND, BleRig.NEAR_DBM, stepMs = 1_000)
        rig.see("S", Role.SEND, BleRig.NEAR_DBM, stepMs = 1_000)
        assertThat(rig.radio.count("disconnect")).isEqualTo(0)
        rig.see("S", Role.SEND, BleRig.NEAR_DBM, stepMs = 1_000)
        assertThat(rig.radio.count("disconnect")).isEqualTo(1)
        assertThat(rig.logged("took too long")).isEqualTo(1)
        assertThat(rig.radio.count("connect")).isEqualTo(1)
        // The stalled attempt answers after all: closed, not mistaken for a connection.
        rig.radio.listener.onConnected("S", true)
        assertThat(rig.radio.count("readName")).isEqualTo(0)
        assertThat(rig.radio.count("disconnect")).isEqualTo(2)
        // A new attempt follows after the usual pause.
        rig.see("S", Role.SEND, BleRig.NEAR_DBM, stepMs = BleTuning.RECONNECT_MS + 100)
        assertThat(rig.radio.count("connect")).isEqualTo(2)
    }
}
