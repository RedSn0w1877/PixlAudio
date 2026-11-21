package com.theveloper.pixelplay.songtap

/** What a phone does with the other phone's tap message (spec 6, the one-sided tap). */
enum class PeerTapVerdict(val code: String) {
    /** Commit now: haptic, save or ack, beam, without waiting for this phone's own detector. */
    ACCEPT("accept"),

    /** This phone's own signal for that phone is below the tap near gate: the message is not believed. */
    TOO_WEAK("too_weak"),

    /** The message did not come from the phone this one is following / armed with. */
    NOT_FOLLOWED("not_followed"),

    /** The sender has not said yes to "Song drop to X?" for this pair (a receiver holds no offer fetched ahead; a sender holds no offer for that reader). */
    NO_YES("no_yes"),

    /** Another tap was taken (or this phone's own detector fired) less than [SongTapPeerTap.MIN_GAP_MS] ago. */
    TOO_SOON("too_soon"),
}

/**
 * The one-sided tap. When a phone's detector fires (the first haptic tick) it tells the other phone at once over the existing link, and
 * the other phone commits without waiting for its own detector, but only when every condition here holds. Both phones therefore beam
 * from whichever one felt the tap first.
 *
 * Wire: a receiver tells the sender with a Control write `04 aa aa aa aa` ([SongTapWire.encodeTap], `aa` = the age of its last jerk);
 * the sender tells the receiver with an Event notification `04` ([SongTapWire.encodeTapEvent]). The consent and the offer checks of
 * spec 5 are untouched: this only removes the wait for the second detector.
 */
object SongTapPeerTap {

    /** At most one accepted peer tap in this long (also counted from this phone's own last fire). */
    const val MIN_GAP_MS = 1_200L

    /**
     * Judges one tap message from the peer, in this order.
     *
     * @param ownFastDbm this phone's own smoothed, NORMALISED signal for the peer, whole dBm, at this moment.
     * @param followed the message came from the phone this one follows (a sender: the phone it is armed to; a receiver: the sender it is connected to and near).
     * @param saidYes the sender said yes to this pair (a sender: it holds an offer that this reader read; a receiver: it holds a checked offer fetched ahead).
     * @param lastTapMs when this phone last fired itself or took a peer tap, -1 for never (any clock, only the difference matters).
     */
    fun judge(ownFastDbm: Int, followed: Boolean, saidYes: Boolean, nowMs: Long, lastTapMs: Long): PeerTapVerdict = when {
        !followed -> PeerTapVerdict.NOT_FOLLOWED
        !saidYes -> PeerTapVerdict.NO_YES
        ownFastDbm < SongTapTuning.NEAR_ENTER_DBM -> PeerTapVerdict.TOO_WEAK
        lastTapMs >= 0L && nowMs - lastTapMs < MIN_GAP_MS -> PeerTapVerdict.TOO_SOON
        else -> PeerTapVerdict.ACCEPT
    }
}
