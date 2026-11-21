package com.theveloper.pixelplay.songtap.ring

import com.theveloper.pixelplay.songtap.SplitMix64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RingLayoutTest {

    @Test
    fun `200 slots in five rings, 25 bytes of 8 slots`() {
        assertEquals(200, RingCodeLayout.RING_SLOTS.sum())
        assertEquals(RingCodeLayout.SLOTS, RingCodeLayout.BYTES * 8)
        var first = 0
        for (r in 0 until 5) {
            assertEquals(first, RingCodeLayout.RING_FIRST_SLOT[r])
            first += RingCodeLayout.RING_SLOTS[r]
            assertEquals(0, RingCodeLayout.RING_SLOTS[r] % 8, "a byte never straddles two rings")
        }
    }

    @Test
    fun `every slot has one ring, one index, one byte bit`() {
        val seen = HashSet<Int>()
        for (slot in 0 until 200) {
            val ring = RingCodeLayout.ringOf(slot)
            val index = RingCodeLayout.indexInRing(slot)
            assertEquals(slot, RingCodeLayout.slotOf(ring, index))
            assertTrue(seen.add(RingCodeLayout.byteOfSlot(slot) * 8 + RingCodeLayout.bitOfSlot(slot)))
            // The eight slots of a byte are neighbours on one ring.
            if (slot % 8 > 0) assertEquals(ring, RingCodeLayout.ringOf(slot - 1))
        }
        assertEquals(200, seen.size)
    }

    @Test
    fun `slot 0 of every ring is at twelve o'clock and angles run clockwise`() {
        for (r in 0 until 5) {
            assertEquals(0, RingCodeLayout.angleMicroTurns(RingCodeLayout.slotOf(r, 0)))
            val n = RingCodeLayout.RING_SLOTS[r]
            assertEquals(500_000, RingCodeLayout.angleMicroTurns(RingCodeLayout.slotOf(r, n / 2)))
            assertTrue(RingCodeLayout.angleMicroTurns(RingCodeLayout.slotOf(r, n - 1)) < 1_000_000)
        }
    }

    @Test
    fun `geometry never overlaps`() {
        // dots neither touch each other along a ring nor across rings, with a margin for blur
        for (r in 0 until 5) {
            val arc = 2L * 314159L * RingCodeLayout.RING_RADIUS[r] / 100000L / RingCodeLayout.RING_SLOTS[r] // ~ 2*pi*R/N
            assertTrue(arc - 2 * RingCodeLayout.BIG_RADIUS >= 20, "ring $r gap ${arc - 2 * RingCodeLayout.BIG_RADIUS}")
        }
        for (r in 1 until 5) assertTrue(RingCodeLayout.RING_RADIUS[r] - RingCodeLayout.RING_RADIUS[r - 1] - 2 * RingCodeLayout.BIG_RADIUS >= 25)
        assertTrue(RingCodeLayout.RING_RADIUS[0] - RingCodeLayout.BIG_RADIUS - RingCodeLayout.BEZEL_OUTER >= 50)
        assertTrue(RingCodeLayout.MARKER_RADIUS_AT - RingCodeLayout.MARKER_RADIUS - (RingCodeLayout.RING_RADIUS[4] + RingCodeLayout.BIG_RADIUS) >= 10)
        assertTrue(RingCodeLayout.MARKER_RADIUS_AT + RingCodeLayout.MARKER_RADIUS <= 960)
        assertEquals(2 * RingCodeLayout.SMALL_RADIUS, RingCodeLayout.BIG_RADIUS)
    }

    @Test
    fun `codeword and slots round trip`() {
        val rng = SplitMix64(1)
        repeat(200) {
            val w = ByteArray(25) { (rng.nextLong() ushr 9).toInt().toByte() }
            assertArrayEquals(w, RingCodeLayout.slotsToCodeword(RingCodeLayout.codewordToSlots(w)))
        }
    }

    /** The spec's map spreads a neighbouring smudge over every byte; the grouped map keeps it to a few. */
    @Test
    fun `a smudge of neighbouring slots touches few bytes with the grouped map and all with the spec's`() {
        var worstGrouped = 0
        var worstSpec = 0
        for (ring in 0 until 5) {
            val n = RingCodeLayout.RING_SLOTS[ring]
            val smudge = n / 5 // 20 percent of the ring
            for (start in 0 until n) {
                val first = RingCodeLayout.slotOf(ring, start)
                worstGrouped = maxOf(worstGrouped, RingCodeLayout.bytesTouched(first, smudge, RingCodeLayout::byteOfSlot))
                worstSpec = maxOf(worstSpec, RingCodeLayout.bytesTouched(first, smudge, RingCodeLayout::specByteOfSlot))
            }
        }
        // 20 percent of the outer ring is 11 slots: at most 3 bytes grouped, 11 bytes with the spec's map.
        assertTrue(worstGrouped <= 3, "grouped $worstGrouped")
        assertTrue(worstSpec >= 11, "spec $worstSpec")
        // A smudge over 20 percent of the whole code (40 neighbouring slots across rings) stays under 14 bytes grouped.
        var worstWedge = 0
        for (start in 0 until 56) worstWedge = maxOf(worstWedge, wedgeBytes(start, 72_000, RingCodeLayout::byteOfSlot))
        assertTrue(worstWedge <= 14, "a 72 degree wedge touches $worstWedge bytes")
    }

    /** Bytes touched by the slots whose angle falls in a wedge of [widthMicroTurns]/1e6 of a turn starting at outer slot [startOuter]. */
    private fun wedgeBytes(startOuter: Int, widthMicroTurns: Int, byteOf: (Int) -> Int): Int {
        val begin = startOuter * 1_000_000 / 56
        val seen = BooleanArray(25)
        for (slot in 0 until 200) {
            val a = RingCodeLayout.angleMicroTurns(slot)
            val d = ((a - begin) % 1_000_000 + 1_000_000) % 1_000_000
            if (d < widthMicroTurns) seen[byteOf(slot)] = true
        }
        return seen.count { it }
    }
}

