package com.theveloper.pixelplay.songtap

/**
 * One Song Tap session as a pure `reduce(state, event) -> (state, effects)`: no clock, no radio, no
 * storage. A transport (QR now, Bluetooth next) turns what it sees into [SessionEvent]s, the controller
 * performs the [SessionEffect]s, and both platforms are proven to agree by golden event traces.
 *
 * Phases: IDLE (Song Tap is off), ARMED (on, nobody around), FOUND (a peer is in range), NEAR (a peer is
 * close), OFFERED (a real offer exists and waits for a decision: the sender for an acknowledgement, the
 * receiver for Add / Not now), TRANSFERRING (bytes or a save in flight, then the beam), SAVED, DECLINED,
 * FAILED, CANCELLED. The last four are shown for a moment and then [SessionEvent.Dismiss] returns to ARMED.
 *
 * An event that does not make sense in the current phase (a late acknowledgement after a cancel, a
 * duplicate offer, a second tap) changes nothing and has no effects.
 */
enum class Role(val code: String) { SEND("send"), RECEIVE("receive") }

enum class Phase(val code: String) {
    IDLE("idle"), ARMED("armed"), FOUND("found"), NEAR("near"), OFFERED("offered"),
    TRANSFERRING("transferring"), SAVED("saved"), DECLINED("declined"), FAILED("failed"), CANCELLED("cancelled"),
}

enum class FailReason(val code: String) {
    NOTHING_TO_SEND("nothing_to_send"),
    BOTH_SENDING("both_sending"),
    PEER_LOST("peer_lost"),
    TIMEOUT("timeout"),
    INVALID_OFFER("invalid_offer"),
    NOT_FOUND("not_found"),
    SAVE_FAILED("save_failed"),
    TOO_SOON("too_soon"),
    LIMIT("limit"),
    DUPLICATE("duplicate"),
    MUTED("muted"),
}

enum class SaveOutcome(val code: String) { SAVED("saved"), ALREADY_HAD("already_had"), NOT_FOUND("not_found"), FAILED("failed") }

enum class TimeoutTag(val code: String) { ACK("ack"), READ("read"), CONSENT("consent"), SAVE("save"), TARGET("target") }

enum class HapticCue(val code: String) { TICK("tick"), CONFIRM("confirm"), SUCCESS("success"), WARN("warn") }

data class SessionState(
    val phase: Phase,
    val role: Role,
    /** The peer's number as the transport gave it, 0 for none (and for a QR code, which has no peer). */
    val peer: Int,
    /** The song in play in this session: the one offered, or the one received; empty before there is one. */
    val videoId: String,
    val fail: FailReason?,
    /** The first beam a receiver ever gets asks (Add / Not now); cleared once the listener has said yes. */
    val askFirst: Boolean,
    /** The song was already in the library: nothing was added and there is nothing to undo. */
    val alreadyHad: Boolean,
    /** The number [SongTapSigning.senderKey] gave the sender of the offer on the table, 0 when unknown. */
    val senderKey: Int,
    val limits: RateLimits,
    /** Beams saved without asking since the listener last said yes; at [SongTapSession.ASK_AGAIN_AFTER] the next one asks again. */
    val autoSaves: Int = 0,
    /** Sender: the peer the listener said "Song drop to X?" yes to (spec 5); 0 when none. The offer is published for that phone only. */
    val target: Int = 0,
    /** Sender: the target has read the offer (so the data is already in hand over there). */
    val offerRead: Boolean = false,
    /** Sender: a tap came before the target had read the offer; the beam starts when the read arrives. */
    val tapSeen: Boolean = false,
    /** Sender: the receiver acknowledged (it saved). */
    val acked: Boolean = false,
    /** Sender: the beam has played out; the session ends when the acknowledgement is also in. */
    val beamDone: Boolean = false,
    /** Receiver: a checked offer from [peer] is in hand ([videoId], [senderKey]) and waits for the tap (spec 5 prefetch). */
    val pre: Boolean = false,
) {
    fun describe(): String =
        "${phase.code} role=${role.code} peer=$peer vid=${videoId.ifEmpty { "-" }} fail=${fail?.code ?: "-"} " +
            "ask=${if (askFirst) 1 else 0} had=${if (alreadyHad) 1 else 0} key=$senderKey" +
            (if (target != 0) " tgt=$target" else "") +
            (if (offerRead) " read=1" else "") +
            (if (tapSeen) " tapseen=1" else "") +
            (if (acked) " acked=1" else "") +
            (if (beamDone) " beamdone=1" else "") +
            (if (pre) " pre=1" else "")

    companion object {
        fun initial(askFirst: Boolean = true): SessionState =
            SessionState(Phase.IDLE, Role.SEND, 0, "", null, askFirst, false, 0, RateLimits())
    }
}

