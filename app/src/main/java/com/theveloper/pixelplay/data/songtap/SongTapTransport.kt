package com.theveloper.pixelplay.data.songtap

import com.theveloper.pixelplay.songtap.Role
import kotlinx.coroutines.flow.Flow

/**
 * What a transport tells the session. A transport is the way two phones find each other and carry an offer
 * (a QR code now, Bluetooth next); it knows nothing about the session's rules. It checks what it can on
 * its own (a Bluetooth offer's signature and challenge) and hands over only bytes that passed.
 */
sealed interface TransportEvent {
    data class PeerFound(val peer: Int) : TransportEvent
    data class PeerLost(val peer: Int) : TransportEvent
    data class PeerNear(val peer: Int) : TransportEvent
    data class PeerFar(val peer: Int) : TransportEvent
    data class PeerRole(val peer: Int, val role: Role) : TransportEvent

    /** The two phones touched: the detector fired. */
    data class Tap(val peer: Int) : TransportEvent

    /** An offer arrived from [peer] (0 for a QR code): the payload bytes and the number of the key that signed it (0 when unsigned). [name] is the sender's name when known. */
    class Offer(val peer: Int, val payload: ByteArray, val senderKey: Int, val name: String? = null) : TransportEvent

    /**
     * Receiver, spec 5: an offer was fetched and checked before any tap, so the data is already in hand when the detector fires.
     * A newer one for the same peer replaces it.
     */
    class OfferPrefetched(val peer: Int, val payload: ByteArray, val senderKey: Int, val name: String?) : TransportEvent

    /** Receiver: the prefetched offer of [peer] is no longer good (the connection closed, the sender withdrew it). */
    data class OfferGone(val peer: Int) : TransportEvent

    /** Sender, spec 5: the phone it is armed to has read the offer (the receiver holds the data now). */
    data class OfferRead(val peer: Int) : TransportEvent

    /** Sender: the phone that read the offer went away before the beam. */
    data class OfferReadLost(val peer: Int) : TransportEvent

    /** The name of [peer] became known (or changed): the full name from its Name characteristic, or the short one it advertised. */
    data class PeerName(val peer: Int, val name: String) : TransportEvent

    /** Something arrived that is not an offer this phone can use. */
    data object OfferInvalid : TransportEvent

    /** The receiver acknowledged the offer (sender side). */
    data class Ack(val peer: Int) : TransportEvent

    /** The receiver declined (sender side). */
    data class Declined(val peer: Int) : TransportEvent
}

/**
 * One way of finding a peer and carrying an offer. The controller drives it with the session's effects; it
 * answers with [TransportEvent]s. Nothing here starts on its own: a transport does nothing until [start], and
 * a radio transport only between [start] and [stop], which the screen ties to being armed in the foreground.
 */
interface SongTapTransport {
    val id: String
    val events: Flow<TransportEvent>

    fun start(role: Role)
    fun stop()

    /**
     * Sender: make [payload] available to [peer] (0 for the QR code). Over Bluetooth this is the listener's yes to "Song drop to X?":
     * the offer is published for that phone only, before any tap, and the phone is followed until [withdrawOffer].
     */
    fun publishOffer(peer: Int, payload: ByteArray)

    /** Sender: stop offering. */
    fun withdrawOffer()

    /** Receiver: read the offer of [peer] now. */
    fun readOffer(peer: Int)
    fun sendAck(peer: Int)
    fun sendDecline(peer: Int)
    fun abort(peer: Int)
}
