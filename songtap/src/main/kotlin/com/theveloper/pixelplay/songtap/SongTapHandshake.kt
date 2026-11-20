package com.theveloper.pixelplay.songtap

/**
 * The clock that lines the two animations up. The receiver acknowledges the offer, the sender answers
 * with `go` carrying a start time a little ahead on the sender's clock, and the receiver starts at its own
 * receipt time plus the same delay minus half the measured round trip. An error of a few tens of
 * milliseconds is invisible because the motion is symmetric and eased.
 */
object SongTapHandshake {

    /** How far ahead of the `go` message both animations start. */
    const val GO_DELAY_MS = 150L

    /** A round trip longer than this is not trusted for the correction. */
    const val MAX_RTT_MS = 1_000L

    /** The sender's animation start, on the sender's clock. */
    fun senderStartMs(nowMs: Long): Long = nowMs + GO_DELAY_MS

    /** The receiver's animation start, on the receiver's clock; [rttMs] is the receiver's measured round trip of its ack. */
    fun receiverStartMs(receiptMs: Long, rttMs: Long): Long {
        val rtt = if (rttMs < 0L) 0L else if (rttMs > MAX_RTT_MS) MAX_RTT_MS else rttMs
        return receiptMs + GO_DELAY_MS - rtt / 2
    }

    /** How long ago, on a phone's own clock, its last impulse was, so the other phone can place it on its clock. */
    fun impulseAgeMs(nowMs: Long, impulseMs: Long): Long = if (impulseMs < 0L || impulseMs > nowMs) -1L else nowMs - impulseMs

    /** The impulse the peer reported, as a time on this phone's clock (less half the one way trip). */
    fun peerImpulseLocalMs(receiptMs: Long, ageMs: Long, rttMs: Long): Long {
        if (ageMs < 0L) return -1L
        val rtt = if (rttMs < 0L) 0L else if (rttMs > MAX_RTT_MS) MAX_RTT_MS else rttMs
        return receiptMs - ageMs - rtt / 2
    }
}