sealed interface SessionEvent {
    fun describe(): String

    data class Arm(val role: Role, val askFirst: Boolean) : SessionEvent {
        override fun describe() = "arm(${role.code},${if (askFirst) 1 else 0})"
    }
    data object Disarm : SessionEvent { override fun describe() = "disarm" }
    data class SetRole(val role: Role) : SessionEvent { override fun describe() = "setRole(${role.code})" }
    data class PeerFound(val peer: Int) : SessionEvent { override fun describe() = "peerFound($peer)" }
    data class PeerLost(val peer: Int) : SessionEvent { override fun describe() = "peerLost($peer)" }
    data class PeerNear(val peer: Int) : SessionEvent { override fun describe() = "peerNear($peer)" }
    data class PeerFar(val peer: Int) : SessionEvent { override fun describe() = "peerFar($peer)" }
    data class PeerRole(val peer: Int, val role: Role) : SessionEvent { override fun describe() = "peerRole($peer,${role.code})" }
    data class PeerDeclined(val peer: Int) : SessionEvent { override fun describe() = "peerDeclined($peer)" }

    /**
     * The detector fired for [peer] (or, for a sender, the receiver told it so); [videoId] is what plays now on this phone
     * (empty when nothing does), [nowMs] this phone's clock: it is the moment of the commit.
     */
    data class TapDetected(val peer: Int, val videoId: String, val nowMs: Long = 0L) : SessionEvent {
        override fun describe() = "tap($peer,${videoId.ifEmpty { "-" }},$nowMs)"
    }

    /** Sender: the listener said yes to "Song drop to X?" for [peer]; the offer for [videoId] is published for that phone only. */
    data class Approve(val peer: Int, val videoId: String) : SessionEvent {
        override fun describe() = "approve($peer,${videoId.ifEmpty { "-" }})"
    }

    /** Sender: the song changed, or the offer is being refreshed: publish [videoId] again for the target (a new read is needed). */
    data class Republish(val videoId: String) : SessionEvent { override fun describe() = "republish(${videoId.ifEmpty { "-" }})" }

    /** Sender: the target phone has read the offer. */
    data class OfferRead(val peer: Int, val nowMs: Long) : SessionEvent { override fun describe() = "offerRead($peer,$nowMs)" }

    /** Sender: the phone that read the offer went away (before the beam). */
    data class OfferReadLost(val peer: Int) : SessionEvent { override fun describe() = "offerReadLost($peer)" }

    /** Receiver: a valid, checked offer from [peer] was fetched before any tap and is now in hand (spec 5 prefetch). */
    data class OfferPrefetched(val peer: Int, val videoId: String, val senderKey: Int) : SessionEvent {
        override fun describe() = "offerPrefetched($peer,$videoId,$senderKey)"
    }

    /** Receiver: the offer in hand is no longer valid (the sender withdrew it or changed it). */
    data class OfferGone(val peer: Int) : SessionEvent { override fun describe() = "offerGone($peer)" }

    /** The sender put its QR code up for [videoId]. */
    data class QrShown(val videoId: String) : SessionEvent { override fun describe() = "qrShown($videoId)" }

    /** A valid offer arrived (checked by the transport): from [peer] (0 for a QR code), made by [senderKey]. */
    data class OfferReceived(val peer: Int, val videoId: String, val senderKey: Int, val nowMs: Long) : SessionEvent {
        override fun describe() = "offer($peer,$videoId,$senderKey,$nowMs)"
    }

    /** Something arrived that is not a valid offer (a stranger's QR code, a bad signature, a stale frame). */
    data object OfferRejected : SessionEvent { override fun describe() = "offerRejected" }

