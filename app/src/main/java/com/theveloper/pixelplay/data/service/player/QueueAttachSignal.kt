package com.theveloper.pixelplay.data.service.player

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Says whether the rest of a freshly started queue is still on its way to the player.
 *
 * Tapping a song in an album or a playlist first sets a ONE-item queue and attaches the rest a moment
 * later (`PlaybackDispatchStateHolder.internalPlaySongs`, `attachQueueSegmentsIfCurrent`). In that
 * moment the queue looks like a single tapped song, which is exactly what Autoplay tops up. A top-up
 * there puts picks behind the tapped song, the attach then refuses (the player no longer holds one
 * item) and the listener's playlist is gone. So Autoplay asks here first and looks again when the
 * attach is over ([AutoplayController]).
 *
 * Main thread only, like the dispatch path and the controller. No dependencies, so both sides can
 * inject it without a cycle.
 */
@Singleton
class QueueAttachSignal internal constructor(private val nanoTime: () -> Long) {

    @Inject
    constructor() : this(System::nanoTime)

    private var attaching = false
    private var beganAtNanos = 0L
    private var listener: (() -> Unit)? = null
    private var nextAtEndListener: (() -> Unit)? = null

    /**
     * True from [onBegin] until [onEnd]. An attach that never reports its end (it cannot, but nothing
     * should be able to switch Autoplay off for good) stops counting after [MAX_ATTACH_NANOS].
     */
    val isAttaching: Boolean
        get() = attaching && nanoTime() - beganAtNanos in 0 until MAX_ATTACH_NANOS

    /** Called when an attach ends, however it ends. One listener: the Autoplay controller. */
    fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /** Called when Next is pressed with nothing after the current track. One listener, as above. */
    fun setNextAtEndListener(listener: (() -> Unit)?) {
        nextAtEndListener = listener
    }

    /**
     * Next was pressed in the app and no track follows the current one, so the press has nowhere to
     * go. Autoplay is told: when its picks are in within moments, it moves on to them.
     */
    fun onNextWithNothingQueued() {
        nextAtEndListener?.invoke()
    }

    /** The tapped song is about to be set as a one-item queue; the rest follows. */
    fun onBegin() {
        attaching = true
        beganAtNanos = nanoTime()
    }

    /** The rest was attached, or the attach was given up (another play started, it was cancelled). */
    fun onEnd() {
        if (!attaching) return
        attaching = false
        listener?.invoke()
    }

    private companion object {
        const val MAX_ATTACH_NANOS = 10_000_000_000L
    }
}
