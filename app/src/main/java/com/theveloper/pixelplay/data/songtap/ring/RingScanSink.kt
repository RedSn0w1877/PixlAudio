package com.theveloper.pixelplay.data.songtap.ring

import com.theveloper.pixelplay.songtap.ring.image.RingFrame
import com.theveloper.pixelplay.songtap.ring.image.RingScanResult
import com.theveloper.pixelplay.songtap.ring.image.RingScanner

/** Where the camera's analysis thread reports to. Called on that thread: keep each call short. */
interface RingScanSink {
    /** A camera frame arrived (scanned or skipped), for the frame rate. */
    fun onFrame(nowMs: Long)

    /** One frame was scanned. [frame]'s buffers are reused by the next frame: copy what you need before returning. */
    fun onScan(frame: RingFrame, result: RingScanResult, scanner: RingScanner, rotationDegrees: Int, nowMs: Long)

    /** The scanner cannot keep up on this phone. */
    fun onSlow()
}