    data class AckReceived(val nowMs: Long, val rttMs: Long) : SessionEvent {
        override fun describe() = "ack($nowMs,$rttMs)"
    }
    data object BeamFinished : SessionEvent { override fun describe() = "beamFinished" }
    data class Accept(val nowMs: Long) : SessionEvent { override fun describe() = "accept($nowMs)" }
    data object Decline : SessionEvent { override fun describe() = "decline" }
    data class SaveResult(val outcome: SaveOutcome, val nowMs: Long) : SessionEvent {
        override fun describe() = "saveResult(${outcome.code},$nowMs)"
    }
    data class Undo(val nowMs: Long) : SessionEvent { override fun describe() = "undo($nowMs)" }
    data object Cancel : SessionEvent { override fun describe() = "cancel" }
    data object Dismiss : SessionEvent { override fun describe() = "dismiss" }
    data class Timeout(val tag: TimeoutTag) : SessionEvent { override fun describe() = "timeout(${tag.code})" }
}

sealed interface SessionEffect {
    fun describe(): String

    data class StartTransport(val role: Role) : SessionEffect { override fun describe() = "startTransport(${role.code})" }
    data object StopTransport : SessionEffect { override fun describe() = "stopTransport" }
    data class PublishOffer(val peer: Int, val videoId: String) : SessionEffect { override fun describe() = "publishOffer($peer,$videoId)" }
    data class ReadOffer(val peer: Int) : SessionEffect { override fun describe() = "readOffer($peer)" }
    data class SendAck(val peer: Int) : SessionEffect { override fun describe() = "sendAck($peer)" }
    data class SendDecline(val peer: Int) : SessionEffect { override fun describe() = "sendDecline($peer)" }
    data class StartBeam(val role: Role, val startMs: Long) : SessionEffect { override fun describe() = "startBeam(${role.code},$startMs)" }
    data class SaveSong(val videoId: String) : SessionEffect { override fun describe() = "saveSong($videoId)" }
    data class UndoSave(val videoId: String) : SessionEffect { override fun describe() = "undoSave($videoId)" }
    data class AbortTransfer(val peer: Int) : SessionEffect { override fun describe() = "abortTransfer($peer)" }
    data class Haptic(val cue: HapticCue) : SessionEffect { override fun describe() = "haptic(${cue.code})" }
    data class ScheduleTimeout(val tag: TimeoutTag, val afterMs: Long) : SessionEffect { override fun describe() = "scheduleTimeout(${tag.code},$afterMs)" }
    data class Notice(val reason: FailReason) : SessionEffect { override fun describe() = "notice(${reason.code})" }
    data object NoticeInvalidCode : SessionEffect { override fun describe() = "noticeInvalidCode" }
}

data class Reduction(val state: SessionState, val effects: List<SessionEffect>)

object SongTapSession {

    /**
     * How long a sender waits to hear back. The receiver may first ask its listener (up to [CONSENT_TIMEOUT_MS]) and then
     * save (up to [SAVE_TIMEOUT_MS]) before it answers, so this is longer than both together.
     */
    const val ACK_TIMEOUT_MS = 45_000L
    const val READ_TIMEOUT_MS = 8_000L
    const val CONSENT_TIMEOUT_MS = 20_000L

    /** How long a sender stays armed to a phone it said yes to, waiting for the tap. */
    const val TARGET_TIMEOUT_MS = 60_000L
    const val SAVE_TIMEOUT_MS = 20_000L

    /** After this many beams saved without asking, the next one asks (Add / Not now) again. */
    const val ASK_AGAIN_AFTER = 5

    /** How long the saved card offers Undo. */
    const val UNDO_WINDOW_MS = 6_000L

    private val NONE = emptyList<SessionEffect>()

    private fun stay(s: SessionState) = Reduction(s, NONE)

