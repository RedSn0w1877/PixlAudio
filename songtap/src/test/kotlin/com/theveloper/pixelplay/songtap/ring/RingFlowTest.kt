package com.theveloper.pixelplay.songtap.ring

import com.theveloper.pixelplay.songtap.ring.image.RingScanFailure
import com.theveloper.pixelplay.songtap.ring.image.RingScanResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The ring code's rules over time, with a fake scanner: results are handed in, nothing here touches a picture. */
class RingFlowTest {
    private val video = "dQw4w9WgXcQ"
    private val t0 = 1_763_600_000_000L

    private fun payload(bucketAgeMin: Int = 0, random: Int = 17, id: String = video, now: Long = t0): RingCodePayload =
        RingCodePayload(id, (RingCodePayloads.bucketOf(now) - bucketAgeMin + RingCodePayloads.MINUTE_BUCKETS) % RingCodePayloads.MINUTE_BUCKETS, random)

    private fun decoded(p: RingCodePayload) = RingScanResult.Decoded(p, corrected = 2, erasures = 0)
    private val failed = RingScanResult.Failed(RingScanFailure.NO_PLATE)

    /** Three equal decodes 150 ms apart: what a steady camera gives. */
    private fun RingReceiveFlow.read(p: RingCodePayload, from: Long): RingStep {
        var step: RingStep = RingStep.None
        for (i in 0 until 3) step = offer(decoded(p), from + i * 150L)
        return step
    }

    @Test
    fun `a steady fresh code is taken after three equal reads and then looked up`() {
        val flow = RingReceiveFlow()
        flow.start(t0, cameraAvailable = true)
        val p = payload()
        assertTrue(flow.offer(decoded(p), t0 + 100) is RingStep.None)
        assertTrue(flow.offer(decoded(p), t0 + 250) is RingStep.None)
        val step = flow.offer(decoded(p), t0 + 400)
        assertTrue(step is RingStep.Resolve)
        assertEquals(p, (step as RingStep.Resolve).payload)
        assertEquals(RingScanStage.RESOLVING, flow.view().stage)
        flow.resolved(p, ok = true, nowMs = t0 + 900)
        assertEquals(RingScanStage.IDLE, flow.view().stage)
    }

