package com.theveloper.pixelplay.songtap.ring

import com.theveloper.pixelplay.songtap.GoldenJson
import com.theveloper.pixelplay.songtap.SplitMix64

/**
 * The golden vectors of the Pixl ring code's pure side (layout, Reed-Solomon, payload, timeline), appended
 * to Song Tap's golden folder as `ring-*.jsonl`. The image side (renderer, scanner, style) is floating
 * point and has no goldens: it is tested by round trips.
 */
object RingGolden {

    private fun line(fn: String, input: GoldenJson.() -> Unit, output: GoldenJson.() -> Unit): String {
        val w = GoldenJson().obj()
        w.str("fn", fn)
        w.obj("in"); w.input(); w.end()
        w.obj("out"); w.output(); w.end()
        return w.end().toString() + "\n"
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun fromHex(h: String): ByteArray = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun bitString(bits: ByteArray): String = bits.joinToString("") { if (it.toInt() != 0) "1" else "0" }

    fun generate(): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["ring-layout.jsonl"] = layout()
        files["ring-rs.jsonl"] = reedSolomon()
        files["ring-payload.jsonl"] = payload()
        files["ring-timeline.jsonl"] = timeline()
        return files
    }

    // ── layout ───────────────────────────────────────────────────────────────────────

    private fun layout(): String {
        val sb = StringBuilder()
        sb.append(line("constants", {}, {
            arr("ringSlots"); for (n in RingCodeLayout.RING_SLOTS) intItem(n); endArr()
            arr("ringFirstSlot"); for (n in RingCodeLayout.RING_FIRST_SLOT) intItem(n); endArr()
            arr("ringRadius"); for (n in RingCodeLayout.RING_RADIUS) intItem(n); endArr()
            int("slots", RingCodeLayout.SLOTS); int("bytes", RingCodeLayout.BYTES)
            int("artRadius", RingCodeLayout.ART_RADIUS); int("bezelInner", RingCodeLayout.BEZEL_INNER); int("bezelOuter", RingCodeLayout.BEZEL_OUTER)
            int("bigRadius", RingCodeLayout.BIG_RADIUS); int("smallRadius", RingCodeLayout.SMALL_RADIUS)
            int("markerAt", RingCodeLayout.MARKER_RADIUS_AT); int("markerRadius", RingCodeLayout.MARKER_RADIUS)
            int("plateCorner", RingCodeLayout.PLATE_CORNER); int("markerArgb", RingCodeLayout.MARKER_ARGB)
        }))
        for (slot in 0 until RingCodeLayout.SLOTS) {
            sb.append(line("slot", { int("slot", slot) }, {
                val ring = RingCodeLayout.ringOf(slot)
                int("ring", ring); int("index", RingCodeLayout.indexInRing(slot)); int("count", RingCodeLayout.RING_SLOTS[ring])
                int("microTurns", RingCodeLayout.angleMicroTurns(slot))
                int("byte", RingCodeLayout.byteOfSlot(slot)); int("bit", RingCodeLayout.bitOfSlot(slot))
            }))
        }
        val rng = SplitMix64(0x52494E47L)
        val words = ArrayList<ByteArray>()
        words += ByteArray(25)
        words += ByteArray(25) { 0xFF.toByte() }
        words += ByteArray(25) { it.toByte() }
        repeat(9) { words += ByteArray(25) { (rng.nextLong() ushr 8).toInt().toByte() } }
        for (w in words) {
            val bits = RingCodeLayout.codewordToSlots(w)
            sb.append(line("codewordToSlots", { str("hex", toHex(w)) }, { str("bits", bitString(bits)) }))
            sb.append(line("slotsToCodeword", { str("bits", bitString(bits)) }, { str("hex", toHex(RingCodeLayout.slotsToCodeword(bits))) }))
        }
        return sb.toString()
    }

    // ── Reed-Solomon ─────────────────────────────────────────────────────────────────