    fun reduce(s: SessionState, e: SessionEvent): Reduction = when (e) {
        is SessionEvent.Arm -> arm(s, e)
        SessionEvent.Disarm -> disarm(s)
        is SessionEvent.SetRole -> setRole(s, e.role)
        is SessionEvent.PeerFound -> peerFound(s, e.peer)
        is SessionEvent.PeerLost -> peerLost(s, e.peer)
        is SessionEvent.PeerNear -> peerNear(s, e.peer)
        is SessionEvent.PeerFar -> peerFar(s, e.peer)
        is SessionEvent.PeerRole -> peerRole(s, e)
        is SessionEvent.PeerDeclined -> peerDeclined(s, e.peer)
        is SessionEvent.TapDetected -> tap(s, e)
        is SessionEvent.Approve -> approve(s, e)
        is SessionEvent.Republish -> republish(s, e.videoId)
        is SessionEvent.OfferRead -> offerRead(s, e)
        is SessionEvent.OfferReadLost -> offerReadLost(s, e.peer)
        is SessionEvent.OfferPrefetched -> offerPrefetched(s, e)
        is SessionEvent.OfferGone -> offerGone(s, e.peer)
        is SessionEvent.QrShown -> qrShown(s, e.videoId)
        is SessionEvent.OfferReceived -> offerReceived(s, e)
        SessionEvent.OfferRejected -> offerRejected(s)
        is SessionEvent.AckReceived -> ackReceived(s, e)
        SessionEvent.BeamFinished -> beamFinished(s)
        is SessionEvent.Accept -> accept(s, e.nowMs)
        SessionEvent.Decline -> decline(s)
        is SessionEvent.SaveResult -> saveResult(s, e)
        is SessionEvent.Undo -> undo(s, e)
        SessionEvent.Cancel -> cancel(s)
        SessionEvent.Dismiss -> dismiss(s)
        is SessionEvent.Timeout -> timeout(s, e.tag)
    }

    /** Runs [events] from [start]; the states and the effects after each one. */
    fun run(start: SessionState, events: List<SessionEvent>): List<Reduction> {
        val out = ArrayList<Reduction>(events.size)
        var state = start
        for (event in events) {
            val r = reduce(state, event)
            out.add(r)
            state = r.state
        }
        return out
    }

    private fun isTerminal(p: Phase) = p == Phase.SAVED || p == Phase.DECLINED || p == Phase.FAILED || p == Phase.CANCELLED

    private fun clear(s: SessionState, phase: Phase, role: Role = s.role) =
        s.copy(
            phase = phase, role = role, peer = 0, videoId = "", fail = null, alreadyHad = false, senderKey = 0,
            target = 0, offerRead = false, tapSeen = false, acked = false, beamDone = false, pre = false,
        )

    private fun arm(s: SessionState, e: SessionEvent.Arm): Reduction {
        if (s.phase != Phase.IDLE && !isTerminal(s.phase)) return stay(s)
        return Reduction(
            clear(s, Phase.ARMED, e.role).copy(askFirst = e.askFirst),
            listOf(SessionEffect.StartTransport(e.role)),
        )
    }

    /**
     * What to stop when a session is torn down in the middle of something. A save that is still running is not undone
     * here: its outcome is not known yet, and a song the listener already had must never be removed. The controller
     * takes back a save that finishes after the session is gone, and only when that save really added the song.
     */
    private fun teardown(s: SessionState): List<SessionEffect> {
        val inFlight = s.phase == Phase.OFFERED || s.phase == Phase.TRANSFERRING
        return if (inFlight && s.peer != 0) listOf(SessionEffect.AbortTransfer(s.peer)) else NONE
    }

    private fun disarm(s: SessionState): Reduction {
        if (s.phase == Phase.IDLE) return stay(s)
        return Reduction(clear(s, Phase.IDLE), teardown(s) + SessionEffect.StopTransport)
    }

    private fun setRole(s: SessionState, role: Role): Reduction {
        if (s.role == role) return stay(s)
        return when (s.phase) {
            Phase.ARMED, Phase.FOUND, Phase.NEAR ->
                Reduction(clear(s, Phase.ARMED, role), listOf(SessionEffect.StopTransport, SessionEffect.StartTransport(role)))
            else -> stay(s)
        }
    }

    private fun peerFound(s: SessionState, peer: Int): Reduction {
        if (peer == 0) return stay(s)
        return if (s.phase == Phase.ARMED) Reduction(s.copy(phase = Phase.FOUND, peer = peer), NONE) else stay(s)
    }

    private fun peerLost(s: SessionState, peer: Int): Reduction {
        if (s.peer == 0 || s.peer != peer) return stay(s)
        return when (s.phase) {
            Phase.FOUND, Phase.NEAR -> Reduction(s.copy(phase = Phase.ARMED, peer = 0, pre = false, videoId = "", senderKey = 0), NONE)
            // A sender armed to a phone it said yes to keeps waiting for the tap when that phone's link drops or it is out of
            // sight for a moment: only the read is lost. The listener cancels, or the target timeout does.
            Phase.OFFERED -> if (s.role == Role.SEND && s.target != 0) Reduction(s.copy(offerRead = false), NONE) else peerLostMidBeam(s)
            Phase.TRANSFERRING -> peerLostMidBeam(s)
            else -> stay(s)
        }
    }

