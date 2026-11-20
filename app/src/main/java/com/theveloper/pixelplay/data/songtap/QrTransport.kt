package com.theveloper.pixelplay.data.songtap

import com.theveloper.pixelplay.songtap.Role
import com.theveloper.pixelplay.songtap.SongTapLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The code route, which works between any two phones: the sender shows a QR code, the receiver scans it.
 * Nothing travels back, so there is no acknowledgement and no shared animation clock; each phone plays its
 * own part when it has what it needs.
 *
 * The camera is owned by the screen: it hands what it reads to [onScanned].
 */
@Singleton
class QrTransport @Inject constructor() : SongTapTransport {

    override val id: String = "qr"

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _code = MutableStateFlow<String?>(null)

    /** The text to draw as a QR code while the sender is showing one. */
    val code: StateFlow<String?> = _code.asStateFlow()

    override fun start(role: Role) = Unit

    override fun stop() {
        _code.value = null
    }

    override fun publishOffer(peer: Int, payload: ByteArray) {
        _code.value = SongTapLink.encode(payload)
    }

    override fun withdrawOffer() {
        _code.value = null
    }

    override fun readOffer(peer: Int) = Unit
    override fun sendAck(peer: Int) = Unit
    override fun sendGo(peer: Int, startMs: Long) = Unit
    override fun sendDecline(peer: Int) = Unit
    override fun abort(peer: Int) = Unit

    /** The camera read [text]. Anything that is not exactly a Song Tap link is reported as not an offer. */
    fun onScanned(text: String) {
        val bytes = SongTapLink.decode(text)
        _events.tryEmit(if (bytes == null) TransportEvent.OfferInvalid else TransportEvent.Offer(peer = 0, payload = bytes, senderKey = 0))
    }
}
