package com.theveloper.pixelplay.songtap.ring

import com.theveloper.pixelplay.songtap.ring.image.RingScanAccumulator
import com.theveloper.pixelplay.songtap.ring.image.RingScanResult

/**
 * What the Song Tap screen does with the ring code, as plain rules over time (integers, no clock: the caller says
 * what time it is). Both phones' halves live here so the iOS port copies them, and so they are tested over fakes.
 *
 * The order of ways to beam is Bluetooth tap, ring code, QR code last:
 * - The sender shows the ring code for a song that has a video id; a song without one goes straight to the QR
 *   ([RingSendRules.canRing]). The code is made again with a fresh tag every [RingSendRules.CODE_LIFE_MS].
 * - The receiver scans it. "Use QR code instead" shows only after the ring scan has failed for
 *   [RingSendRules.QR_OFFER_AFTER_MS] or the camera cannot run the scanner ([RingReceiveFlow]); the sender offers
 *   the same button once its code has been up that long (a sender cannot see the receiver's scan).
 */
object RingSendRules {
    /** A code is made again with a fresh tag this often (the tag itself is good for two to three minutes). */
    const val CODE_LIFE_MS = 60_000L

    /** "Use QR code instead" appears after this long. */
    const val QR_OFFER_AFTER_MS = 6_000L

    fun canRing(videoId: String?): Boolean = videoId != null && RingCodePayloads.isValidVideoId(videoId)

    fun qrButtonVisible(shownForMs: Long): Boolean = shownForMs >= QR_OFFER_AFTER_MS
}

enum class RingVerdict(val code: String) {
    ACCEPT("accept"),

    /** The tag is older than the receiver allows (the sender's code is stale, or this phone's clock is ahead). */
    EXPIRED("expired"),

    /** The tag is from the future (this phone's clock is behind the sender's by more than a minute). */
    FUTURE("future"),

    /** This exact code (video and tag) was already taken a moment ago. */
    REPLAY("replay"),
}

/**
 * Decides whether a decoded ring code may be used: fresh tag, and not one that was already taken. [check] only
 * looks; [commit] remembers a code that was used, so a lookup that failed (no internet) can be tried again.
 */
class RingCodeGuard(private val memoryMs: Long = 5 * 60_000L, private val capacity: Int = 32) {
    private val seen = LinkedHashMap<String, Long>()

    private fun keyOf(p: RingCodePayload): String = p.videoId + "/" + p.tag()

    private fun forget(nowMs: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) if (nowMs - it.next().value > memoryMs) it.remove()
        while (seen.size >= capacity) {
            val oldest = seen.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    fun check(p: RingCodePayload, nowMs: Long): RingVerdict {
        when (RingCodePayloads.freshness(p.minuteBucket, nowMs)) {
            Freshness.EXPIRED -> return RingVerdict.EXPIRED
            Freshness.FUTURE -> return RingVerdict.FUTURE
            Freshness.FRESH -> Unit
        }
        val at = seen[keyOf(p)]
        if (at != null && nowMs - at <= memoryMs) return RingVerdict.REPLAY
        return RingVerdict.ACCEPT
    }

    fun commit(p: RingCodePayload, nowMs: Long) {
        forget(nowMs)
        seen[keyOf(p)] = nowMs
    }
}

/** What the receiver's scan screen shows besides the camera. */
enum class RingScanMessage(val code: String) {
    NONE("none"),

    /** A code was read but its tag is too old. */
    EXPIRED("expired"),

    /** A code was read but its tag is from the future: the clocks differ. */
    CLOCK("clock"),

    /** The song could not be looked up (no internet). */
    LOOKUP_FAILED("lookup_failed"),

    /** The same code was already taken. */
    REPLAY("replay"),
}

enum class RingScanStage(val code: String) {
    /** Not scanning. */
    IDLE("idle"),

    /** The camera is reading frames for a ring code. */
    SCANNING("scanning"),

    /** A code was read and the song is being looked up. */
    RESOLVING("resolving"),

    /** The camera cannot run the ring scanner (no camera, it would not start, or the phone is too slow for it). */
    UNAVAILABLE("unavailable"),
}

/** What the screen draws. [qrOffered] is the one switch that shows "Use QR code instead". */
data class RingScanView(val stage: RingScanStage, val qrOffered: Boolean, val message: RingScanMessage) {
    companion object {
        val IDLE = RingScanView(RingScanStage.IDLE, false, RingScanMessage.NONE)
    }
}

/** What one scan result asks the app to do next. */
sealed class RingStep {
    data object None : RingStep()

    /** Three equal fresh decodes in a row: look the song up, then call [RingReceiveFlow.resolved]. */
    class Resolve(val payload: RingCodePayload) : RingStep()