    private fun reedSolomon(): String {
        val sb = StringBuilder()
        for ((a, b) in listOf(1 to 1, 2 to 2, 0x80 to 0x1D, 0xFF to 0xFF, 0x53 to 0xCA, 7 to 0, 0 to 9)) {
            sb.append(line("gfMul", { int("a", a); int("b", b) }, { int("p", Gf256.mul(a, b)) }))
        }
        for (a in intArrayOf(1, 2, 3, 0x1D, 0x80, 0xFF)) sb.append(line("gfInv", { int("a", a) }, { int("inv", Gf256.inv(a)) }))
        val rng = SplitMix64(0x52535F31L)
        val datas = ArrayList<ByteArray>()
        datas += ByteArray(11)
        datas += ByteArray(11) { 0xFF.toByte() }
        datas += ByteArray(11) { (it + 1).toByte() }
        repeat(37) { datas += ByteArray(11) { (rng.nextLong() ushr 8).toInt().toByte() } }
        val words = ArrayList<ByteArray>()
        for (d in datas) {
            val w = ReedSolomon.encode(d)
            words += w
            sb.append(line("rsEncode", { str("data", toHex(d)) }, { str("codeword", toHex(w)) }))
        }
        // Decode: clean, 1..7 errors, erasures, mixes, beyond capacity.
        fun corrupt(w: ByteArray, errors: Int, erasures: Int): Pair<ByteArray, IntArray> {
            val positions = ArrayList<Int>()
            while (positions.size < errors + erasures) {
                val p = rng.nextInt(25)
                if (p !in positions) positions += p
            }
            val out = w.copyOf()
            for (i in 0 until errors) out[positions[i]] = (out[positions[i]].toInt() xor (1 + rng.nextInt(255))).toByte()
            val er = IntArray(erasures) { positions[errors + it] }
            for (p in er) out[p] = (rng.nextLong() ushr 8).toInt().toByte()
            return out to er
        }
        val plans = ArrayList<IntArray>() // errors, erasures
        for (e in 0..7) repeat(5) { plans += intArrayOf(e, 0) }
        for (s in 1..14) repeat(2) { plans += intArrayOf(0, s) }
        for (e in 1..6) for (s in listOf(1, 14 - 2 * e)) plans += intArrayOf(e, s)
        for (e in 8..12) repeat(3) { plans += intArrayOf(e, 0) }
        plans += intArrayOf(1, 13)
        var wi = 0
        for (plan in plans) {
            val w = words[wi % words.size]; wi++
            val s = minOf(plan[1], 14)
            val (bad, er) = corrupt(w, plan[0], s)
            val result = ReedSolomon.decode(bad, er)
            sb.append(line("rsDecode", {
                str("received", toHex(bad)); arr("erasures"); for (p in er) intItem(p); endArr()
            }, {
                bool("ok", result.ok)
                if (result.ok) { str("codeword", toHex(result.codeword!!)); int("corrected", result.corrected) }
            }))
        }
        return sb.toString()
    }

    // ── payload ──────────────────────────────────────────────────────────────────────

    private val IDS = listOf("dQw4w9WgXcQ", "AAAAAAAAAAA", "___________", "-----------", "a-b_c-d_e-f", "0123456789A", "kJQP7kiw5Fk", "9bZkp7q19f0")