class RingReedSolomonTest {

    private val rng = SplitMix64(0xC0DEL)

    private fun randomData() = ByteArray(11) { (rng.nextLong() ushr 7).toInt().toByte() }

    private fun positions(n: Int): IntArray {
        val out = ArrayList<Int>()
        while (out.size < n) { val p = rng.nextInt(25); if (p !in out) out += p }
        return out.toIntArray()
    }

    @Test
    fun `field basics`() {
        assertEquals(1, Gf256.mul(1, 1))
        assertEquals(0x1D, Gf256.mul(0x80, 2))
        for (a in 1..255) assertEquals(1, Gf256.mul(a, Gf256.inv(a)))
        for (a in 1..255) for (b in intArrayOf(1, 2, 3, 0x1D, 0xFF)) assertEquals(a, Gf256.div(Gf256.mul(a, b), b))
        assertEquals(Gf256.pow(3), Gf256.pow(3 + 255))
        assertEquals(1, Gf256.mul(Gf256.pow(-7), Gf256.pow(7)))
    }

    @Test
    fun `encode is systematic and gives a codeword`() {
        repeat(200) {
            val data = randomData()
            val w = ReedSolomon.encode(data)
            assertEquals(25, w.size)
            assertArrayEquals(data, w.copyOfRange(0, 11))
            assertTrue(ReedSolomon.isCodeword(w))
        }
    }

    @Test
    fun `corrects every pattern of up to seven byte errors`() {
        var trials = 0
        for (errors in 0..7) repeat(2500) {
            val data = randomData()
            val w = ReedSolomon.encode(data)
            val bad = w.copyOf()
            for (p in positions(errors)) bad[p] = (bad[p].toInt() xor (1 + rng.nextInt(255))).toByte()
            val r = ReedSolomon.decode(bad)
            assertTrue(r.ok, "errors=$errors")
            assertArrayEquals(w, r.codeword)
            assertEquals(errors, r.corrected)
            trials++
        }
        assertEquals(20_000, trials)
    }

