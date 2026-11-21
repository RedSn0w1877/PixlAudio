package com.theveloper.pixelplay.data.songtap.ring

import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import com.theveloper.pixelplay.songtap.ring.image.RingFrame
import com.theveloper.pixelplay.songtap.ring.image.RingScanResult
import com.theveloper.pixelplay.songtap.ring.image.RingScanner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the ring scanner saw in the last frame it looked at, for Developer mode > Ring code diagnostics. Picture-sized values are
 * copied here (a small upright grey picture, the plate's circle, the start dot, 200 confidences) and published at most five times a second,
 * and only while the diagnostics screen is open: with it closed [record] returns at once.
 */
class RingDiagSnapshot(
    val serial: Int,
    /** The frame the scanner saw, small, grey and upright (rotated by the camera's rotation). */
    val previewWidth: Int,
    val previewHeight: Int,
    val preview: ByteArray,
    val found: Boolean,
    /** The plate's centre and outline radius as fractions of the upright picture's width and height (radius: of the width). */
    val centreX: Float,
    val centreY: Float,
    val radius: Float,
    val markerFound: Boolean,
    val markerX: Float,
    val markerY: Float,
    val matched: Int,
    val residualPx: Float,
    val rsCorrected: Int,
    val erasures: Int,
    val scanMillis: Float,
    val candidates: Int,
    /** "decoded <id> ...", or the failure reason. */
    val outcome: String,
    val confidence: IntArray,
    val bits: ByteArray,
    val framesPerSecond: Int,
    val scansPerSecond: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val tooSlow: Boolean,
) {
    companion object {
        val EMPTY = RingDiagSnapshot(
            0, 0, 0, ByteArray(0), false, 0f, 0f, 0f, false, 0f, 0f, 0, 0f, 0, 0, 0f, 0, "", IntArray(RingCodeLayout.SLOTS),
            ByteArray(RingCodeLayout.SLOTS), 0, 0, 0, 0, false,
        )
    }
}

@Singleton
class RingCodeDiagnostics @Inject constructor() {

    @Volatile private var enabled = false

    private val _snapshot = MutableStateFlow(RingDiagSnapshot.EMPTY)
    val snapshot: StateFlow<RingDiagSnapshot> = _snapshot.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** The last [LOG_SIZE] results, newest last. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var serial = 0
    private var lastPublishMs = 0L
    private var lastLogMs = Long.MIN_VALUE
    private var lastLogKey = ""
    private var secondStartMs = 0L
    private var framesThisSecond = 0
    private var scansThisSecond = 0
    private var framesPerSecond = 0
    private var scansPerSecond = 0
    private var slow = false

    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            _snapshot.value = RingDiagSnapshot.EMPTY
            _log.value = emptyList()
            lastLogKey = ""
            lastLogMs = Long.MIN_VALUE
        }
    }

    fun onFrame(nowMs: Long) {
        if (!enabled) return
        framesThisSecond++
        roll(nowMs)
    }

    fun onSlow() {
        slow = true
    }

    private fun roll(nowMs: Long) {
        if (nowMs - secondStartMs >= 1000L) {
            framesPerSecond = framesThisSecond
            scansPerSecond = scansThisSecond
            framesThisSecond = 0
            scansThisSecond = 0
            secondStartMs = nowMs
        }
    }

    /** One scan finished. Analysis thread. */
    fun record(frame: RingFrame, result: RingScanResult, scanner: RingScanner, rotationDegrees: Int, nowMs: Long) {
        if (!enabled) return
        scansThisSecond++
        roll(nowMs)
        val outcome = describe(result)
        addLog(result, outcome, nowMs, scanner)
        if (nowMs - lastPublishMs < PUBLISH_GAP_MS) return
        lastPublishMs = nowMs
        val d = scanner.diagnostics
        val rot = ((rotationDegrees % 360) + 360) % 360
        val upW = if (rot == 90 || rot == 270) frame.height else frame.width
        val upH = if (rot == 90 || rot == 270) frame.width else frame.height
        val step = maxOf(1, (maxOf(upW, upH) + PREVIEW_SIDE - 1) / PREVIEW_SIDE)
        val pw = upW / step
        val ph = upH / step
        val preview = ByteArray(pw * ph)
        for (v in 0 until ph) for (u in 0 until pw) {
            val uu = u * step
            val vv = v * step
            val x: Int
            val y: Int
            when (rot) {
                90 -> { x = vv; y = frame.height - 1 - uu }
                180 -> { x = frame.width - 1 - uu; y = frame.height - 1 - vv }
                270 -> { x = frame.width - 1 - vv; y = uu }
                else -> { x = uu; y = vv }
            }
            preview[v * pw + u] = frame.luma[y.coerceIn(0, frame.height - 1) * frame.width + x.coerceIn(0, frame.width - 1)]
        }
        fun nx(x: Double, y: Double): Float = when (rot) {
            90 -> (1.0 - y / frame.height).toFloat()
            180 -> (1.0 - x / frame.width).toFloat()
            270 -> (y / frame.height).toFloat()
            else -> (x / frame.width).toFloat()
        }
        fun ny(x: Double, y: Double): Float = when (rot) {
            90 -> (x / frame.width).toFloat()
            180 -> (1.0 - y / frame.height).toFloat()
            270 -> (1.0 - x / frame.width).toFloat()
            else -> (y / frame.height).toFloat()
        }
        serial++
        _snapshot.value = RingDiagSnapshot(
            serial = serial,
            previewWidth = pw,
            previewHeight = ph,
            preview = preview,
            found = d.found,
            centreX = nx(d.centreX, d.centreY),
            centreY = ny(d.centreX, d.centreY),
            radius = (d.bezelRadiusPx / upW).toFloat(),
            markerFound = d.markerFound,
            markerX = nx(d.markerX, d.markerY),
            markerY = ny(d.markerX, d.markerY),
            matched = d.matched,
            residualPx = d.residualPx.toFloat(),
            rsCorrected = d.rsCorrected,
            erasures = d.erasuresUsed,
            scanMillis = d.scanMicros / 1000f,
            candidates = d.candidates,
            outcome = outcome,
            confidence = d.slotConfidence.copyOf(),
            bits = d.slotBits.copyOf(),
            framesPerSecond = framesPerSecond,
            scansPerSecond = scansPerSecond,
            frameWidth = frame.width,
            frameHeight = frame.height,
            tooSlow = slow,
        )
    }

    private fun describe(result: RingScanResult): String = when (result) {
        is RingScanResult.Decoded -> "decoded ${result.payload.videoId} tag ${result.payload.tag()} (fixed ${result.corrected}, erasures ${result.erasures})"
        is RingScanResult.Failed -> result.reason.code
    }

    /** Decodes are always logged; a failure only when it differs from the last line or a second has passed. */
    private fun addLog(result: RingScanResult, text: String, nowMs: Long, scanner: RingScanner) {
        val key = if (result is RingScanResult.Failed) result.reason.code else "ok"
        val decoded = result is RingScanResult.Decoded
        if (!decoded && key == lastLogKey && nowMs - lastLogMs < 1000L) return
        lastLogKey = key
        lastLogMs = nowMs
        val d = scanner.diagnostics
        val line = "${stamp(nowMs)}  $text  dots ${d.matched}/${RingCodeLayout.SLOTS}  ${"%.1f".format(d.scanMicros / 1000f)} ms"
        _log.value = (_log.value + line).takeLast(LOG_SIZE)
    }

    private fun stamp(ms: Long): String {
        val s = (ms / 1000L) % 3600L
        return "%02d:%02d.%03d".format(s / 60, s % 60, ms % 1000L)
    }

    /** Everything worth pasting into a message: the thresholds, the numbers and the last results. */
    fun copyText(device: String): String {
        val s = _snapshot.value
        return buildString {
            appendLine("Ring code diagnostics")
            appendLine("device: $device")
            appendLine("frame: ${s.frameWidth} x ${s.frameHeight}, ${s.framesPerSecond} frames/s, ${s.scansPerSecond} scans/s, last scan ${"%.1f".format(s.scanMillis)} ms")
            appendLine("plate found: ${s.found}, start dot found: ${s.markerFound}, dots matched ${s.matched}/${RingCodeLayout.SLOTS}, residual ${"%.2f".format(s.residualPx)} px")
            appendLine("reed-solomon: fixed ${s.rsCorrected}, erasures ${s.erasures}, outline candidates ${s.candidates}")
            appendLine("scanner too slow for this phone: ${s.tooSlow}")
            appendLine("last outcome: ${s.outcome}")
            val low = s.confidence.count { it in 1..39 }
            val zero = s.confidence.count { it == 0 }
            appendLine("slot confidence: $zero unknown, $low weak, ${s.confidence.size - zero - low} clear")
            appendLine("last results:")
            for (line in _log.value) appendLine(line)
        }
    }

    private companion object {
        const val LOG_SIZE = 20
        const val PUBLISH_GAP_MS = 200L
        const val PREVIEW_SIDE = 320
    }
}