    class Rejected(val verdict: RingVerdict) : RingStep()
}

/**
 * The receiver's side of the ring code over one scan session: collects results, needs three equal decodes in a row
 * (the accumulator), asks the guard, and tracks when "Use QR code instead" may be offered. One thread at a time;
 * the app calls it under its own lock.
 */
class RingReceiveFlow(
    private val guard: RingCodeGuard = RingCodeGuard(),
    private val accumulator: RingScanAccumulator = RingScanAccumulator(),
) {
    private var stage = RingScanStage.IDLE
    private var qrOffered = false
    private var message = RingScanMessage.NONE
    private var startedMs = 0L
    private var lastDecodeMs = Long.MIN_VALUE
    private var ignoreUntilMs = Long.MIN_VALUE

    fun view(): RingScanView = RingScanView(stage, qrOffered, message)

    /** The scan screen opened. A phone whose camera cannot run the scanner offers the QR at once. */
    fun start(nowMs: Long, cameraAvailable: Boolean): RingScanView {
        accumulator.reset()
        startedMs = nowMs
        lastDecodeMs = Long.MIN_VALUE
        ignoreUntilMs = Long.MIN_VALUE
        message = RingScanMessage.NONE
        stage = if (cameraAvailable) RingScanStage.SCANNING else RingScanStage.UNAVAILABLE
        qrOffered = !cameraAvailable
        return view()
    }

    /** The camera would not start, or died. */
    fun cameraFailed(): RingScanView {
        if (stage == RingScanStage.IDLE) return view()
        accumulator.reset()
        stage = RingScanStage.UNAVAILABLE
        qrOffered = true
        return view()
    }

    /** The scanner cannot keep up on this phone (the average scan is too slow to be useful). */
    fun scannerTooSlow(): RingScanView {
        if (stage == RingScanStage.SCANNING) qrOffered = true
        return view()
    }

    /** Time passed with no news: after [RingSendRules.QR_OFFER_AFTER_MS] without a working scan the QR is offered. True when the view changed. */
    fun tick(nowMs: Long): Boolean {
        if (stage != RingScanStage.SCANNING || qrOffered) return false
        if (nowMs - startedMs < RingSendRules.QR_OFFER_AFTER_MS) return false
        // A scan that keeps reading codes (even ones not good enough yet) is working: wait for it.
        if (lastDecodeMs != Long.MIN_VALUE && nowMs - lastDecodeMs <= DECODE_GRACE_MS) return false
        qrOffered = true
        return true
    }

    /** One scanner result. */
    fun offer(result: RingScanResult, nowMs: Long): RingStep {
        if (stage != RingScanStage.SCANNING) return RingStep.None
        if (nowMs < ignoreUntilMs) return RingStep.None
        if (result is RingScanResult.Decoded) lastDecodeMs = nowMs
        val payload = accumulator.offer(result, nowMs) ?: return RingStep.None
        return when (val verdict = guard.check(payload, nowMs)) {
            RingVerdict.ACCEPT -> {
                stage = RingScanStage.RESOLVING
                message = RingScanMessage.NONE
                RingStep.Resolve(payload)
            }
            RingVerdict.EXPIRED -> reject(verdict, RingScanMessage.EXPIRED, offerQr = true)
            RingVerdict.FUTURE -> reject(verdict, RingScanMessage.CLOCK, offerQr = true)
            RingVerdict.REPLAY -> reject(verdict, RingScanMessage.REPLAY, offerQr = false)
        }
    }

    private fun reject(verdict: RingVerdict, why: RingScanMessage, offerQr: Boolean): RingStep {
        accumulator.reset()
        message = why
        if (offerQr) qrOffered = true
        return RingStep.Rejected(verdict)
    }

    /** The lookup of [payload] finished. A failure goes back to scanning with the QR on offer; the same code is not tried again for a few seconds. */
    fun resolved(payload: RingCodePayload, ok: Boolean, nowMs: Long): RingScanView {
        if (stage != RingScanStage.RESOLVING) return view()
        accumulator.reset()
        if (ok) {
            guard.commit(payload, nowMs)
            stage = RingScanStage.IDLE
            message = RingScanMessage.NONE
        } else {
            stage = RingScanStage.SCANNING
            message = RingScanMessage.LOOKUP_FAILED
            qrOffered = true
            ignoreUntilMs = nowMs + RETRY_AFTER_FAILURE_MS
        }
        return view()
    }

    /** The scan screen closed. */
    fun stop(): RingScanView {
        accumulator.reset()
        stage = RingScanStage.IDLE
        qrOffered = false
        message = RingScanMessage.NONE
        return view()
    }

    companion object {
        /** Decodes this recent count as a scan that is working. */
        const val DECODE_GRACE_MS = 2_000L

        /** After a lookup that failed the same code is left alone for this long. */
        const val RETRY_AFTER_FAILURE_MS = 3_000L
    }
}
