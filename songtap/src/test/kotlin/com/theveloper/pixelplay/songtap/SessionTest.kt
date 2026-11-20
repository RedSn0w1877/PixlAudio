package com.theveloper.pixelplay.songtap

import com.theveloper.pixelplay.songtap.SessionEvent.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionTest {

    private val v1 = "dQw4w9WgXcQ"
    private val v2 = "aaaaaaaaaaa"

    private fun run(ask: Boolean, vararg events: SessionEvent) = SongTapSession.run(SessionState.initial(ask), events.toList())
    private fun List<Reduction>.last() = this[size - 1]
    private fun Reduction.fx() = effects.map { it.describe() }

    @Test
    fun `the sender's happy path`() {
        val r = run(true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, v1), AckReceived(1000, 80), BeamFinished)
        assertEquals(listOf("startTransport(send)"), r[0].fx())
        assertEquals(Phase.NEAR, r[2].state.phase)
        assertEquals(listOf("publishOffer(1,$v1)", "haptic(tick)", "scheduleTimeout(ack,45000)"), r[3].fx())
        assertEquals(Phase.OFFERED, r[3].state.phase)
        assertEquals(listOf("sendGo(1,1150)", "startBeam(send,1150)"), r[4].fx())
        assertEquals(Phase.TRANSFERRING, r[4].state.phase)
        assertEquals(Phase.SAVED, r[5].state.phase)
        assertEquals(listOf("haptic(success)"), r[5].fx())
    }

    @Test
    fun `the receiver asks the first time and saves after a yes`() {
        val r = run(true, Arm(Role.RECEIVE, true), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 1000), Accept(1500), SaveResult(SaveOutcome.SAVED, 1800), GoReceived(1900, 60))
        assertEquals(listOf("readOffer(1)", "haptic(tick)", "scheduleTimeout(read,8000)"), r[3].fx())
        assertEquals(Phase.OFFERED, r[4].state.phase)
        assertEquals(listOf("scheduleTimeout(consent,20000)"), r[4].fx())
        // The sender is told only once the song is saved.
        assertEquals(listOf("saveSong($v1)", "scheduleTimeout(save,20000)"), r[5].fx())
        assertFalse(r[5].state.askFirst)
        assertEquals(Phase.SAVED, r[6].state.phase)
        assertEquals(listOf("sendAck(1)", "haptic(success)"), r[6].fx())
        assertEquals(listOf("startBeam(receive,2020)"), r[7].fx())
    }

    @Test
    fun `later beams save at once and undo works once`() {
        val r = run(false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 1000), SaveResult(SaveOutcome.SAVED, 1400), Undo(3000), Undo(3100))
        assertEquals(listOf("saveSong($v1)", "scheduleTimeout(save,20000)"), r[4].fx())
        assertEquals(listOf("sendAck(1)", "haptic(success)"), r[5].fx())
        assertEquals(listOf("undoSave($v1)"), r[6].fx())
        assertEquals(Phase.ARMED, r[6].state.phase)
        assertTrue(r[7].fx().isEmpty())
    }

    @Test
    fun `a qr beam has no handshake`() {
        val r = run(false, Arm(Role.RECEIVE, false), OfferRejected, OfferReceived(0, v1, 0, 5000), SaveResult(SaveOutcome.SAVED, 5100))
        assertEquals(listOf("noticeInvalidCode"), r[1].fx())
        assertEquals(Phase.ARMED, r[1].state.phase)
        assertEquals(listOf("saveSong($v1)", "scheduleTimeout(save,20000)", "startBeam(receive,5000)"), r[2].fx())
        assertEquals(Phase.SAVED, r[3].state.phase)
    }

    @Test
    fun `already in the library has nothing to undo`() {
        val r = run(false, Arm(Role.RECEIVE, false), OfferReceived(0, v1, 0, 5000), SaveResult(SaveOutcome.ALREADY_HAD, 5100), Undo(5200))
        assertTrue(r[2].state.alreadyHad)
        assertEquals(Phase.SAVED, r[3].state.phase)
        assertTrue(r[3].fx().isEmpty())
    }

    @Test
    fun `not found saves nothing`() {
        val r = run(false, Arm(Role.RECEIVE, false), OfferReceived(0, v1, 0, 5000), SaveResult(SaveOutcome.NOT_FOUND, 5100))
        assertEquals(Phase.FAILED, r[2].state.phase)
        assertEquals(FailReason.NOT_FOUND, r[2].state.fail)
        assertEquals(listOf("notice(not_found)", "haptic(warn)"), r[2].fx())
    }

    @Test
    fun `both phones sending is a conflict`() {
        val a = run(true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), PeerRole(1, Role.SEND))
        assertEquals(FailReason.BOTH_SENDING, a.last().state.fail)
        val b = run(true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, v1), OfferReceived(1, v2, 5, 1000))
        assertEquals(FailReason.BOTH_SENDING, b.last().state.fail)
        assertTrue(b.last().fx().contains("abortTransfer(1)"))
    }

    @Test
    fun `a dropped peer fails a beam that was still being read and aborts it`() {
        val r = run(false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), PeerLost(1))
        assertEquals(FailReason.PEER_LOST, r.last().state.fail)
        assertEquals(listOf("abortTransfer(1)", "haptic(warn)"), r.last().fx())
    }

    @Test
    fun `a receiver that holds a checked offer keeps saving when the phones come apart`() {
        val r = run(false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 1000), PeerLost(1), SaveResult(SaveOutcome.SAVED, 1500))
        assertEquals(Phase.TRANSFERRING, r[5].state.phase)
        assertEquals(0, r[5].state.peer)
        assertTrue(r[5].fx().isEmpty())
        // There is no one to answer, so nobody is acknowledged, and the song is still saved.
        assertEquals(Phase.SAVED, r[6].state.phase)
        assertEquals(listOf("haptic(success)"), r[6].fx())
    }

    @Test
    fun `the first beam's Add still works after the phones come apart, and the beam plays at once`() {
        val r = run(true, Arm(Role.RECEIVE, true), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 1000), PeerLost(1), Accept(9_000), SaveResult(SaveOutcome.SAVED, 9_200))
        assertEquals(Phase.OFFERED, r[5].state.phase)
        assertEquals(v1, r[5].state.videoId)
        assertTrue(r[5].fx().isEmpty())
        assertEquals(listOf("saveSong($v1)", "scheduleTimeout(save,20000)", "startBeam(receive,9000)"), r[6].fx())
        assertEquals(Phase.SAVED, r[7].state.phase)
    }

    @Test
    fun `a failed save tells the sender and counts towards the limits`() {
        val r = run(false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 10_000), SaveResult(SaveOutcome.NOT_FOUND, 10_100), Dismiss,
            TapDetected(1, ""), OfferReceived(1, v2, 7, 11_000))
        assertEquals(listOf("sendDecline(1)", "notice(not_found)", "haptic(warn)"), r[5].fx())
        assertEquals(FailReason.TOO_SOON, r.last().state.fail)
    }

    @Test
    fun `a beam of a song the listener has is never undone by a teardown`() {
        // Cancel, disarm and the timeouts only stop the transfer: the save's own outcome decides whether anything is taken back.
        val cancel = run(false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 1000), Cancel)
        assertTrue(cancel.last().fx().none { it.startsWith("undoSave") })
        val timeout = run(false, Arm(Role.RECEIVE, false), OfferReceived(0, v1, 0, 1000), Timeout(TimeoutTag.SAVE))
        assertTrue(timeout.last().fx().none { it.startsWith("undoSave") })
    }

    @Test
    fun `every fifth beam saved without asking makes the next one ask again`() {
        val events = ArrayList<SessionEvent>()
        events += Arm(Role.RECEIVE, false)
        var now = 10_000L
        for (i in 0 until SongTapSession.ASK_AGAIN_AFTER) {
            events += OfferReceived(0, "video$i".padEnd(11, 'x'), 0, now)
            events += SaveResult(SaveOutcome.SAVED, now + 100)
            events += Dismiss
            now += 10_000L
        }
        val r = SongTapSession.run(SessionState.initial(false), events)
        // After four saves it still does not ask; after the fifth it does.
        assertFalse(r[3 * 4].state.askFirst)
        assertTrue(r.last().state.askFirst)
        val next = SongTapSession.reduce(r.last().state, OfferReceived(0, "zzzzzzzzzzz", 0, now))
        assertEquals(Phase.OFFERED, next.state.phase)
    }

    @Test
    fun `cancel mid transfer stops the transfer and a late result is ignored`() {
        val r = run(false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 1000), Cancel, SaveResult(SaveOutcome.SAVED, 1100))
        assertEquals(Phase.CANCELLED, r[5].state.phase)
        assertEquals(listOf("abortTransfer(1)"), r[5].fx())
        assertEquals(Phase.CANCELLED, r[6].state.phase)
        assertTrue(r[6].fx().isEmpty())
    }

    @Test
    fun `late and duplicate events change nothing`() {
        val r = run(true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, v1), Cancel, AckReceived(900, 50), TapDetected(1, v1))
        assertEquals(Phase.CANCELLED, r[6].state.phase)
        assertTrue(r[5].fx().isEmpty() && r[6].fx().isEmpty())
        val d = run(false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 1000), OfferReceived(1, v2, 7, 1010))
        assertEquals(v1, d.last().state.videoId)
        assertTrue(d.last().fx().isEmpty())
        val idle = run(true, PeerFound(1), TapDetected(1, v1), OfferReceived(0, v1, 1, 0), Accept(0), Undo(0), Dismiss, Cancel, Timeout(TimeoutTag.ACK))
        for (step in idle) { assertEquals(Phase.IDLE, step.state.phase); assertTrue(step.effects.isEmpty()) }
    }

    @Test
    fun `timeouts only fire in the phase that waits for them`() {
        val a = run(true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, v1), Timeout(TimeoutTag.READ), Timeout(TimeoutTag.ACK))
        assertEquals(Phase.OFFERED, a[4].state.phase)
        assertEquals(FailReason.TIMEOUT, a[5].state.fail)
        val c = run(true, Arm(Role.RECEIVE, true), OfferReceived(0, v1, 1, 0), Timeout(TimeoutTag.CONSENT))
        assertEquals(Phase.DECLINED, c.last().state.phase)
    }

    @Test
    fun `limits stop a second beam within three seconds and the same song within a minute`() {
        val r = run(false, Arm(Role.RECEIVE, false), OfferReceived(0, v1, 3, 10_000), SaveResult(SaveOutcome.SAVED, 10_100), Dismiss,
            OfferReceived(0, v2, 3, 11_000), Dismiss, OfferReceived(0, v1, 3, 20_000), Dismiss, OfferReceived(0, v2, 3, 14_000))
        assertEquals(FailReason.TOO_SOON, r[4].state.fail)
        assertEquals(FailReason.DUPLICATE, r[6].state.fail)
        assertEquals(Phase.TRANSFERRING, r[8].state.phase)
    }

    @Test
    fun `two undos mute that sender for an hour`() {
        val r = run(false, Arm(Role.RECEIVE, false),
            OfferReceived(0, v1, 9, 10_000), SaveResult(SaveOutcome.SAVED, 10_100), Undo(11_000),
            OfferReceived(0, v2, 9, 20_000), SaveResult(SaveOutcome.SAVED, 20_100), Undo(21_000),
            OfferReceived(0, "bbbbbbbbbbb", 9, 30_000), Dismiss, OfferReceived(0, "bbbbbbbbbbb", 8, 31_000))
        assertEquals(FailReason.MUTED, r[7].state.fail)
        assertEquals(Phase.TRANSFERRING, r[9].state.phase)
        val later = run(false, Arm(Role.RECEIVE, false),
            OfferReceived(0, v1, 9, 10_000), SaveResult(SaveOutcome.SAVED, 10_100), Undo(11_000),
            OfferReceived(0, v2, 9, 20_000), SaveResult(SaveOutcome.SAVED, 20_100), Undo(21_000),
            OfferReceived(0, "bbbbbbbbbbb", 9, 21_000 + 3_600_001))
        assertEquals(Phase.TRANSFERRING, later.last().state.phase)
    }

    @Test
    fun `code beams have no sender to mute`() {
        val r = run(false, Arm(Role.RECEIVE, false),
            OfferReceived(0, v1, 0, 10_000), SaveResult(SaveOutcome.SAVED, 10_100), Undo(11_000),
            OfferReceived(0, v2, 0, 20_000), SaveResult(SaveOutcome.SAVED, 20_100), Undo(21_000),
            OfferReceived(0, "bbbbbbbbbbb", 0, 30_000))
        assertEquals(Phase.TRANSFERRING, r.last().state.phase)
    }

    @Test
    fun `nothing to send is a notice and not an offer`() {
        val r = run(true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, ""))
        assertEquals(FailReason.NOTHING_TO_SEND, r.last().state.fail)
        val q = run(true, Arm(Role.SEND, true), QrShown(""))
        assertEquals(FailReason.NOTHING_TO_SEND, q.last().state.fail)
    }

    @Test
    fun `switching role restarts the transport only while nothing is offered`() {
        val r = run(true, Arm(Role.SEND, true), SetRole(Role.RECEIVE), PeerFound(1), PeerNear(1), TapDetected(1, ""), SetRole(Role.SEND))
        assertEquals(listOf("stopTransport", "startTransport(receive)"), r[1].fx())
        assertEquals(Role.RECEIVE, r[5].state.role)
        assertTrue(r[5].fx().isEmpty())
    }

    @Test
    fun `disarm tears everything down`() {
        val r = run(false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, v1, 7, 1000), Disarm, Disarm)
        assertEquals(Phase.IDLE, r[5].state.phase)
        assertEquals(listOf("abortTransfer(1)", "stopTransport"), r[5].fx())
        assertTrue(r[6].fx().isEmpty())
    }

    @Test
    fun `a sender's decline reaches the sender`() {
        val r = run(true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, v1), PeerDeclined(2), PeerDeclined(1))
        assertEquals(Phase.OFFERED, r[4].state.phase)
        assertEquals(Phase.DECLINED, r[5].state.phase)
    }

    @Test
    fun `every random stream ends in a state that can still be left`() {
        val rng = SplitMix64(99)
        for (n in 0 until 300) {
            var state = SessionState.initial(rng.nextInt(2) == 0)
            var now = 0L
            for (i in 0 until 40) {
                now += rng.nextInt(5000)
                val e: SessionEvent = when (rng.nextInt(10)) {
                    0 -> Arm(Role.entries[rng.nextInt(2)], true)
                    1 -> PeerFound(1 + rng.nextInt(2))
                    2 -> PeerNear(1 + rng.nextInt(2))
                    3 -> TapDetected(1 + rng.nextInt(2), if (rng.nextInt(2) == 0) v1 else "")
                    4 -> OfferReceived(rng.nextInt(2), v1, rng.nextInt(3), now)
                    5 -> AckReceived(now, 10)
                    6 -> SaveResult(SaveOutcome.entries[rng.nextInt(4)], now)
                    7 -> Cancel
                    8 -> Accept(now)
                    else -> Dismiss
                }
                state = SongTapSession.reduce(state, e).state
            }
            val out = SongTapSession.reduce(SongTapSession.reduce(state, Cancel).state, Disarm).state
            assertEquals(Phase.IDLE, SongTapSession.reduce(out, Disarm).state.phase)
        }
    }
}