    private fun peerLostMidBeam(s: SessionState): Reduction {
        // After the receiver's acknowledgement the sender has nothing left to lose.
        if (s.role == Role.SEND && s.acked) return stay(s)
        return when (s.phase) {
            Phase.OFFERED, Phase.TRANSFERRING ->
                // A receiver that holds a checked offer does not need the sender any more: pulling the phones apart after
                // a tap is normal, and Add / save must still work. Only a beam still being read is lost.
                if (s.role == Role.RECEIVE && s.videoId.isNotEmpty()) Reduction(s.copy(peer = 0), NONE)
                else Reduction(fail(s, FailReason.PEER_LOST), teardown(s) + SessionEffect.Haptic(HapticCue.WARN))
            else -> stay(s)
        }
    }

    private fun peerNear(s: SessionState, peer: Int): Reduction =
        if (s.phase == Phase.FOUND && s.peer == peer) Reduction(s.copy(phase = Phase.NEAR), NONE) else stay(s)

    private fun peerFar(s: SessionState, peer: Int): Reduction =
        if (s.phase == Phase.NEAR && s.peer == peer) Reduction(s.copy(phase = Phase.FOUND), NONE) else stay(s)

    private fun peerRole(s: SessionState, e: SessionEvent.PeerRole): Reduction {
        if (s.peer != e.peer || s.peer == 0) return stay(s)
        val active = s.phase == Phase.FOUND || s.phase == Phase.NEAR || s.phase == Phase.OFFERED || s.phase == Phase.TRANSFERRING
        if (active && s.role == Role.SEND && e.role == Role.SEND) {
            return Reduction(fail(s, FailReason.BOTH_SENDING), teardown(s) + SessionEffect.Notice(FailReason.BOTH_SENDING))
        }
        return stay(s)
    }

    private fun peerDeclined(s: SessionState, peer: Int): Reduction {
        if (s.role != Role.SEND || s.peer != peer || peer == 0) return stay(s)
        return if (s.phase == Phase.OFFERED || s.phase == Phase.TRANSFERRING) {
            Reduction(s.copy(phase = Phase.DECLINED), NONE)
        } else stay(s)
    }

    private fun fail(s: SessionState, reason: FailReason) = s.copy(phase = Phase.FAILED, fail = reason)

    private fun tap(s: SessionState, e: SessionEvent.TapDetected): Reduction {
        if (s.role == Role.SEND) {
            // A sender beams only to the phone its listener said yes to; a tap with anybody else changes nothing.
            if (s.phase != Phase.OFFERED || s.target == 0 || s.peer != e.peer) return stay(s)
            // The target already holds the offer: the tap is the commit. If it has not read it yet, the beam starts when it does.
            return if (s.offerRead) commitSend(s, e.nowMs) else stay(s.copy(tapSeen = true))
        }
        if ((s.phase != Phase.FOUND && s.phase != Phase.NEAR) || s.peer != e.peer) return stay(s)
        // The offer was fetched before the tap: the tap is the commit, with nothing slow left to do.
        if (s.pre) return admitOffer(s.copy(pre = false), e.peer, s.videoId, s.senderKey, e.nowMs, withTick = true)
        return Reduction(
            s.copy(phase = Phase.TRANSFERRING, videoId = ""),
            listOf(
                SessionEffect.ReadOffer(e.peer),
                SessionEffect.ScheduleTimeout(TimeoutTag.READ, READ_TIMEOUT_MS),
            ),
        )
    }

    /** The sender's commit: the first haptic, the beam, and the wait for the receiver's answer. */
    private fun commitSend(s: SessionState, nowMs: Long): Reduction = Reduction(
        s.copy(phase = Phase.TRANSFERRING, tapSeen = false),
        listOf(
            SessionEffect.Haptic(HapticCue.TICK),
            SessionEffect.StartBeam(Role.SEND, nowMs),
            SessionEffect.ScheduleTimeout(TimeoutTag.ACK, ACK_TIMEOUT_MS),
        ),
    )