    @Test
    fun `corrects up to fourteen erasures and any mix with 2e + s at most 14`() {
        for (s in 0..14) repeat(300) {
            val w = ReedSolomon.encode(randomData())
            val er = positions(s)
            val bad = w.copyOf()
            for (p in er) bad[p] = (rng.nextLong() ushr 5).toInt().toByte()
            val r = ReedSolomon.decode(bad, er)
            assertTrue(r.ok, "erasures=$s")
            assertArrayEquals(w, r.codeword)
        }
        for (e in 1..7) for (s in 0..(14 - 2 * e)) repeat(150) {
            val w = ReedSolomon.encode(randomData())
            val all = positions(e + s)
            val bad = w.copyOf()
            for (i in 0 until e) bad[all[i]] = (bad[all[i]].toInt() xor (1 + rng.nextInt(255))).toByte()
            val er = IntArray(s) { all[e + it] }
            for (p in er) bad[p] = (rng.nextLong() ushr 5).toInt().toByte()
            val r = ReedSolomon.decode(bad, er)
            assertTrue(r.ok, "e=$e s=$s")
            assertArrayEquals(w, r.codeword)
        }
    }

    @Test
    fun `an erasure whose value happens to be right costs nothing`() {
        val w = ReedSolomon.encode(randomData())
        val r = ReedSolomon.decode(w, intArrayOf(0, 5, 24))
        assertTrue(r.ok)
        assertArrayEquals(w, r.codeword)
    }

    /** Beyond capacity the decoder may fail or (very rarely: about one word in 10^11) land on another codeword; it must never crash and never return a non-codeword. */
    @Test
    fun `fuzz beyond capacity never crashes and never returns a non-codeword`() {
        var failed = 0
        var miscorrected = 0
        val n = 60_000
        repeat(n) {
            val w = ReedSolomon.encode(randomData())
            val bad = w.copyOf()
            val errors = 8 + rng.nextInt(18)
            for (p in positions(errors)) bad[p] = (bad[p].toInt() xor (1 + rng.nextInt(255))).toByte()
            val r = ReedSolomon.decode(bad)
            if (!r.ok) failed++ else {
                assertTrue(ReedSolomon.isCodeword(r.codeword!!))
                if (!r.codeword!!.contentEquals(w)) miscorrected++
            }
        }
        assertTrue(failed > n * 9 / 10, "failed $failed of $n")
        println("RS fuzz: $n words with 8..25 byte errors: $failed rejected, $miscorrected decoded to another codeword (the decoding spheres cover about 1 in 10^11 of all words)")
    }

    @Test
    fun `garbage and odd input never crash`() {
        repeat(20_000) {
            val junk = ByteArray(25) { (rng.nextLong() ushr 3).toInt().toByte() }
            val s = rng.nextInt(17)
            val er = IntArray(s) { rng.nextInt(27) - 1 } // may be out of range or duplicated
            ReedSolomon.decode(junk, er)
            ReedSolomon.decode(junk)
        }
        assertFalse(ReedSolomon.decode(ByteArray(24)).ok)
        assertFalse(ReedSolomon.decode(ByteArray(26)).ok)
        assertFalse(ReedSolomon.decode(ByteArray(25), IntArray(15) { it }).ok)
        assertFalse(ReedSolomon.decode(ByteArray(25), intArrayOf(3, 3)).ok)
        assertFalse(ReedSolomon.decode(ByteArray(25), intArrayOf(25)).ok)
        assertTrue(ReedSolomon.decode(ByteArray(25)).ok) // all zeros is the zero codeword
    }
}

class RingPayloadTest {

    private val rng = SplitMix64(0xFACEL)
    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private fun randomId() = String(CharArray(11) { alphabet[rng.nextInt(64)] })

    @Test
    fun `pack and unpack round trip for random ids and tags`() {
        repeat(5000) {
            val id = randomId()
            val bucket = rng.nextInt(64)
            val random = rng.nextInt(256)
            val packed = RingCodePayloads.pack(id, bucket, random)!!
            assertEquals(11, packed.size)
            val r = RingCodePayloads.unpack(packed)
            assertTrue(r is RingUnpack.Ok)
            assertEquals(RingCodePayload(id, bucket, random), (r as RingUnpack.Ok).payload)
        }
    }

