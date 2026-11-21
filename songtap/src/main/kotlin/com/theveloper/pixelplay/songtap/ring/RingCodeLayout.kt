package com.theveloper.pixelplay.songtap.ring

/**
 * Where everything sits on a Pixl ring code, in integers. Lengths are permille of the plate's half-size
 * (the plate is the square [-1000, 1000] on both axes, y down, so a dot at ring radius 800 is 80 percent of
 * the way from the centre to the plate's edge). Angles run clockwise from 12 o'clock.
 *
 * ```
 *   centre            art disc, radius 300 (the album art), a dark outline ring 300..325 around it
 *   ring 0..4         24, 32, 40, 48, 56 slots at radius 420, 515, 610, 705, 800 (pitch 95)
 *   slot i of N       angle i/N of a turn, i = 0 at 12 o'clock
 *   one dot per slot  BIG (radius 32) = bit 1, SMALL (radius 16) = bit 0: the dot's centre never moves,
 *                     so the scanner always knows where to look and only measures a size
 *   start dot         orange, radius 42, at 12 o'clock at radius 895: one ring pitch outside the outer ring
 * ```
 *
 * The 200 slots carry exactly one 25-byte Reed-Solomon codeword. The start dot is a 201st mark that carries
 * nothing: it only tells the scanner which way is up.
 *
 * Slot ids run ring by ring (ring 0 first), clockwise inside a ring: ring 0 is 0..23, ring 1 24..55,
 * ring 2 56..95, ring 3 96..143, ring 4 144..199.
 *
 * Byte map (deviates from RING-CODE-SPEC.md on purpose, see handoff/2026-11-21-ring-code.md): byte b owns the
 * eight consecutive slots 8b .. 8b+7, most significant bit first. Every ring size is a multiple of 8, so a
 * byte is always a run of eight neighbours on one ring (24/8 = 3, 32/8 = 4, 40/8 = 5, 48/8 = 6, 56/8 = 7:
 * 25 bytes). The spec's "byte j owns slots j, j+25, ..." scatters a byte's bits, which is the opposite of
 * what a byte-oriented code wants: a smudge on 40 neighbouring slots would hit all 25 bytes by one bit each
 * (more than the 14 an erasure decode can fix), while here it hits at most 7. [specByteOfSlot] keeps the
 * spec's map so a test can show the difference.
 */
object RingCodeLayout {
    const val RINGS = 5
    val RING_SLOTS = intArrayOf(24, 32, 40, 48, 56)
    val RING_FIRST_SLOT = intArrayOf(0, 24, 56, 96, 144)
    const val SLOTS = 200
    const val BYTES = 25
    const val BITS_PER_BYTE = 8

    // ── geometry, permille of the plate half-size ──────────────────────────────────
    const val ART_RADIUS = 300
    const val BEZEL_INNER = 300
    const val BEZEL_OUTER = 325
    val RING_RADIUS = intArrayOf(420, 515, 610, 705, 800)
    const val BIG_RADIUS = 32
    const val SMALL_RADIUS = 16
    const val MARKER_RADIUS_AT = 895
    const val MARKER_RADIUS = 42
    const val PLATE_CORNER = 180

    /** The one fixed colour of the code, on every device (ARGB). Dark enough to read in luma, orange in hue. */
    const val MARKER_ARGB: Int = 0xFFD9480F.toInt()

    fun ringOf(slot: Int): Int {
        require(slot in 0 until SLOTS) { "slot $slot" }
        var r = RINGS - 1
        while (RING_FIRST_SLOT[r] > slot) r--
        return r
    }

    fun indexInRing(slot: Int): Int = slot - RING_FIRST_SLOT[ringOf(slot)]

    fun slotOf(ring: Int, index: Int): Int {
        require(ring in 0 until RINGS && index in 0 until RING_SLOTS[ring]) { "ring $ring index $index" }
        return RING_FIRST_SLOT[ring] + index
    }

    /** The slot's angle as millionths of a turn from 12 o'clock, clockwise: index * 1,000,000 / count (rounded down). */
    fun angleMicroTurns(slot: Int): Int {
        val ring = ringOf(slot)
        return (indexInRing(slot).toLong() * 1_000_000L / RING_SLOTS[ring]).toInt()
    }

    fun byteOfSlot(slot: Int): Int = slot / BITS_PER_BYTE

    /** The bit (7 = most significant) a slot carries inside its byte. */
    fun bitOfSlot(slot: Int): Int = BITS_PER_BYTE - 1 - slot % BITS_PER_BYTE

    /** The spec's interleave (byte j owns j, j+25, ...), kept for comparison only. */
    fun specByteOfSlot(slot: Int): Int = slot % BYTES

    /** 25 codeword bytes to 200 slot bits (0 or 1 per element, index = slot id). */
    fun codewordToSlots(codeword: ByteArray): ByteArray {
        require(codeword.size == BYTES) { "a codeword has $BYTES bytes" }
        val bits = ByteArray(SLOTS)
        for (slot in 0 until SLOTS) {
            bits[slot] = ((codeword[byteOfSlot(slot)].toInt() ushr bitOfSlot(slot)) and 1).toByte()
        }
        return bits
    }

    /** 200 slot bits (any non-zero value counts as 1) back to 25 bytes. */
    fun slotsToCodeword(bits: ByteArray): ByteArray {
        require(bits.size == SLOTS) { "a code has $SLOTS slots" }
        val out = ByteArray(BYTES)
        for (slot in 0 until SLOTS) {
            if (bits[slot].toInt() != 0) {
                val b = byteOfSlot(slot)
                out[b] = (out[b].toInt() or (1 shl bitOfSlot(slot))).toByte()
            }
        }
        return out
    }

    /** How many different bytes a run of [count] consecutive slots starting at [first] (wrapping inside its ring) touches. */
    fun bytesTouched(first: Int, count: Int, byteOf: (Int) -> Int): Int {
        val ring = ringOf(first)
        val n = RING_SLOTS[ring]
        val start = indexInRing(first)
        val seen = BooleanArray(BYTES)
        for (k in 0 until count) seen[byteOf(slotOf(ring, (start + k) % n))] = true
        var c = 0
        for (b in 0 until BYTES) if (seen[b]) c++
        return c
    }
}
