package com.theveloper.pixelplay.songtap

/**
 * What is left of the clock alignment. Spec 4 had the sender wait for the receiver's acknowledgement, answer with `go`
 * and start both animations [GO_DELAY_MS] later, which made a tap feel late (the owner's report: the first haptic was
 * the moment to send, but the beam took a while to register). From spec 5 the data is fetched before the tap, so each
 * phone starts its own beam at its own commit (the first haptic) and nothing waits for the other: there is no `go` and no
 * start delay ([GO_DELAY_MS] is 0 and kept only so the ports compile against the same name).
 *
 * The two animations therefore differ by the difference of the two commit times (each phone commits on its own signal
 * jump or hold, or on the other's tap message, a few tens of milliseconds, typically under 150) plus nothing else. The
 * motion is symmetric and eased and the phones are touching, so this is not visible; it is not corrected.
 */
object SongTapHandshake {

    /** No wait: both animations start at the moment each phone commits. */
    const val GO_DELAY_MS = 0L

    /** A round trip longer than this is not trusted for the correction. */
    const val MAX_RTT_MS = 1_000L

    /** How long ago, on a phone's own clock, its last impulse was, so the other phone can place it on its clock. */
    fun impulseAgeMs(nowMs: Long, impulseMs: Long): Long = if (impulseMs < 0L || impulseMs > nowMs) -1L else nowMs - impulseMs

    /** The impulse the peer reported, as a time on this phone's clock (less half the one way trip). */
    fun peerImpulseLocalMs(receiptMs: Long, ageMs: Long, rttMs: Long): Long {
        if (ageMs < 0L) return -1L
        val rtt = if (rttMs < 0L) 0L else if (rttMs > MAX_RTT_MS) MAX_RTT_MS else rttMs
        return receiptMs - ageMs - rtt / 2
    }
}