    @Test
    fun `every alphabet character survives`() {
        val id = alphabet.substring(0, 11)
        val r = RingCodePayloads.unpack(RingCodePayloads.pack(id, 5, 5)!!) as RingUnpack.Ok
        assertEquals(id, r.payload.videoId)
        for (c in alphabet) {
            val one = "$c".repeat(11)
            assertEquals(one, (RingCodePayloads.unpack(RingCodePayloads.pack(one, 0, 0)!!) as RingUnpack.Ok).payload.videoId)
        }
    }

    @Test
    fun `invalid input is refused`() {
        assertNull(RingCodePayloads.pack("short", 1, 1))
        assertNull(RingCodePayloads.pack("dQw4w9WgXcQQ", 1, 1))
        assertNull(RingCodePayloads.pack("dQw4w9WgX.Q", 1, 1))
        assertNull(RingCodePayloads.pack("dQw4w9WgXcQ", 64, 1))
        assertNull(RingCodePayloads.pack("dQw4w9WgXcQ", -1, 1))
        assertNull(RingCodePayloads.pack("dQw4w9WgXcQ", 1, 256))
        assertNotNull(RingCodePayloads.pack("dQw4w9WgXcQ", 63, 255))
        assertTrue(RingCodePayloads.unpack(ByteArray(10)) is RingUnpack.Rejected)
        assertTrue(RingCodePayloads.unpack(ByteArray(12)) is RingUnpack.Rejected)
    }