    private fun approve(s: SessionState, e: SessionEvent.Approve): Reduction {
        if (s.role != Role.SEND || e.peer <= 0) return stay(s)
        if (s.phase != Phase.ARMED && s.phase != Phase.FOUND && s.phase != Phase.NEAR) return stay(s)
        if (e.videoId.isEmpty()) {
            return Reduction(fail(s, FailReason.NOTHING_TO_SEND), listOf(SessionEffect.Notice(FailReason.NOTHING_TO_SEND), SessionEffect.Haptic(HapticCue.WARN)))
        }
        return Reduction(
            s.copy(phase = Phase.OFFERED, peer = e.peer, videoId = e.videoId, target = e.peer, offerRead = false, tapSeen = false),
            listOf(SessionEffect.PublishOffer(e.peer, e.videoId), SessionEffect.ScheduleTimeout(TimeoutTag.TARGET, TARGET_TIMEOUT_MS)),
        )
    }

    private fun republish(s: SessionState, videoId: String): Reduction {
        if (s.role != Role.SEND || s.phase != Phase.OFFERED || s.target == 0 || videoId.isEmpty()) return stay(s)
        return Reduction(s.copy(videoId = videoId, offerRead = false), listOf(SessionEffect.PublishOffer(s.target, videoId)))
    }

    private fun offerRead(s: SessionState, e: SessionEvent.OfferRead): Reduction {
        if (s.role != Role.SEND || s.phase != Phase.OFFERED || s.target == 0 || s.target != e.peer) return stay(s)
        // A tap that came first starts the beam now that the offer is in hand.
        return if (s.tapSeen) commitSend(s.copy(offerRead = true), e.nowMs) else stay(s.copy(offerRead = true))
    }

    private fun offerReadLost(s: SessionState, peer: Int): Reduction {
        if (s.role != Role.SEND || s.phase != Phase.OFFERED || s.target == 0 || s.target != peer) return stay(s)
        return stay(s.copy(offerRead = false))
    }

    private fun offerPrefetched(s: SessionState, e: SessionEvent.OfferPrefetched): Reduction {
        if (s.role != Role.RECEIVE || e.peer == 0 || s.peer != e.peer) return stay(s)
        if (s.phase != Phase.FOUND && s.phase != Phase.NEAR) return stay(s)
        return stay(s.copy(pre = true, videoId = e.videoId, senderKey = e.senderKey))
    }

    private fun offerGone(s: SessionState, peer: Int): Reduction {
        if (s.role != Role.RECEIVE || !s.pre || s.peer != peer) return stay(s)
        return stay(s.copy(pre = false, videoId = "", senderKey = 0))
    }

    private fun qrShown(s: SessionState, videoId: String): Reduction {
        if (s.role != Role.SEND) return stay(s)
        if (s.phase != Phase.ARMED && s.phase != Phase.FOUND && s.phase != Phase.NEAR) return stay(s)
        if (videoId.isEmpty()) return Reduction(fail(s, FailReason.NOTHING_TO_SEND), listOf(SessionEffect.Notice(FailReason.NOTHING_TO_SEND)))
        return Reduction(
            s.copy(phase = Phase.OFFERED, peer = 0, videoId = videoId),
            listOf(SessionEffect.PublishOffer(0, videoId)),
        )
    }

    private fun verdictReason(v: LimitVerdict): FailReason = when (v) {
        LimitVerdict.TOO_SOON -> FailReason.TOO_SOON
        LimitVerdict.DUPLICATE -> FailReason.DUPLICATE
        LimitVerdict.MUTED -> FailReason.MUTED
        else -> FailReason.LIMIT
    }

    private fun offerReceived(s: SessionState, e: SessionEvent.OfferReceived): Reduction {
        val waiting = s.phase == Phase.ARMED || s.phase == Phase.FOUND || s.phase == Phase.NEAR ||
            (s.phase == Phase.TRANSFERRING && s.videoId.isEmpty())
        if (s.role == Role.SEND) {
            // A sender being offered a song: both phones are sending.
            val active = s.phase == Phase.FOUND || s.phase == Phase.NEAR || s.phase == Phase.OFFERED
            return if (active) Reduction(fail(s, FailReason.BOTH_SENDING), teardown(s) + SessionEffect.Notice(FailReason.BOTH_SENDING)) else stay(s)
        }
        if (!waiting) return stay(s)
        // A beam fetched after the tap (no prefetch) commits when it arrives: that is when the haptic belongs.
        return admitOffer(s.copy(pre = false), e.peer, e.videoId, e.senderKey, e.nowMs, withTick = e.peer != 0)
    }