    private fun payload(): String {
        val sb = StringBuilder()
        val rng = SplitMix64(0x50415950L)
        val cases = ArrayList<Triple<String, Int, Int>>()
        for (id in IDS) cases += Triple(id, 0, 0)
        for (id in IDS) cases += Triple(id, 63, 255)
        repeat(30) { cases += Triple(IDS[rng.nextInt(IDS.size)], rng.nextInt(64), rng.nextInt(256)) }
        for ((id, bucket, random) in cases) {
            val packed = RingCodePayloads.pack(id, bucket, random)
            sb.append(line("pack", { str("id", id); int("bucket", bucket); int("random", random) }, { hexOrNull("hex", packed) }))
            val slots = RingCodePayloads.encodeSlots(id, bucket, random)
            sb.append(line("encodeSlots", { str("id", id); int("bucket", bucket); int("random", random) }, {
                if (slots == null) raw("bits", "null") else str("bits", bitString(slots))
            }))
        }
        // Invalid inputs.
        for ((id, bucket, random) in listOf(Triple("short", 1, 1), Triple("dQw4w9WgXcQQ", 1, 1), Triple("dQw4w9WgX.Q", 1, 1), Triple("dQw4w9WgXcQ", 64, 1), Triple("dQw4w9WgXcQ", -1, 1), Triple("dQw4w9WgXcQ", 1, 256), Triple("dQw4w9WgXcQ", 1, -1))) {
            sb.append(line("pack", { str("id", id); int("bucket", bucket); int("random", random) }, { hexOrNull("hex", RingCodePayloads.pack(id, bucket, random)) }))
        }
        // Unpack: valid, single bit flips (checksum or not), wrong lengths.
        for ((id, bucket, random) in cases.take(12)) {
            val packed = RingCodePayloads.pack(id, bucket, random)!!
            sb.append(line("unpack", { str("hex", toHex(packed)) }, { unpacked(RingCodePayloads.unpack(packed)) }))
            for (bit in listOf(0, 17, 65, 66, 71, 79, 80, 87)) {
                val flipped = packed.copyOf()
                flipped[bit / 8] = (flipped[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
                sb.append(line("unpack", { str("hex", toHex(flipped)) }, { unpacked(RingCodePayloads.unpack(flipped)) }))
            }
        }
        sb.append(line("unpack", { str("hex", "00") }, { unpacked(RingCodePayloads.unpack(ByteArray(1))) }))
        sb.append(line("unpack", { str("hex", toHex(ByteArray(12))) }, { unpacked(RingCodePayloads.unpack(ByteArray(12))) }))
        // Buckets and freshness.
        for (now in longArrayOf(0L, 59_999L, 60_000L, 3_839_999L, 3_840_000L, 1_763_600_000_000L, 1_763_600_059_999L, -1L, -60_000L, -60_001L)) {
            sb.append(line("bucketOf", { long("now", now) }, { int("bucket", RingCodePayloads.bucketOf(now)) }))
        }
        val base = 1_763_600_000_000L
        val baseBucket = RingCodePayloads.bucketOf(base)
        for (deltaMin in -3..4) for (bucketShift in listOf(0, 63)) {
            val bucket = (baseBucket + bucketShift) % 64
            val now = base + deltaMin * 60_000L + 5_000L
            sb.append(line("freshness", { int("bucket", bucket); long("now", now) }, { str("verdict", RingCodePayloads.freshness(bucket, now).code) }))
        }
        for (bucket in 0 until 64 step 7) {
            sb.append(line("freshness", { int("bucket", bucket); long("now", base) }, { str("verdict", RingCodePayloads.freshness(bucket, base).code) }))
        }
        return sb.toString()
    }

    private fun GoldenJson.hexOrNull(name: String, b: ByteArray?) { hex(name, b) }

    private fun GoldenJson.unpacked(r: RingUnpack) {
        when (r) {
            is RingUnpack.Ok -> { bool("ok", true); str("id", r.payload.videoId); int("bucket", r.payload.minuteBucket); int("random", r.payload.random) }
            is RingUnpack.Rejected -> { bool("ok", false); str("reason", r.reason.code) }
        }
    }

    // ── timeline ─────────────────────────────────────────────────────────────────────

    private val SAMPLE_SLOTS = listOf(0 to 0, 0 to 12, 1 to 5, 2 to 20, 3 to 47, 4 to 0, 4 to 1, 4 to 28, 4 to 55)

    /** Golden frames: 0..2000 every 100 ms and 2000..7000 every 250 ms, in three scenarios, with and without Reduce Motion. */
    private fun timeline(): String {
        val sb = StringBuilder()
        for (x in 0..1000 step 100) {
            sb.append(line("easeNoop", { int("x", x) }, { int("smooth", com.theveloper.pixelplay.songtap.SongTapTimeline.smooth(x)) }))
        }
        val times = ArrayList<Long>()
        for (t in 0L..2000L step 100L) times += t
        var t = 2250L
        while (t <= 7000L) { times += t; t += 250L }
        val scenarios = listOf(
            // name, approachStart, approachEnd, beamStart
            Triple("approach_then_beam", longArrayOf(1800L, RingCodeTimeline.NONE), 3400L),
            Triple("approach_cancelled", longArrayOf(1000L, 2200L), RingCodeTimeline.NONE),
            Triple("beam_only", longArrayOf(RingCodeTimeline.NONE, RingCodeTimeline.NONE), 800L),
        )
        val buffer = RingFrameBuffer()
        for ((name, approach, beam) in scenarios) for (reduced in listOf(false, true)) for (tm in times) {
            RingCodeTimeline.frameInto(tm, approach[0], approach[1], beam, reduced, buffer)
            val beamMs = buffer.beamMs
            var checksum = 0L
            for (ring in 0 until RingCodeLayout.RINGS) for (i in 0 until RingCodeLayout.RING_SLOTS[ring]) {
                checksum += RingCodeTimeline.slotProgress(ring, i, beamMs).toLong() * (RingCodeLayout.slotOf(ring, i) + 1)
            }
            sb.append(line("frame", {
                str("scenario", name); long("t", tm); long("approachStart", approach[0]); long("approachEnd", approach[1]); long("beamStart", beam)
                bool("reduced", reduced)
            }, {
                str("frame", buffer.describe())
                long("progressChecksum", checksum)
                arr("samples"); for ((r, i) in SAMPLE_SLOTS) intItem(RingCodeTimeline.slotProgress(r, i, beamMs)); endArr()
                int("marker", RingCodeTimeline.markerProgress(beamMs))
            }))
        }
        for (ring in 0 until RingCodeLayout.RINGS) for (index in intArrayOf(0, 1, 7, RingCodeLayout.RING_SLOTS[ring] - 1)) {
            sb.append(line("slotStart", { int("ring", ring); int("index", index) }, { int("ms", RingCodeTimeline.slotStartMs(ring, index)) }))
        }
        for (reduced in listOf(false, true)) sb.append(line("beamTotal", { bool("reduced", reduced) }, { long("ms", RingCodeTimeline.beamTotalMs(reduced)) }))
        for (tm in longArrayOf(0L, 1000L, 6000L, 6001L, 12_000L, 18_000L, 23_999L, 24_000L, 30_000L, 123_456L)) {
            sb.append(line("sway", { long("t", tm) }, { int("angle", RingCodeTimeline.swayAngle(tm)); int("breath", RingCodeTimeline.breathScale(tm)) }))
        }
        return sb.toString()
    }

    val README = """

        ## Pixl ring code (the ring-*.jsonl files; handoff/2026-11-21-ring-code.md)

        - ring-layout.jsonl: `constants` (the geometry in permille of the plate half-size, ring sizes, the orange
          start dot colour as a signed Int32), `slot` (slot id to ring, index, count, microTurns = index*1e6/count
          rounded down, byte, bit), `codewordToSlots` / `slotsToCodeword` (25 bytes hex to 200 characters of 0/1).
        - ring-rs.jsonl: `gfMul`, `gfInv` (GF(256), polynomial 0x11D), `rsEncode` (11 data bytes to the 25-byte word,
          data then 14 parity bytes, generator roots alpha^0..alpha^13), `rsDecode` (`received` hex, `erasures` positions;
          out `ok` and the corrected `codeword` and `corrected` (unknown-position errors repaired); includes words
          beyond capacity, where a port must give the same answer as the listed one: `ok:false`, or the same miscorrection).
        - ring-payload.jsonl: `pack` (id, bucket, random to the 11 data bytes, null when invalid), `encodeSlots`
          (the 200 slot bits), `unpack` (11 bytes to id/bucket/random or the reject reason), `bucketOf`, `freshness`.
        - ring-timeline.jsonl: `frame` (scenario, t, approachStart, approachEnd, beamStart in ms with
          -9223372036854775808 = NONE, reduced; out `frame` = RingFrameBuffer.describe(), `progressChecksum` =
          sum of slotProgress * (slot id + 1), `samples` = slotProgress of nine fixed slots, `marker`), `slotStart`,
          `beamTotal`, `sway` (idle angle and breath), `easeNoop`.
        The image side of the ring code (renderer, scanner, style) is floating point and has no goldens.
    """.trimIndent() + "\n"
}