    @Test
    fun `any single bit flip is caught by the checksum or changes a field the receiver then checks`() {
        // 88 bits: the first 80 are covered by the checksum, so every flip there is rejected.
        val packed = RingCodePayloads.pack("dQw4w9WgXcQ", 17, 99)!!
        for (bit in 0 until 88) {
            val f = packed.copyOf()
            f[bit / 8] = (f[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
            assertTrue(RingCodePayloads.unpack(f) is RingUnpack.Rejected, "bit $bit")
        }
    }

    @Test
    fun `fuzz random 11 byte words are rejected about 255 times in 256`() {
        var ok = 0
        val n = 200_000
        repeat(n) {
            val junk = ByteArray(11) { (rng.nextLong() ushr 11).toInt().toByte() }
            if (RingCodePayloads.unpack(junk) is RingUnpack.Ok) ok++
        }
        // expectation n/256 = 781
        assertTrue(ok in 500..1100, "accepted $ok of $n")
    }

    @Test
    fun `buckets follow the clock`() {
        assertEquals(0, RingCodePayloads.bucketOf(0L))
        assertEquals(0, RingCodePayloads.bucketOf(59_999L))
        assertEquals(1, RingCodePayloads.bucketOf(60_000L))
        assertEquals(63, RingCodePayloads.bucketOf(63 * 60_000L))
        assertEquals(0, RingCodePayloads.bucketOf(64 * 60_000L))
        assertEquals(63, RingCodePayloads.bucketOf(-1L))
    }

    @Test
    fun `freshness window and clock skew`() {
        val base = 1_763_600_000_000L
        val bucket = RingCodePayloads.bucketOf(base)
        fun at(minutes: Int, secondsIn: Long = 0) = base - base % 60_000L + minutes * 60_000L + secondsIn * 1000L
        // The receiver's clock, in minutes after the code's minute.
        assertEquals(Freshness.FUTURE, RingCodePayloads.freshness(bucket, at(-2)))
        assertEquals(Freshness.FRESH, RingCodePayloads.freshness(bucket, at(-1)))   // receiver one minute behind
        assertEquals(Freshness.FRESH, RingCodePayloads.freshness(bucket, at(0)))
        assertEquals(Freshness.FRESH, RingCodePayloads.freshness(bucket, at(1)))
        assertEquals(Freshness.FRESH, RingCodePayloads.freshness(bucket, at(2, 59)))
        assertEquals(Freshness.EXPIRED, RingCodePayloads.freshness(bucket, at(3)))
        assertEquals(Freshness.EXPIRED, RingCodePayloads.freshness(bucket, at(30)))
        // A code made at the very end of a minute lives just over two minutes.
        val madeAt = at(0, 59)
        assertEquals(Freshness.FRESH, RingCodePayloads.freshness(RingCodePayloads.bucketOf(madeAt), madeAt + 120_000L))
        assertEquals(Freshness.EXPIRED, RingCodePayloads.freshness(RingCodePayloads.bucketOf(madeAt), madeAt + 180_000L))
        // Bucket arithmetic wraps at 64.
        assertEquals(Freshness.FRESH, RingCodePayloads.freshness(63, 64 * 60_000L + 1_000L))
        assertEquals(Freshness.FRESH, RingCodePayloads.freshness(0, 63 * 60_000L + 1_000L))
    }

    @Test
    fun `the whole word round trips through slots and a damaged copy`() {
        repeat(300) {
            val id = randomId()
            val bucket = rng.nextInt(64)
            val random = rng.nextInt(256)
            val bits = RingCodePayloads.encodeSlots(id, bucket, random)!!
            // flip up to 20 isolated slots inside different bytes (at most 7 bytes)
            val damaged = bits.copyOf()
            val bytes = (0 until 25).shuffled(java.util.Random(rng.nextLong())).take(7)
            for (b in bytes) { val slot = b * 8 + rng.nextInt(8); damaged[slot] = (1 - damaged[slot]).toByte() }
            val rs = ReedSolomon.decode(RingCodeLayout.slotsToCodeword(damaged))
            assertTrue(rs.ok)
            val p = RingCodePayloads.fromCodeword(rs.codeword!!) as RingUnpack.Ok
            assertEquals(RingCodePayload(id, bucket, random), p.payload)
        }
    }

    /** A decode that leans on erasures can accept a wrong word; the checksum is what catches it. The scanner uses at most 12. */
    @Test
    fun `erasure-heavy decodes of a wrong word are caught`() {
        var wrongByRs = 0
        var wrongPassing12 = 0
        var wrongPassing14 = 0
        val n = 40_000
        repeat(n) {
            val id = randomId()
            val word = RingCodePayloads.encodeCodeword(id, rng.nextInt(64), rng.nextInt(256))!!
            val original = (RingCodePayloads.unpack(word.copyOfRange(0, 11)) as RingUnpack.Ok).payload
            // 14 erasures plus one wrong byte among the 11 that remain: RS cannot see it, the checksum must
            val order = (0 until 25).shuffled(java.util.Random(rng.nextLong()))
            val bad = word.copyOf()
            bad[order[14]] = (bad[order[14]].toInt() xor (1 + rng.nextInt(255))).toByte()
            val r14 = ReedSolomon.decode(bad, IntArray(14) { order[it] })
            if (r14.ok && !r14.codeword!!.contentEquals(word)) {
                wrongByRs++
                val u = RingCodePayloads.fromCodeword(r14.codeword!!)
                if (u is RingUnpack.Ok && u.payload != original) wrongPassing14++
            }
            // 10 erasures plus three wrong bytes: beyond 2e + s <= 14 (the scanner never flags more than 10)
            val bad12 = word.copyOf()
            for (k in 10..12) bad12[order[k]] = (bad12[order[k]].toInt() xor (1 + rng.nextInt(255))).toByte()
            val r12 = ReedSolomon.decode(bad12, IntArray(10) { order[it] })
            if (r12.ok && !r12.codeword!!.contentEquals(word)) {
                val u = RingCodePayloads.fromCodeword(r12.codeword!!)
                if (u is RingUnpack.Ok && u.payload != original) wrongPassing12++
            }
        }
        println("erasure fuzz: $n words; 14 erasures + 1 wrong byte: $wrongByRs decoded to a wrong codeword at the RS level, $wrongPassing14 of them passed the checksum (about 1 in 256); 10 erasures + 3 wrong bytes: $wrongPassing12 passed the checksum (12 erasures + 2 wrong passes about 1 in 7000: the scanner stops at 10)")
        assertTrue(wrongByRs > n / 2)
        assertTrue(wrongPassing12 <= 1, "$wrongPassing12 wrong words passed")
    }

    /** The scanner's whole decision chain on a heavily damaged word: never a wrong id without a flag. */
    @Test
    fun `fuzz damaged codes decode to the right payload or are refused`() {
        var right = 0
        var refused = 0
        var rsMisread = 0
        val n = 150_000
        repeat(n) {
            val id = randomId()
            val bucket = rng.nextInt(64)
            val random = rng.nextInt(256)
            val word = RingCodePayloads.encodeCodeword(id, bucket, random)!!
            val bad = word.copyOf()
            val errors = 8 + rng.nextInt(10)
            val seen = HashSet<Int>()
            while (seen.size < errors) seen += rng.nextInt(25)
            for (p in seen) bad[p] = (bad[p].toInt() xor (1 + rng.nextInt(255))).toByte()
            val rs = ReedSolomon.decode(bad)
            if (!rs.ok) { refused++; return@repeat }
            val r = RingCodePayloads.fromCodeword(rs.codeword!!)
            if (r is RingUnpack.Rejected) { refused++; if (!rs.codeword!!.contentEquals(word)) rsMisread++; return@repeat }
            val got = (r as RingUnpack.Ok).payload
            assertEquals(RingCodePayload(id, bucket, random), got, "a damaged code was taken for a different song")
            right++
        }
        println("payload fuzz: $n words with 8..17 byte errors: $refused refused ($rsMisread of them RS miscorrections the checksum caught), $right read right")
    }
}

class RingTimelineTest {

    private fun frame(t: Long, a: Long = RingCodeTimeline.NONE, ae: Long = RingCodeTimeline.NONE, b: Long = RingCodeTimeline.NONE, reduced: Boolean = false) =
        RingCodeTimeline.frame(t, a, ae, b, reduced)

    @Test
    fun `idle sways, breathes and never leaves its range`() {
        var maxAngle = 0
        var t = 0L
        while (t < 48_000L) {
            val f = frame(t)
            assertEquals(1000, f.codeScale)
            assertEquals(0, f.glow)
            assertEquals(1000, f.plateAlpha)
            assertTrue(f.artScale in 990..1010, "breath ${f.artScale}")
            assertTrue(Math.abs(f.codeAngle) <= RingCodeTimeline.SWAY_AMPLITUDE_MICRO_RAD)
            maxAngle = maxOf(maxAngle, Math.abs(f.codeAngle))
            t += 37
        }
        assertTrue(maxAngle > 1_300_000)
        assertEquals(0, frame(0).codeAngle)
        assertEquals(0, frame(RingCodeTimeline.SWAY_PERIOD_MS).codeAngle)
        assertEquals(frame(5_000).codeAngle, frame(5_000 + RingCodeTimeline.SWAY_PERIOD_MS).codeAngle)
        // continuous: neighbouring milliseconds differ by little (speed under 0.5 rad/s)
        for (t2 in 0L until 24_000L step 13L) assertTrue(Math.abs(frame(t2 + 1).codeAngle - frame(t2).codeAngle) < 400)
    }

    @Test
    fun `approach tightens by eight percent and glows, then relaxes`() {
        assertEquals(1000, frame(1800, 1800).codeScale)
        val full = frame(1800 + 800, 1800)
        assertEquals(920, full.codeScale)
        assertEquals(700, full.glow)
        assertEquals(1, full.phase)
        val mid = frame(1800 + 400, 1800)
        assertTrue(mid.codeScale in 921..999)
        val gone = frame(2600 + 400, 1800, 2600)
        assertEquals(1000, gone.codeScale)
        assertEquals(0, gone.glow)
        assertEquals(0, gone.phase)
        // monotone rise
        var last = 1001
        for (t in 1800L..2600L step 20L) { val s = frame(t, 1800).codeScale; assertTrue(s <= last); last = s }
    }

    @Test
    fun `the beam peels the outer ring first and everything lands`() {
        val start = 3400L
        // first dot of the outer ring starts at 0 ms, the inner ring's last about 280 ms later
        assertTrue(RingCodeTimeline.slotStartMs(4, 0) < RingCodeTimeline.slotStartMs(3, 0) + 70)
        for (ring in 0 until 4) {
            val outerMax = (0 until RingCodeLayout.RING_SLOTS[ring + 1]).maxOf { RingCodeTimeline.slotStartMs(ring + 1, it) }
            val innerMin = (0 until RingCodeLayout.RING_SLOTS[ring]).minOf { RingCodeTimeline.slotStartMs(ring, it) }
            assertTrue(RingCodeTimeline.slotStartMs(ring + 1, 0) < RingCodeTimeline.slotStartMs(ring, 0), "ring order")
            assertTrue(outerMax < innerMin + RingCodeTimeline.RING_STAGGER_MS + RingCodeTimeline.IN_RING_SPREAD_MS)
        }
        var lastStart = 0
        for (ring in 0 until 5) for (i in 0 until RingCodeLayout.RING_SLOTS[ring]) {
            val s = RingCodeTimeline.slotStartMs(ring, i)
            lastStart = maxOf(lastStart, s)
            assertEquals(0, RingCodeTimeline.slotProgress(ring, i, s))
            assertEquals(1000, RingCodeTimeline.slotProgress(ring, i, s + 800))
        }
        assertTrue(lastStart + 800 < 1300, "all dots are in before the art lifts")
        val f0 = frame(start, 1800, RingCodeTimeline.NONE, start)
        assertEquals(2, f0.phase)
        assertEquals(1000, f0.plateAlpha)
        val f1 = frame(start + 1000, 1800, RingCodeTimeline.NONE, start)
        assertEquals(0, f1.plateAlpha)
        assertTrue(f1.glow < f0.glow + 700)
        // the art pulses 10 percent, then flies off the top at 0.38
        var peak = 0
        for (dt in 600L..1400L step 10L) peak = maxOf(peak, frame(start + dt, 1800, RingCodeTimeline.NONE, start).artScale)
        assertEquals(1100, peak)
        val end = frame(start + 2000, 1800, RingCodeTimeline.NONE, start)
        assertEquals(380, end.artScale)
        assertEquals(-1100, end.artTranslateY)
        assertEquals(0, end.artAlpha)
        val done = frame(start + 2150, 1800, RingCodeTimeline.NONE, start)
        assertEquals(1000, done.pillAlpha)
        assertEquals(3, done.phase)
        assertEquals(0, frame(start + 1000, b = start).pillAlpha)
    }

    @Test
    fun `reduce motion only cross-fades`() {
        val b = 1000L
        for (t in 0L..3000L step 25L) {
            val f = frame(t, 100, RingCodeTimeline.NONE, b, true)
            assertEquals(0, f.codeAngle)
            assertEquals(1000, f.codeScale)
            assertEquals(0, f.glow)
            assertEquals(1000, f.artScale)
            assertEquals(0, f.artTranslateY)
            assertEquals(1000, f.codeAlpha + f.pillAlpha, "cross-fade at $t")
        }
        assertEquals(0, frame(1000, b = 1000, reduced = true).pillAlpha)
        assertEquals(1000, frame(1150, b = 1000, reduced = true).pillAlpha)
        assertEquals(150L, RingCodeTimeline.beamTotalMs(true))
        assertEquals(2150L, RingCodeTimeline.beamTotalMs(false))
    }

    @Test
    fun `frames are pure and fill the buffer without leftovers`() {
        val buf = RingFrameBuffer()
        RingCodeTimeline.frameInto(5000, 1800, RingCodeTimeline.NONE, 3400, false, buf)
        val first = buf.describe()
        RingCodeTimeline.frameInto(10, RingCodeTimeline.NONE, RingCodeTimeline.NONE, RingCodeTimeline.NONE, false, buf)
        RingCodeTimeline.frameInto(5000, 1800, RingCodeTimeline.NONE, 3400, false, buf)
        assertEquals(first, buf.describe())
    }
}
