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
    fun `a receiver follows a sender, announces it, and connects only when it is near`() = runTest {
        val rig = BleRig(this)
        rig.transport.start(Role.RECEIVE)
        assertThat(rig.transport.status.value).isEqualTo(BleStatus.ACTIVE)
        assertThat(rig.impulses.running).isTrue()

        rig.see("S", Role.SEND, -88)
        assertThat(rig.of<TransportEvent.PeerFound>()).hasSize(1)
        assertThat(rig.radio.count("connect")).isEqualTo(0)

        rig.settle("S", Role.SEND, -60)
        assertThat(rig.of<TransportEvent.PeerNear>()).isNotEmpty()
        assertThat(rig.radio.calls).contains("connect:S")
        assertThat(rig.transport.glow.value).isGreaterThan(0)

        rig.radio.listener.onConnected("S", true)
        val hello = rig.lastControl() as SongTapWire.ControlMessage.Hello
        assertThat(hello.challenge).hasLength(SongTapSigning.CHALLENGE_BYTES)
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
        assertThat(rig.radio.count("readOffer")).isEqualTo(BleTuning.MAX_READ_ATTEMPTS)
        assertThat(rig.of<TransportEvent.OfferInvalid>()).hasSize(1)
    }

    @Test
    fun `ack goes out as a write, its round trip is measured, and go is passed on with it`() = runTest {
        val rig = connectedReceiver()
        rig.touch("S", Role.SEND)
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.transport.sendAck(peer)
        assertThat(rig.lastControl()).isEqualTo(SongTapWire.ControlMessage.Ack)
        rig.clock.advance(46)
        rig.radio.listener.onControlWritten("S", true) // the Tap write's answer (first in line)
        rig.radio.listener.onControlWritten("S", true) // the Hello's
        rig.radio.listener.onControlWritten("S", true) // the Ack's
        rig.radio.listener.onNotified("S", SongTapWire.encodeGo())
        val go = rig.of<TransportEvent.Go>().single()
        assertThat(go.peer).isEqualTo(peer)
        assertThat(go.rttMs).isEqualTo(46L)
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
        rig.see("S", Role.SEND, -60, stepMs = 100)
        assertThat(rig.radio.count("connect")).isEqualTo(1)
        rig.see("S", Role.SEND, -60, stepMs = BleTuning.RECONNECT_MS)
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
        rig.settle("S", Role.SEND, -60)
        assertThat(diag.snapshot.value.rssi).isEqualTo(0)
        diag.setEnabled(true)
        rig.see("S", Role.SEND, -58)
        assertThat(diag.snapshot.value.rssi).isEqualTo(-58)
        assertThat(diag.snapshot.value.focus).contains("sending")
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

    @Test
    fun `a sender gives nothing until a receiver has said hello and an offer is published`() = runTest {
        val rig = sender()
        val client = "client-1"
        assertThat(rig.transport.serverOffer(client)).isNull()
        rig.transport.publishOffer(0, payloadBytes)
        assertThat(rig.transport.serverOffer(client)).isNull() // no challenge yet
        val challenge = ByteArray(16) { (it + 1).toByte() }
        assertThat(rig.transport.serverControl(client, SongTapWire.encodeHello(challenge)!!)).isTrue()
        val signed = rig.transport.serverOffer(client)!!
        val offer = SongTapSigning.decodeFrame(signed)!!
        assertThat(SongTapSigning.check(offer, challenge, rig.clock.wall, com.theveloper.pixelplay.data.songtap.SongTapVerifier)).isEqualTo(com.theveloper.pixelplay.songtap.OfferCheck.OK)
        // A challenge answers one read.
        assertThat(rig.transport.serverOffer(client)).isNull()
    }

    @Test
    fun `a sender signs with a key of its own for each session`() = runTest {
        val a = sender()
        val b = sender()
        val challenge = ByteArray(16) { 7 }
        for (rig in listOf(a, b)) {
            rig.transport.publishOffer(0, payloadBytes)
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
    fun `ack and decline from the receiver reach the session, and go is sent to that receiver`() = runTest {
        val rig = sender()
        val peer = rig.of<TransportEvent.PeerFound>().last().peer
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        rig.transport.serverControl("c", SongTapWire.encodeAck())
        assertThat(rig.of<TransportEvent.Ack>().single().peer).isEqualTo(peer)
        rig.transport.sendGo(peer, 5L)
        assertThat(rig.radio.goNotifications).containsExactly("c")
        rig.transport.serverControl("c", SongTapWire.encodeDecline())
        assertThat(rig.of<TransportEvent.Declined>().single().peer).isEqualTo(peer)
    }

    @Test
    fun `a receiver's tap message counts only when the sender's own signal jumped too`() = runTest {
        val quiet = sender()
        quiet.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(quiet.of<TransportEvent.Tap>()).isEmpty()

        val jumped = sender()
        jumped.touch("R", Role.RECEIVE, samples = 3) // the jump has started but has not held long enough to fire alone
        jumped.transport.serverControl("c", SongTapWire.encodeTap(30))
        assertThat(jumped.of<TransportEvent.Tap>()).isNotEmpty()
    }

    @Test
    fun `a sender whose own detector fires offers by itself`() = runTest {
        val rig = sender()
        rig.touch("R", Role.RECEIVE)
        assertThat(rig.of<TransportEvent.Tap>()).hasSize(1)
        assertThat(rig.of<TransportEvent.PeerRole>().single().role).isEqualTo(Role.RECEIVE)
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
    fun `a receiver leaving after an offer was published is a lost peer, before that it is nothing`() = runTest {
        val rig = sender()
        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        rig.transport.serverClientGone("c")
        assertThat(rig.of<TransportEvent.PeerLost>()).isEmpty()

        rig.transport.serverControl("c", SongTapWire.encodeHello(ByteArray(16))!!)
        rig.transport.publishOffer(0, payloadBytes)
        rig.transport.serverClientGone("c")
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
        rig.transport.publishOffer(0, bytes)
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
        s.transport.publishOffer(0, payloadBytes)
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
        assertThat(rig.events.filterNot { it is TransportEvent.PeerFound || it is TransportEvent.PeerNear }).isEmpty()
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