    /** The receiver has a checked offer in hand and the tap (or a code) says take it: limits, then ask or save and beam. */
    private fun admitOffer(s: SessionState, peer: Int, videoId: String, senderKey: Int, nowMs: Long, withTick: Boolean): Reduction {
        val verdict = s.limits.admit(nowMs, videoId, senderKey)
        if (verdict != LimitVerdict.ALLOWED) {
            val reason = verdictReason(verdict)
            // The sender has already started its beam (spec 5): it is told this one will not be taken.
            return Reduction(
                fail(s.copy(peer = peer), reason),
                (if (peer != 0) listOf(SessionEffect.SendDecline(peer)) else NONE) +
                    listOf(SessionEffect.Notice(reason), SessionEffect.Haptic(HapticCue.WARN)),
            )
        }
        val known = s.copy(peer = peer, videoId = videoId, senderKey = senderKey)
        val tick = if (withTick) listOf(SessionEffect.Haptic(HapticCue.TICK)) else NONE
        if (s.askFirst) {
            return Reduction(
                known.copy(phase = Phase.OFFERED),
                tick + SessionEffect.ScheduleTimeout(TimeoutTag.CONSENT, CONSENT_TIMEOUT_MS),
            )
        }
        return Reduction(known.copy(phase = Phase.TRANSFERRING), tick + saveEffects(videoId, nowMs))
    }

    /**
     * Saving starts and the beam starts with it, on every route: the beam does not wait for the save, the sender or a `go`
     * (spec 5). The receiver's card says "Adding" until the save result arrives; a save that fails ends the beam.
     */
    private fun saveEffects(videoId: String, nowMs: Long): List<SessionEffect> =
        listOf(
            SessionEffect.SaveSong(videoId),
            SessionEffect.ScheduleTimeout(TimeoutTag.SAVE, SAVE_TIMEOUT_MS),
            SessionEffect.StartBeam(Role.RECEIVE, nowMs),
        )

    private fun offerRejected(s: SessionState): Reduction = when {
        s.role != Role.RECEIVE -> stay(s)
        s.phase == Phase.ARMED || s.phase == Phase.FOUND || s.phase == Phase.NEAR -> Reduction(s, listOf(SessionEffect.NoticeInvalidCode))
        s.phase == Phase.TRANSFERRING && s.videoId.isEmpty() ->
            Reduction(fail(s, FailReason.INVALID_OFFER), listOf(SessionEffect.Notice(FailReason.INVALID_OFFER), SessionEffect.Haptic(HapticCue.WARN)))
        else -> stay(s)
    }

    private fun ackReceived(s: SessionState, e: SessionEvent.AckReceived): Reduction {
        if (s.role != Role.SEND || s.peer == 0) return stay(s)
        if (s.phase == Phase.OFFERED) {
            // The receiver committed and saved before this phone noticed the tap: the beam starts now.
            return commitSend(s.copy(acked = true), e.nowMs)
        }
        if (s.phase != Phase.TRANSFERRING || s.acked) return stay(s)
        if (s.beamDone) return Reduction(s.copy(phase = Phase.SAVED, acked = true), listOf(SessionEffect.Haptic(HapticCue.SUCCESS)))
        return stay(s.copy(acked = true))
    }

    private fun beamFinished(s: SessionState): Reduction {
        if (s.role != Role.SEND || s.phase != Phase.TRANSFERRING) return stay(s)
        // The session ends when the beam has played and the receiver has said it saved (a QR beam has no peer to wait for).
        return if (s.acked || s.peer == 0) Reduction(s.copy(phase = Phase.SAVED, beamDone = true), listOf(SessionEffect.Haptic(HapticCue.SUCCESS)))
        else stay(s.copy(beamDone = true))
    }

    private fun accept(s: SessionState, nowMs: Long): Reduction {
        if (s.role != Role.RECEIVE || s.phase != Phase.OFFERED || s.videoId.isEmpty()) return stay(s)
        return Reduction(s.copy(phase = Phase.TRANSFERRING, askFirst = false, autoSaves = 0), saveEffects(s.videoId, nowMs))
    }

    private fun decline(s: SessionState): Reduction {
        if (s.role != Role.RECEIVE || s.phase != Phase.OFFERED) return stay(s)
        val effects = if (s.peer != 0) listOf(SessionEffect.SendDecline(s.peer)) else NONE
        return Reduction(s.copy(phase = Phase.DECLINED), effects)
    }