    @Test
    fun `two different codes in between never add up`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        assertTrue(flow.offer(decoded(payload(random = 1)), t0 + 100) is RingStep.None)
        assertTrue(flow.offer(decoded(payload(random = 2)), t0 + 250) is RingStep.None)
        assertTrue(flow.offer(decoded(payload(random = 1)), t0 + 400) is RingStep.None)
        assertEquals(RingScanStage.SCANNING, flow.view().stage)
    }

    @Test
    fun `the QR is not offered at first and is offered after six seconds of a scan that reads nothing`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        for (ms in 0 until 5_900 step 130) flow.offer(failed, t0 + ms)
        assertFalse(flow.tick(t0 + 5_900))
        assertFalse(flow.view().qrOffered)
        assertTrue(flow.tick(t0 + 6_000))
        assertTrue(flow.view().qrOffered)
        assertEquals(RingScanStage.SCANNING, flow.view().stage)
        // Offered once, and it stays offered while the scan goes on.
        assertFalse(flow.tick(t0 + 6_500))
        assertTrue(flow.view().qrOffered)
    }

    @Test
    fun `a scan that keeps reading codes is working and does not offer the QR yet`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        // Alternating codes never reach three in a row, but every frame decodes.
        for (i in 0 until 60) flow.offer(decoded(payload(random = i % 2)), t0 + i * 130L)
        assertFalse(flow.tick(t0 + 7_000))
        assertFalse(flow.view().qrOffered)
        // Once the decodes stop for two seconds, it is offered.
        assertTrue(flow.tick(t0 + 10_500))
    }

    @Test
    fun `no camera offers the QR at once, with nothing to scan`() {
        val flow = RingReceiveFlow()
        val v = flow.start(t0, cameraAvailable = false)
        assertEquals(RingScanStage.UNAVAILABLE, v.stage)
        assertTrue(v.qrOffered)
        assertTrue(flow.offer(decoded(payload()), t0 + 10) is RingStep.None)
    }

    @Test
    fun `a camera that fails while scanning offers the QR`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        val v = flow.cameraFailed()
        assertEquals(RingScanStage.UNAVAILABLE, v.stage)
        assertTrue(v.qrOffered)
    }

    @Test
    fun `a scanner too slow for this phone offers the QR`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        assertTrue(flow.scannerTooSlow().qrOffered)
        assertEquals(RingScanStage.SCANNING, flow.view().stage)
    }

    @Test
    fun `an expired tag is refused, says so and offers the QR`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        val step = flow.read(payload(bucketAgeMin = 5), t0 + 100)
        assertTrue(step is RingStep.Rejected)
        assertEquals(RingVerdict.EXPIRED, (step as RingStep.Rejected).verdict)
        val v = flow.view()
        assertEquals(RingScanMessage.EXPIRED, v.message)
        assertTrue(v.qrOffered)
        assertEquals(RingScanStage.SCANNING, v.stage)
    }

    @Test
    fun `a tag from the future (this clock is behind) is refused as a clock problem`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        val step = flow.read(payload(bucketAgeMin = -3), t0 + 100)
        assertEquals(RingVerdict.FUTURE, (step as RingStep.Rejected).verdict)
        assertEquals(RingScanMessage.CLOCK, flow.view().message)
        assertTrue(flow.view().qrOffered)
    }

    @Test
    fun `a code from two minutes ago, one minute ahead and this minute are all fresh`() {
        for (age in listOf(0, 1, 2, -1)) {
            val flow = RingReceiveFlow()
            flow.start(t0, true)
            assertTrue(flow.read(payload(bucketAgeMin = age), t0 + 100) is RingStep.Resolve, "age $age")
        }
    }

    @Test
    fun `a wrong tag never reaches the flow, a failed checksum is just a frame that read nothing`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        for (i in 0 until 30) assertTrue(flow.offer(RingScanResult.Failed(RingScanFailure.BAD_CHECKSUM), t0 + i * 130L) is RingStep.None)
        assertEquals(RingScanStage.SCANNING, flow.view().stage)
        // A tag whose checksum is wrong cannot even be unpacked.
        val data = RingCodePayloads.pack(video, 5, 9)!!
        data[10] = (data[10].toInt() xor 0x5A).toByte()
        assertTrue(RingCodePayloads.unpack(data) is RingUnpack.Rejected)
    }

    @Test
    fun `the same code is taken once and a replay of it is refused`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        val p = payload()
        assertTrue(flow.read(p, t0 + 100) is RingStep.Resolve)
        flow.resolved(p, ok = true, nowMs = t0 + 800)
        // The listener scans again (or never left the code): the same video and tag again.
        flow.start(t0 + 5_000, true)
        val step = flow.read(p, t0 + 5_100)
        assertEquals(RingVerdict.REPLAY, (step as RingStep.Rejected).verdict)
        assertEquals(RingScanMessage.REPLAY, flow.view().message)
        assertFalse(flow.view().qrOffered)
        // A new code for the same song (a new tag) is a different code and is taken.
        assertTrue(flow.read(payload(random = 18), t0 + 5_800) is RingStep.Resolve)
    }

    @Test
    fun `a lookup that fails goes back to scanning with the QR on offer and the code is not forgotten`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        val p = payload()
        assertTrue(flow.read(p, t0 + 100) is RingStep.Resolve)
        val v = flow.resolved(p, ok = false, nowMs = t0 + 700)
        assertEquals(RingScanStage.SCANNING, v.stage)
        assertEquals(RingScanMessage.LOOKUP_FAILED, v.message)
        assertTrue(v.qrOffered)
        // The same code is left alone for three seconds, then may be tried again.
        assertTrue(flow.read(p, t0 + 1_000) is RingStep.None)
        assertTrue(flow.read(p, t0 + 4_000) is RingStep.Resolve)
    }

    @Test
    fun `results that arrive while a song is being looked up are ignored`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        val p = payload()
        assertTrue(flow.read(p, t0 + 100) is RingStep.Resolve)
        assertTrue(flow.read(payload(random = 99), t0 + 500) is RingStep.None)
        assertEquals(RingScanStage.RESOLVING, flow.view().stage)
    }

    @Test
    fun `stopping puts everything away`() {
        val flow = RingReceiveFlow()
        flow.start(t0, true)
        flow.cameraFailed()
        assertEquals(RingScanView.IDLE, flow.stop())
        assertTrue(flow.read(payload(), t0 + 100) is RingStep.None)
    }

    @Test
    fun `the guard keeps a bounded memory`() {
        val guard = RingCodeGuard(capacity = 4)
        for (i in 0 until 10) guard.commit(payload(random = i), t0 + i)
        // The oldest are gone, the newest remembered.
        assertEquals(RingVerdict.ACCEPT, guard.check(payload(random = 0), t0 + 20))
        assertEquals(RingVerdict.REPLAY, guard.check(payload(random = 9), t0 + 20))
        // And a code is forgotten after the memory window.
        assertEquals(RingVerdict.EXPIRED, guard.check(payload(random = 9), t0 + 10 * 60_000L))
    }

    @Test
    fun `only a song with a real video id can use the ring code`() {
        assertTrue(RingSendRules.canRing(video))
        assertFalse(RingSendRules.canRing(null))
        assertFalse(RingSendRules.canRing(""))
        assertFalse(RingSendRules.canRing("short"))
        assertFalse(RingSendRules.canRing("dQw4w9WgXc!"))
    }

    @Test
    fun `the sender offers the QR after six seconds and a code is made again every minute`() {
        assertFalse(RingSendRules.qrButtonVisible(5_999))
        assertTrue(RingSendRules.qrButtonVisible(6_000))
        assertEquals(60_000L, RingSendRules.CODE_LIFE_MS)
        // Two codes a minute apart carry different tags for the same song.
        val a = RingCodePayloads.encodeSlots(video, RingCodePayloads.bucketOf(t0), 10)!!
        val b = RingCodePayloads.encodeSlots(video, RingCodePayloads.bucketOf(t0 + 60_000L), 200)!!
        assertFalse(a.contentEquals(b))
    }
}