    private fun saveResult(s: SessionState, e: SessionEvent.SaveResult): Reduction {
        if (s.role != Role.RECEIVE || s.phase != Phase.TRANSFERRING || s.videoId.isEmpty()) return stay(s)
        val linked = s.peer != 0
        return when (e.outcome) {
            SaveOutcome.SAVED -> {
                // Every ASK_AGAIN_AFTER-th beam saved without asking makes the next one ask: consent is not for ever.
                val auto = s.autoSaves + 1
                val ask = auto >= ASK_AGAIN_AFTER
                Reduction(
                    s.copy(
                        phase = Phase.SAVED, askFirst = ask, alreadyHad = false, autoSaves = if (ask) 0 else auto,
                        limits = s.limits.accepted(e.nowMs, s.videoId),
                    ),
                    (if (linked) listOf(SessionEffect.SendAck(s.peer)) else NONE) + SessionEffect.Haptic(HapticCue.SUCCESS),
                )
            }
            SaveOutcome.ALREADY_HAD -> Reduction(
                s.copy(phase = Phase.SAVED, alreadyHad = true, limits = s.limits.accepted(e.nowMs, s.videoId)),
                (if (linked) listOf(SessionEffect.SendAck(s.peer)) else NONE) + SessionEffect.Haptic(HapticCue.TICK),
            )
            SaveOutcome.NOT_FOUND -> Reduction(
                fail(s, FailReason.NOT_FOUND).copy(limits = s.limits.attempted(e.nowMs)),
                (if (linked) listOf(SessionEffect.SendDecline(s.peer)) else NONE) +
                    listOf(SessionEffect.Notice(FailReason.NOT_FOUND), SessionEffect.Haptic(HapticCue.WARN)),
            )
            SaveOutcome.FAILED -> Reduction(
                fail(s, FailReason.SAVE_FAILED).copy(limits = s.limits.attempted(e.nowMs)),
                (if (linked) listOf(SessionEffect.SendDecline(s.peer)) else NONE) +
                    listOf(SessionEffect.Notice(FailReason.SAVE_FAILED), SessionEffect.Haptic(HapticCue.WARN)),
            )
        }
    }

    private fun undo(s: SessionState, e: SessionEvent.Undo): Reduction {
        if (s.role != Role.RECEIVE || s.phase != Phase.SAVED || s.alreadyHad || s.videoId.isEmpty()) return stay(s)
        val next = clear(s, Phase.ARMED).copy(limits = s.limits.undone(e.nowMs, s.senderKey))
        return Reduction(next, listOf(SessionEffect.UndoSave(s.videoId)))
    }

    private fun cancel(s: SessionState): Reduction = when (s.phase) {
        Phase.FOUND, Phase.NEAR, Phase.OFFERED, Phase.TRANSFERRING ->
            Reduction(s.copy(phase = Phase.CANCELLED), teardown(s))
        else -> stay(s)
    }

    private fun dismiss(s: SessionState): Reduction =
        if (isTerminal(s.phase)) Reduction(clear(s, Phase.ARMED), NONE) else stay(s)

    private fun timeout(s: SessionState, tag: TimeoutTag): Reduction = when {
        tag == TimeoutTag.ACK && s.role == Role.SEND && s.phase == Phase.TRANSFERRING && !s.acked && s.peer != 0 ->
            Reduction(fail(s, FailReason.TIMEOUT), teardown(s) + SessionEffect.Haptic(HapticCue.WARN))
        tag == TimeoutTag.TARGET && s.role == Role.SEND && s.phase == Phase.OFFERED && s.target != 0 ->
            Reduction(s.copy(phase = Phase.CANCELLED), teardown(s))
        tag == TimeoutTag.READ && s.role == Role.RECEIVE && s.phase == Phase.TRANSFERRING && s.videoId.isEmpty() ->
            Reduction(fail(s, FailReason.TIMEOUT), teardown(s) + SessionEffect.Haptic(HapticCue.WARN))
        tag == TimeoutTag.CONSENT && s.role == Role.RECEIVE && s.phase == Phase.OFFERED ->
            Reduction(s.copy(phase = Phase.DECLINED), if (s.peer != 0) listOf(SessionEffect.SendDecline(s.peer)) else NONE)
        tag == TimeoutTag.SAVE && s.role == Role.RECEIVE && s.phase == Phase.TRANSFERRING && s.videoId.isNotEmpty() ->
            Reduction(fail(s, FailReason.TIMEOUT), teardown(s) + SessionEffect.Haptic(HapticCue.WARN))
        else -> stay(s)
    }
}
