package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.ring.ReedSolomon
import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import com.theveloper.pixelplay.songtap.ring.RingCodePayload
import com.theveloper.pixelplay.songtap.ring.RingCodePayloads
import com.theveloper.pixelplay.songtap.ring.RingUnpack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One camera frame as the scanner takes it: the luma (Y) plane, row-major, [width] x [height], and
 * optionally a coarse "orangeness" map ([hue], [hueWidth] x [hueHeight], 0 = not orange, 255 = the start
 * dot's colour) that the app builds from the U and V planes with [RingHue.orangeness]. The hue map is only a
 * tie-breaker: the scanner finds the start dot by where it is (the one dot outside the outer ring), so a
 * device colour that is itself orange cannot hide it. A frame without a hue map is fine.
 */
class RingFrame(
    val width: Int,
    val height: Int,
    val luma: ByteArray,
    val hue: ByteArray? = null,
    val hueWidth: Int = width,
    val hueHeight: Int = height,
)

object RingHue {
    /**
     * How orange a pixel is, 0..255, from its chroma values (U = Cb, V = Cr, 0..255, 128 = neutral). The start
     * dot (0xD9480F) has U about 75 and V about 205: strong V, weak U.
     */
    fun orangeness(u: Int, v: Int): Int {
        val du = 128 - u
        val dv = v - 128
        if (du <= 10 || dv <= 25) return 0
        val ratio = dv * 100 / du
        if (ratio < 90 || ratio > 280) return 0
        return min(255, (min(du, dv) - 10) * 5)
    }
}

enum class RingScanFailure(val code: String) {
    /** No round outline found. */
    NO_PLATE("no_plate"),

    /** An outline, but no start dot where one belongs. */
    NO_MARKER("no_marker"),

    /** Too few dots matched the layout to trust the geometry. */
    TOO_FEW_DOTS("too_few_dots"),

    /** The dots were read but the code could not be repaired. */
    UNREADABLE("unreadable"),

    /** Repaired, but the checksum says it is not a code of ours. */
    BAD_CHECKSUM("bad_checksum"),
}

/** What the last scan saw, for the Developer mode screen. Owned by the scanner and overwritten by the next scan. */
class RingScanDiagnostics {
    @JvmField var scale = 1
    @JvmField var found = false
    @JvmField var centreX = 0.0
    @JvmField var centreY = 0.0
    @JvmField var bezelRadiusPx = 0.0
    @JvmField var markerFound = false
    @JvmField var markerX = 0.0
    @JvmField var markerY = 0.0
    @JvmField var matched = 0
    @JvmField var residualPx = 0.0
    @JvmField val homography = DoubleArray(9)
    @JvmField var rsCorrected = 0
    @JvmField var erasuresUsed = 0
    @JvmField var candidates = 0
    @JvmField var scanMicros = 0L

    /** Per slot: 0..100, how clearly the dot read as big or small. */
    @JvmField val slotConfidence = IntArray(RingCodeLayout.SLOTS)

    /** Per slot: the measured dot mass in squared permille (a small dot is about 800, a big one about 3200). */
    @JvmField val slotMass = DoubleArray(RingCodeLayout.SLOTS)
    @JvmField val slotBits = ByteArray(RingCodeLayout.SLOTS)
}

sealed class RingScanResult {
    class Decoded(val payload: RingCodePayload, val corrected: Int, val erasures: Int) : RingScanResult()
    class Failed(val reason: RingScanFailure) : RingScanResult()
}

/**
 * Reads a Pixl ring code from a camera frame. Pure Kotlin over a luma array; one instance owns its scratch
 * memory and must be used from one thread (the app's analysis thread). The app skips frames so a scan never
 * starves the UI.
 *
 * How it works (RING-CODE-SPEC.md, "Scanning"):
 * 1. Downscale to at most `maxSide` pixels on the long side (box average) and threshold each pixel against the
 *    mean of its neighbourhood (an integral image), so a gradient or a glare edge does not matter.
 * 2. Label the dark blobs. The plate's outline ring around the artwork is a large round blob: its outer
 *    boundary is an ellipse whatever the artwork holds (every row's leftmost and rightmost pixel give the
 *    filled shape; its second moments give the centre and the two axes). That undoes scale and tilt up to
 *    the rotation, which the start dot then fixes: it is the one dot-sized blob beyond the outer ring.
 * 3. Match every expected slot position to the nearest dot blob and fit a homography in four steps, adding
 *    one ring at a time, so perspective is learnt from the inner rings first and extrapolated outward.
 * 4. Measure the dark mass in a small window around each of the 200 slots (normalised by the plate's own
 *    light and dark levels), compare each to its ring's two clusters (two-means, seeded with the expected
 *    sizes), and call it big (1) or small (0). Confidence is the distance from the decision line.
 * 5. Reed-Solomon decode, first as it is, then with the least certain bytes flagged as erasures (at most
 *    10), and check the payload's checksum.
 */
class RingScanner(private val maxSide: Int = 800) {

    val diagnostics = RingScanDiagnostics()

    /** Test hook: where the scanner says what it decided. Null in the app. */
    var trace: ((String) -> Unit)? = null

    // working buffers (grown on demand)
    private var gw = 0
    private var gh = 0
    private var gray = IntArray(0)
    private var integral = IntArray(0)
    private var mask = ByteArray(0)
    private var labels = IntArray(0)
    private var parent = IntArray(1024)

    // blob stats, per compact component id
    private var compCount = 0
    private var cArea = IntArray(256)
    private var cMinX = IntArray(256)
    private var cMaxX = IntArray(256)
    private var cMinY = IntArray(256)
    private var cMaxY = IntArray(256)
    private var cSumX = DoubleArray(256)
    private var cSumY = DoubleArray(256)

    private val hist = IntArray(256)
    private val slotCx = DoubleArray(RingCodeLayout.SLOTS)
    private val slotCy = DoubleArray(RingCodeLayout.SLOTS)
    private val slotMass = DoubleArray(RingCodeLayout.SLOTS)
    private val slotBit = ByteArray(RingCodeLayout.SLOTS)
    private val slotConf = IntArray(RingCodeLayout.SLOTS)

    // plate-plane coordinates of the slots (and, last, the start dot), permille
    private val qx = DoubleArray(RingCodeLayout.SLOTS + 1)
    private val qy = DoubleArray(RingCodeLayout.SLOTS + 1)

    init {
        for (slot in 0 until RingCodeLayout.SLOTS) {
            val ring = RingCodeLayout.ringOf(slot)
            val exact = RingCodeLayout.indexInRing(slot).toDouble() / RingCodeLayout.RING_SLOTS[ring] * 2.0 * PI
            qx[slot] = RingCodeLayout.RING_RADIUS[ring] * sin(exact)
            qy[slot] = -RingCodeLayout.RING_RADIUS[ring] * cos(exact)
        }
        qx[RingCodeLayout.SLOTS] = 0.0
        qy[RingCodeLayout.SLOTS] = -RingCodeLayout.MARKER_RADIUS_AT.toDouble()
    }

    // ── public ───────────────────────────────────────────────────────────────────────

    /** Scans one frame. [clockNanos] is optional and only used for [RingScanDiagnostics.scanMicros]. */
    fun scan(frame: RingFrame, clockNanos: (() -> Long)? = null): RingScanResult {
        val t0 = clockNanos?.invoke() ?: 0L
        val result = scanInner(frame)
        if (clockNanos != null) diagnostics.scanMicros = (clockNanos() - t0) / 1000L
        return result
    }

    private fun scanInner(frame: RingFrame): RingScanResult {
        val d = diagnostics
        d.found = false; d.markerFound = false; d.matched = 0; d.residualPx = 0.0; d.rsCorrected = 0; d.erasuresUsed = 0; d.candidates = 0
        if (frame.width < 64 || frame.height < 64 || frame.luma.size < frame.width * frame.height) {
            return RingScanResult.Failed(RingScanFailure.NO_PLATE)
        }
        prepare(frame)
        threshold()
        label()
        val bezels = findBezels()
        if (bezels.isEmpty()) return RingScanResult.Failed(RingScanFailure.NO_PLATE)
        // Best outline first. A second reading of the same outline (the arc fit beside the moments fit) is the
        // way out when a smudge or a glare patch hid part of it and the first ellipse came out a little skewed.
        var firstFailure: RingScanResult? = null
        for (bezel in bezels) {
            val r = readCode(frame, bezel)
            if (r is RingScanResult.Decoded) return r
            if (firstFailure == null) firstFailure = r
        }
        return firstFailure!!
    }

    // ── 1. working image ─────────────────────────────────────────────────────────────

    private fun prepare(frame: RingFrame) {
        val f = max(1, (max(frame.width, frame.height) + maxSide - 1) / maxSide)
        diagnostics.scale = f
        gw = frame.width / f
        gh = frame.height / f
        if (gray.size < gw * gh) {
            gray = IntArray(gw * gh)
            mask = ByteArray(gw * gh)
            labels = IntArray(gw * gh)
        }
        if (integral.size < (gw + 1) * (gh + 1)) integral = IntArray((gw + 1) * (gh + 1))
        if (f == 1) {
            for (i in 0 until gw * gh) gray[i] = frame.luma[i].toInt() and 0xFF
        } else {
            val area = f * f
            for (y in 0 until gh) for (x in 0 until gw) {
                var acc = 0
                val base = y * f * frame.width + x * f
                for (dy in 0 until f) {
                    val row = base + dy * frame.width
                    for (dx in 0 until f) acc += frame.luma[row + dx].toInt() and 0xFF
                }
                gray[y * gw + x] = acc / area
            }
        }
        val stride = gw + 1
        for (x in 0..gw) integral[x] = 0
        for (y in 0 until gh) {
            var rowSum = 0
            integral[(y + 1) * stride] = 0
            for (x in 0 until gw) {
                rowSum += gray[y * gw + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
            }
        }
    }

    private fun threshold() {
        val half = max(10, min(gw, gh) / 20)
        val stride = gw + 1
        for (y in 0 until gh) {
            val y0 = max(0, y - half)
            val y1 = min(gh, y + half + 1)
            for (x in 0 until gw) {
                val x0 = max(0, x - half)
                val x1 = min(gw, x + half + 1)
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] - integral[y1 * stride + x0] + integral[y0 * stride + x0]
                val n = (x1 - x0) * (y1 - y0)
                // dark when under 80 percent of the neighbourhood's mean
                mask[y * gw + x] = if (gray[y * gw + x].toLong() * n * 100 < sum.toLong() * 80) 1 else 0
            }
        }
    }

    // ── 2. blobs ─────────────────────────────────────────────────────────────────────

    private fun find(a: Int): Int {
        var x = a
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }

    private fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) {
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }
    }

    private fun label() {
        var next = 1
        parent[0] = 0
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                if (mask[i].toInt() == 0) {
                    labels[i] = 0
                    continue
                }
                val left = if (x > 0) labels[i - 1] else 0
                val up = if (y > 0) labels[i - gw] else 0
                if (left == 0 && up == 0) {
                    if (next >= parent.size) parent = parent.copyOf(parent.size * 2)
                    parent[next] = next
                    labels[i] = next++
                } else if (left != 0 && up != 0) {
                    labels[i] = min(left, up)
                    if (left != up) union(left, up)
                } else {
                    labels[i] = if (left != 0) left else up
                }
            }
        }
        val compact = IntArray(next) { -1 }
        compCount = 0
        for (l in 1 until next) {
            val r = find(l)
            if (compact[r] < 0) {
                compact[r] = compCount++
                if (compCount > cArea.size) growStats()
                val c = compact[r]
                cArea[c] = 0; cMinX[c] = Int.MAX_VALUE; cMaxX[c] = -1; cMinY[c] = Int.MAX_VALUE; cMaxY[c] = -1
                cSumX[c] = 0.0; cSumY[c] = 0.0
            }
        }
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x
            val l = labels[i]
            if (l == 0) continue
            val c = compact[find(l)]
            labels[i] = c + 1 // 1-based component id from here on
            cArea[c]++
            if (x < cMinX[c]) cMinX[c] = x
            if (x > cMaxX[c]) cMaxX[c] = x
            if (y < cMinY[c]) cMinY[c] = y
            if (y > cMaxY[c]) cMaxY[c] = y
            cSumX[c] += x + 0.5
            cSumY[c] += y + 0.5
        }
    }

    private fun growStats() {
        val n = cArea.size * 2
        cArea = cArea.copyOf(n); cMinX = cMinX.copyOf(n); cMaxX = cMaxX.copyOf(n); cMinY = cMinY.copyOf(n); cMaxY = cMaxY.copyOf(n)
        cSumX = cSumX.copyOf(n); cSumY = cSumY.copyOf(n)
    }

    // ── the outline and the affine map ───────────────────────────────────────────────

    /** The outline's ellipse: its centre and the 2x2 matrix that takes the unit disc to the outline's outer edge (working pixels). */
    private class Bezel(val cx: Double, val cy: Double, val a11: Double, val a12: Double, val a21: Double, val a22: Double, val comp: Int)

    private fun spanMoments(c: Int): Bezel? {
        var area = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        val id = c + 1
        for (y in cMinY[c]..cMaxY[c]) {
            var lo = -1
            var hi = -1
            val row = y * gw
            for (x in cMinX[c]..cMaxX[c]) if (labels[row + x] == id) { lo = x; break }
            if (lo < 0) continue
            for (x in cMaxX[c] downTo lo) if (labels[row + x] == id) { hi = x; break }
            val len = (hi - lo + 1).toDouble()
            val x0 = lo.toDouble()
            val x1 = hi + 1.0 // the span covers [x0, x1)
            val yc = y + 0.5
            area += len
            sx += len * (x0 + x1) / 2.0
            sy += len * yc
            sxx += (x1 * x1 * x1 - x0 * x0 * x0) / 3.0
            syy += len * (yc * yc + 1.0 / 12.0)
            sxy += yc * len * (x0 + x1) / 2.0
        }
        if (area < 200.0) return null
        val mx = sx / area
        val my = sy / area
        val cxx = sxx / area - mx * mx
        val cyy = syy / area - my * my
        val cxy = sxy / area - mx * my
        val det = cxx * cyy - cxy * cxy
        if (det <= 1.0) return null
        val ratio = area / (4.0 * PI * sqrt(det))
        if (ratio < 0.88 || ratio > 1.12) return null
        // symmetric square root of the covariance: (C + s I) / t with s = sqrt(det), t = sqrt(trace + 2 s)
        val s = sqrt(det)
        val t = sqrt(cxx + cyy + 2.0 * s)
        val r11 = (cxx + s) / t
        val r12 = cxy / t
        val r22 = (cyy + s) / t
        return Bezel(mx, my, 2.0 * r11, 2.0 * r12, 2.0 * r12, 2.0 * r22, c)
    }

    /**
     * The outline when part of it is hidden (a smudge, a glare patch): the blob is an open arc, so its filled
     * shape is no ellipse. Fit a conic to the arc's own pixels (a x^2 + b xy + c y^2 + d x + e y = 1, in
     * coordinates centred on the blob and scaled to its size) and take the ellipse of the arc's centre line,
     * 312.5 permille out, scaled to the outer edge at 325. Needs the arc to cover a good part of the turn.
     */
    private fun arcFit(c: Int): Bezel? {
        val bw = cMaxX[c] - cMinX[c] + 1
        val bh = cMaxY[c] - cMinY[c] + 1
        val fill = cArea[c].toDouble() / (bw * bh)
        if (fill > 0.30 || cArea[c] < 60) return null
        val id = c + 1
        val mx0 = cSumX[c] / cArea[c]
        val my0 = cSumY[c] / cArea[c]
        val scale = max(bw, bh).toDouble()
        val step = max(1, cArea[c] / 1500)
        val ata = Array(5) { DoubleArray(5) }
        val atb = DoubleArray(5)
        val row = DoubleArray(5)
        var seen = 0
        var n = 0
        for (y in cMinY[c]..cMaxY[c]) for (x in cMinX[c]..cMaxX[c]) {
            if (labels[y * gw + x] != id) continue
            if (seen++ % step != 0) continue
            val u = (x + 0.5 - mx0) / scale
            val v = (y + 0.5 - my0) / scale
            row[0] = u * u; row[1] = u * v; row[2] = v * v; row[3] = u; row[4] = v
            for (i in 0 until 5) {
                for (j in 0 until 5) ata[i][j] += row[i] * row[j]
                atb[i] += row[i]
            }
            n++
        }
        if (n < 40) return null
        // a short arc cannot tell an ellipse from a circle: pull the fit toward a circle (a = c, b = 0)
        val prior = n * 0.35
        val pa = doubleArrayOf(1.0, 0.0, -1.0, 0.0, 0.0)
        for (i in 0 until 5) for (j in 0 until 5) ata[i][j] += prior * pa[i] * pa[j]
        ata[1][1] += prior
        val sol = gauss(ata, atb, 5) ?: return null
        val qa = sol[0]
        val qb = sol[1] / 2.0
        val qc = sol[2]
        val det = qa * qc - qb * qb
        if (det <= 1e-12 || qa <= 0.0) return null
        // centre: M c0 = -(d, e) / 2
        val c0u = -(qc * sol[3] / 2.0 - qb * sol[4] / 2.0) / det
        val c0v = -(-qb * sol[3] / 2.0 + qa * sol[4] / 2.0) / det
        val k = 1.0 + (qa * c0u * c0u + 2.0 * qb * c0u * c0v + qc * c0v * c0v)
        if (k <= 1e-9) return null
        // shape matrix S = M / k; unit circle -> ellipse is S^(-1/2)
        val s11 = qa / k
        val s12 = qb / k
        val s22 = qc / k
        val sdet = s11 * s22 - s12 * s12
        if (sdet <= 1e-12) return null
        // S^(-1/2) = (S^(-1))^(1/2); S^(-1) = [[s22, -s12], [-s12, s11]] / sdet
        val i11 = s22 / sdet
        val i12 = -s12 / sdet
        val i22 = s11 / sdet
        val idet = i11 * i22 - i12 * i12
        val rs = sqrt(idet)
        val rt = sqrt(i11 + i22 + 2.0 * rs)
        val a11 = (i11 + rs) / rt
        val a12 = i12 / rt
        val a22 = (i22 + rs) / rt
        val grow = RingCodeLayout.BEZEL_OUTER / ((RingCodeLayout.BEZEL_INNER + RingCodeLayout.BEZEL_OUTER) / 2.0)
        val m11 = a11 * scale * grow
        val m12 = a12 * scale * grow
        val m22 = a22 * scale * grow
        val centreX = mx0 + c0u * scale
        val centreY = my0 + c0v * scale
        // sanity: ellipse not wild, and the arc's pixels sit on it
        val ev = sqrt(abs(m11 * m22 - m12 * m12))
        if (ev < 14.0 || ev > min(gw, gh) * 0.5) return null
        val tr = m11 * m11 + 2 * m12 * m12 + m22 * m22
        val disc = sqrt(max(0.0, tr * tr - 4.0 * ev * ev * ev * ev))
        val l1 = (tr + disc) / 2.0
        val l2 = (tr - disc) / 2.0
        if (l2 <= 1e-9 || sqrt(l1 / l2) > 1.8) return null
        // residual along the arc, and angular coverage
        val det2 = m11 * m22 - m12 * m12
        val inv11 = m22 / det2
        val inv12 = -m12 / det2
        val inv21 = -m12 / det2
        val inv22 = m11 / det2
        var err = 0.0
        val bins = BooleanArray(12)
        var m = 0
        seen = 0
        for (y in cMinY[c]..cMaxY[c]) for (x in cMinX[c]..cMaxX[c]) {
            if (labels[y * gw + x] != id) continue
            if (seen++ % step != 0) continue
            val px = x + 0.5 - centreX
            val py = y + 0.5 - centreY
            val ux = inv11 * px + inv12 * py
            val uy = inv21 * px + inv22 * py
            val r = sqrt(ux * ux + uy * uy) * RingCodeLayout.BEZEL_OUTER
            err += abs(r - (RingCodeLayout.BEZEL_INNER + RingCodeLayout.BEZEL_OUTER) / 2.0)
            var a = atan2(uy, ux)
            if (a < 0) a += 2.0 * PI
            bins[min(11, (a / (2.0 * PI) * 12.0).toInt())] = true
            m++
        }
        if (m == 0) return null
        if (err / m > 22.0) return null // mean distance from the centre line over 22 permille: not our ring
        if (bins.count { it } < 5) return null
        return Bezel(centreX, centreY, m11, m12, m12, m22, c)
    }

    /** The share of the blob's pixels that lie in the outline's band (305..330 permille, 35 either side) under this ellipse. */
    private fun ringFitness(b: Bezel, c: Int): Double {
        val inv = invAffine(b)
        val id = c + 1
        val step = max(1, cArea[c] / 1500)
        var seen = 0
        var inBand = 0
        var n = 0
        for (y in cMinY[c]..cMaxY[c]) for (x in cMinX[c]..cMaxX[c]) {
            if (labels[y * gw + x] != id) continue
            if (seen++ % step != 0) continue
            val px = x + 0.5 - b.cx
            val py = y + 0.5 - b.cy
            val r = sqrt((inv[0] * px + inv[1] * py).let { it * it } + (inv[2] * px + inv[3] * py).let { it * it }) * RingCodeLayout.BEZEL_OUTER
            n++
            if (r >= RingCodeLayout.BEZEL_INNER - 35.0 && r <= RingCodeLayout.BEZEL_OUTER + 35.0) inBand++
        }
        return if (n == 0) 0.0 else inBand.toDouble() / n
    }

    /** The outlines worth trying, best first (at most [MAX_OUTLINES]): per large round blob the moments fit and the arc fit, each kept when it explains the blob and has dot-sized blobs where the rings would be. */
    private fun findBezels(): List<Bezel> {
        val limit = min(gw, gh)
        val ids = ArrayList<Int>()
        for (c in 0 until compCount) {
            val bw = cMaxX[c] - cMinX[c] + 1
            val bh = cMaxY[c] - cMinY[c] + 1
            if (min(bw, bh) < 36 || max(bw, bh) > limit * 98 / 100) continue
            if (max(bw, bh).toDouble() / min(bw, bh) > 2.1) continue
            val fill = cArea[c].toDouble() / (bw * bh)
            if (fill < 0.05 || fill > 0.86) continue
            ids += c
        }
        ids.sortByDescending { cArea[it] }
        if (trace != null) for (c in ids.take(6)) trace?.invoke("blob $c box=${cMaxX[c] - cMinX[c] + 1}x${cMaxY[c] - cMinY[c] + 1} area=${cArea[c]}")
        val found = ArrayList<Bezel>()
        val scores = ArrayList<Double>()
        for (c in ids.take(6)) {
            val viaMoments = spanMoments(c)?.takeIf { ringFitness(it, c) >= 0.9 }
            val viaArc = arcFit(c)?.takeIf { ringFitness(it, c) >= 0.8 }
            for (bezel in listOfNotNull(viaMoments, viaArc)) {
                val score = scoreCandidate(bezel)
                trace?.invoke("outline ${if (bezel === viaMoments) "moments" else "arc"} centre=(${"%.1f".format(bezel.cx)},${"%.1f".format(bezel.cy)}) A=[${"%.1f".format(bezel.a11)},${"%.1f".format(bezel.a12)};${"%.1f".format(bezel.a21)},${"%.1f".format(bezel.a22)}] score=$score")
                diagnostics.candidates++
                if (score >= 20.0) {
                    found += bezel
                    scores += score
                }
            }
        }
        val order = found.indices.sortedByDescending { scores[it] }
        return order.take(MAX_OUTLINES).map { found[it] }
    }

    private fun invAffine(b: Bezel): DoubleArray {
        val det = b.a11 * b.a22 - b.a12 * b.a21
        return doubleArrayOf(b.a22 / det, -b.a12 / det, -b.a21 / det, b.a11 / det)
    }

    /** How many dot-sized blobs sit where the rings are, if this outline is the plate's. */
    private fun scoreCandidate(b: Bezel): Double {
        val inv = invAffine(b)
        val sc = sqrt(abs(b.a11 * b.a22 - b.a12 * b.a21)) / RingCodeLayout.BEZEL_OUTER
        val smallA = PI * RingCodeLayout.SMALL_RADIUS * RingCodeLayout.SMALL_RADIUS * sc * sc
        val bigA = PI * RingCodeLayout.BIG_RADIUS * RingCodeLayout.BIG_RADIUS * sc * sc
        var inRings = 0
        for (c in 0 until compCount) {
            if (c == b.comp) continue
            val a = cArea[c]
            if (a < smallA * 0.3 || a > bigA * 2.6) continue
            val px = cSumX[c] / a - b.cx
            val py = cSumY[c] / a - b.cy
            val ux = inv[0] * px + inv[1] * py
            val uy = inv[2] * px + inv[3] * py
            val rho = sqrt(ux * ux + uy * uy) * RingCodeLayout.BEZEL_OUTER
            if (rho in 380.0..850.0) inRings++
        }
        return inRings.toDouble()
    }

    // ── 3. the code ──────────────────────────────────────────────────────────────────

    private fun readCode(frame: RingFrame, bezel: Bezel): RingScanResult {
        val d = diagnostics
        val f = d.scale
        val sc = sqrt(abs(bezel.a11 * bezel.a22 - bezel.a12 * bezel.a21)) / RingCodeLayout.BEZEL_OUTER
        d.found = true
        d.centreX = bezel.cx * f
        d.centreY = bezel.cy * f
        d.bezelRadiusPx = sc * RingCodeLayout.BEZEL_OUTER * f
        val inv = invAffine(bezel)

        val dotIds = IntArray(compCount)
        var nd = 0
        val smallA = PI * RingCodeLayout.SMALL_RADIUS * RingCodeLayout.SMALL_RADIUS * sc * sc
        val bigA = PI * RingCodeLayout.BIG_RADIUS * RingCodeLayout.BIG_RADIUS * sc * sc
        for (c in 0 until compCount) {
            if (c == bezel.comp) continue
            val a = cArea[c]
            if (a >= smallA * 0.3 && a <= bigA * 2.8) dotIds[nd++] = c
        }
        if (nd < 40) return RingScanResult.Failed(RingScanFailure.TOO_FEW_DOTS)

        val marker = findMarker(bezel, inv, sc, frame)
        var markerFailure: RingScanResult? = null
        if (marker != null) {
            d.markerFound = true
            d.markerX = cSumX[marker] / cArea[marker] * f
            d.markerY = cSumY[marker] / cArea[marker] * f
            // the start dot's direction in unit-disc coordinates gives the rotation
            val mx = cSumX[marker] / cArea[marker] - bezel.cx
            val my = cSumY[marker] / cArea[marker] - bezel.cy
            val psi = atan2(inv[0] * mx + inv[1] * my, -(inv[2] * mx + inv[3] * my))
            val first = fitAndDecode(bezel, sc, psi, marker, dotIds, nd)
            if (first is RingScanResult.Decoded) return first
            // A blob that is not the start dot can pass for one (the real one smudged, a stray dot beyond the rings).
            // The positions of the dots fit the plate in eight turns, so the blind search below still finds the code.
            markerFailure = first
        }
        // The start dot is hidden. The dots alone fit the plate in eight turns (every ring size divides by 8),
        // so find the 45 degree class that matches best and try each of its eight turns until one decodes.
        var bestPsi = 0.0
        var bestCost = Double.MAX_VALUE
        val coarse = 30
        for (i in 0 until coarse) {
            val psi = i * (PI / 4.0) / coarse
            val cost = stage0Cost(affineFor(bezel, psi), sc, dotIds, nd)
            if (cost < bestCost) {
                bestCost = cost
                bestPsi = psi
            }
        }
        val centre = bestPsi
        for (i in -10..10) {
            val psi = centre + i * (PI / 4.0 / coarse) / 10.0
            val cost = stage0Cost(affineFor(bezel, psi), sc, dotIds, nd)
            if (cost < bestCost) {
                bestCost = cost
                bestPsi = psi
            }
        }
        val bestHits = (60.0 * (1.0 - bestCost)).toInt()
        if (bestCost > 0.55) return markerFailure ?: RingScanResult.Failed(RingScanFailure.NO_MARKER)
        var last: RingScanResult = markerFailure ?: RingScanResult.Failed(RingScanFailure.NO_MARKER)
        for (k in 0 until 8) {
            val r = fitAndDecode(bezel, sc, bestPsi + k * PI / 4.0, -1, dotIds, nd)
            trace?.invoke("blind turn $k psi=${"%.1f".format((bestPsi + k * PI / 4.0) * 180 / PI)} hits=$bestHits matched=${diagnostics.matched} resid=${"%.2f".format(diagnostics.residualPx)} -> ${if (r is RingScanResult.Failed) r.reason.code else "decoded"}")
            if (r is RingScanResult.Decoded) return r
            if (r is RingScanResult.Failed && r.reason == RingScanFailure.UNREADABLE || r is RingScanResult.Failed && r.reason == RingScanFailure.BAD_CHECKSUM) last = r
        }
        return if (last is RingScanResult.Failed && last.reason != RingScanFailure.NO_MARKER) last else RingScanResult.Failed(RingScanFailure.NO_MARKER)
    }

    private fun affineFor(bezel: Bezel, psi: Double): DoubleArray {
        val cp = cos(psi)
        val sp = sin(psi)
        // plate permille -> working pixels: p = c + A * R(psi) * q / 325
        val k = 1.0 / RingCodeLayout.BEZEL_OUTER
        return doubleArrayOf(
            (bezel.a11 * cp + bezel.a12 * sp) * k, (-bezel.a11 * sp + bezel.a12 * cp) * k, bezel.cx,
            (bezel.a21 * cp + bezel.a22 * sp) * k, (-bezel.a21 * sp + bezel.a22 * cp) * k, bezel.cy,
            0.0, 0.0, 1.0,
        )
    }

    /** The mean distance (as a share of the matching tolerance, at most 1) from the slots of rings 0 and 1 to the nearest dot blob under the affine map [h]. */
    private fun stage0Cost(h: DoubleArray, sc: Double, dotIds: IntArray, nd: Int): Double {
        val tol = 0.46 * 90.0 * sc
        var sum = 0.0
        val count = RingCodeLayout.RING_FIRST_SLOT[2]
        for (slot in 0 until count) {
            val px = project(h, qx[slot], qy[slot], 0)
            val py = project(h, qx[slot], qy[slot], 1)
            var best = tol * tol
            for (i in 0 until nd) {
                val c = dotIds[i]
                val dx = cSumX[c] / cArea[c] - px
                val dy = cSumY[c] / cArea[c] - py
                val dd = dx * dx + dy * dy
                if (dd < best) best = dd
            }
            sum += sqrt(best) / tol
        }
        return sum / count
    }

    /** Matches, fits (ring by ring) and reads the code for one assumed rotation; [marker] is the start dot's blob, or -1 when it is hidden. */
    private fun fitAndDecode(bezel: Bezel, sc: Double, psi: Double, marker: Int, dotIds: IntArray, nd: Int): RingScanResult {
        val d = diagnostics
        val f = d.scale
        var h = affineFor(bezel, psi)
        val matchSlot = IntArray(RingCodeLayout.SLOTS + 1)
        val matchBlob = IntArray(RingCodeLayout.SLOTS + 1)
        val used = IntArray(compCount)
        var matched = 0
        // Five steps: rings 0..1 twice (affine; the first with a wide net, in case the outline was found a little off),
        // then 0..2, 0..3, and finally everything plus the start dot (homography).
        for (stage in 0 until 5) {
            val maxRing = STAGE_RINGS[stage]
            val tolPx = STAGE_TOLERANCE[stage] * 90.0 * sc
            matched = 0
            used.fill(-1)
            for (slot in 0..RingCodeLayout.SLOTS) {
                if (slot < RingCodeLayout.SLOTS && RingCodeLayout.ringOf(slot) > maxRing) continue
                if (slot == RingCodeLayout.SLOTS && (stage < 4 || marker < 0)) continue
                val px = project(h, qx[slot], qy[slot], 0)
                val py = project(h, qx[slot], qy[slot], 1)
                var bestBlob = -1
                var bestD = tolPx
                for (i in 0 until nd) {
                    val c = dotIds[i]
                    val dx = cSumX[c] / cArea[c] - px
                    val dy = cSumY[c] / cArea[c] - py
                    val dd = sqrt(dx * dx + dy * dy)
                    if (dd < bestD) {
                        bestD = dd
                        bestBlob = c
                    }
                }
                if (slot == RingCodeLayout.SLOTS && marker >= 0 && bestBlob != marker) {
                    // the start dot is not in the dot list when it is bigger than a big dot: use its own blob
                    val dx = cSumX[marker] / cArea[marker] - px
                    val dy = cSumY[marker] / cArea[marker] - py
                    if (sqrt(dx * dx + dy * dy) < tolPx) bestBlob = marker
                }
                if (bestBlob < 0) continue
                val prev = used[bestBlob]
                if (prev >= 0) {
                    // two slots want one blob (merged dots): keep the nearer one
                    val dx = cSumX[bestBlob] / cArea[bestBlob] - project(h, qx[prev], qy[prev], 0)
                    val dy = cSumY[bestBlob] / cArea[bestBlob] - project(h, qx[prev], qy[prev], 1)
                    if (sqrt(dx * dx + dy * dy) <= bestD) continue
                    for (m in 0 until matched) {
                        if (matchSlot[m] == prev) {
                            matchSlot[m] = matchSlot[matched - 1]
                            matchBlob[m] = matchBlob[matched - 1]
                            matched--
                            break
                        }
                    }
                }
                used[bestBlob] = slot
                matchSlot[matched] = slot
                matchBlob[matched] = bestBlob
                matched++
            }
            if (matched < 12) return RingScanResult.Failed(RingScanFailure.TOO_FEW_DOTS)
            h = fitHomography(matchSlot, matchBlob, matched, if (stage < 2) 6 else 8) ?: return RingScanResult.Failed(RingScanFailure.TOO_FEW_DOTS)
        }
        d.matched = matched
        if (matched < 60) return RingScanResult.Failed(RingScanFailure.TOO_FEW_DOTS)
        var resid = 0.0
        for (m in 0 until matched) {
            val s = matchSlot[m]
            val dx = cSumX[matchBlob[m]] / cArea[matchBlob[m]] - project(h, qx[s], qy[s], 0)
            val dy = cSumY[matchBlob[m]] / cArea[matchBlob[m]] - project(h, qx[s], qy[s], 1)
            resid += sqrt(dx * dx + dy * dy)
        }
        d.residualPx = resid / matched * f
        // diagnostics report frame pixels: scale the image-space rows by f
        for (i in 0 until 9) d.homography[i] = h[i]
        for (i in 0 until 6) d.homography[i] = h[i] * f

        measure(h)
        return decode()
    }

    private fun project(h: DoubleArray, x: Double, y: Double, axis: Int): Double {
        val w = h[6] * x + h[7] * y + h[8]
        return if (axis == 0) (h[0] * x + h[1] * y + h[2]) / w else (h[3] * x + h[4] * y + h[5]) / w
    }

    /** The start dot: the dot-sized blob out beyond the outer ring, nearest the right size, helped by its colour. */
    private fun findMarker(bezel: Bezel, inv: DoubleArray, sc: Double, frame: RingFrame): Int? {
        val markerA = PI * RingCodeLayout.MARKER_RADIUS * RingCodeLayout.MARKER_RADIUS * sc * sc
        var best = -1
        var bestScore = 0.0
        for (c in 0 until compCount) {
            if (c == bezel.comp) continue
            val a = cArea[c].toDouble()
            if (a < markerA * 0.4 || a > markerA * 2.4) continue
            val px = cSumX[c] / a - bezel.cx
            val py = cSumY[c] / a - bezel.cy
            val ux = inv[0] * px + inv[1] * py
            val uy = inv[2] * px + inv[3] * py
            val rho = sqrt(ux * ux + uy * uy) * RingCodeLayout.BEZEL_OUTER
            if (rho < 842.0 || rho > 960.0) continue
            val bw = cMaxX[c] - cMinX[c] + 1
            val bh = cMaxY[c] - cMinY[c] + 1
            if (max(bw, bh) > 2.6 * min(bw, bh)) continue
            var score = min(a, markerA) / max(a, markerA)
            score *= 1.0 - abs(rho - RingCodeLayout.MARKER_RADIUS_AT) / 300.0
            val hue = frame.hue
            if (hue != null) {
                val hx = ((cSumX[c] / a) * diagnostics.scale * frame.hueWidth / frame.width).toInt().coerceIn(0, frame.hueWidth - 1)
                val hy = ((cSumY[c] / a) * diagnostics.scale * frame.hueHeight / frame.height).toInt().coerceIn(0, frame.hueHeight - 1)
                score *= 1.0 + (hue[hy * frame.hueWidth + hx].toInt() and 0xFF) / 255.0
            }
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (best >= 0 && bestScore > 0.25) best else null
    }

    /**
     * Least-squares fit of the matched slots' plate coordinates to their blob centroids. [dof] 6 fits an affine map,
     * 8 a homography. Drops matches further than 2.5 times the median residual and fits once more.
     */
    private fun fitHomography(slots: IntArray, blobs: IntArray, n: Int, dof: Int): DoubleArray? {
        var h = solve(slots, blobs, n, dof, null) ?: return null
        val residuals = DoubleArray(n)
        for (m in 0 until n) {
            val s = slots[m]
            val dx = cSumX[blobs[m]] / cArea[blobs[m]] - project(h, qx[s], qy[s], 0)
            val dy = cSumY[blobs[m]] / cArea[blobs[m]] - project(h, qx[s], qy[s], 1)
            residuals[m] = sqrt(dx * dx + dy * dy)
        }
        val sorted = residuals.copyOf()
        sorted.sort()
        val cut = max(1.2, 2.5 * sorted[n / 2])
        val keep = BooleanArray(n) { residuals[it] <= cut }
        var kept = 0
        for (m in 0 until n) if (keep[m]) kept++
        if (kept in (dof + 2) until n) h = solve(slots, blobs, n, dof, keep) ?: h
        return h
    }

    private fun solve(slots: IntArray, blobs: IntArray, n: Int, dof: Int, keep: BooleanArray?): DoubleArray? {
        // normalise: plate coordinates / 1000; pixel coordinates relative to their mean and divided by their spread
        var mu = 0.0
        var mv = 0.0
        var cnt = 0
        for (m in 0 until n) {
            if (keep != null && !keep[m]) continue
            mu += cSumX[blobs[m]] / cArea[blobs[m]]
            mv += cSumY[blobs[m]] / cArea[blobs[m]]
            cnt++
        }
        if (cnt < dof / 2 + 2) return null
        mu /= cnt
        mv /= cnt
        var spread = 0.0
        for (m in 0 until n) {
            if (keep != null && !keep[m]) continue
            val u = cSumX[blobs[m]] / cArea[blobs[m]] - mu
            val v = cSumY[blobs[m]] / cArea[blobs[m]] - mv
            spread += u * u + v * v
        }
        spread = sqrt(spread / cnt)
        if (spread < 1e-6) return null
        val size = if (dof == 8) 8 else 6
        val ata = Array(size) { DoubleArray(size) }
        val atb = DoubleArray(size)
        val row = DoubleArray(size)
        fun add(rhs: Double) {
            for (i in 0 until size) {
                if (row[i] == 0.0) continue
                for (j in 0 until size) ata[i][j] += row[i] * row[j]
                atb[i] += row[i] * rhs
            }
        }
        for (m in 0 until n) {
            if (keep != null && !keep[m]) continue
            val s = slots[m]
            val x = qx[s] / 1000.0
            val y = qy[s] / 1000.0
            val u = (cSumX[blobs[m]] / cArea[blobs[m]] - mu) / spread
            val v = (cSumY[blobs[m]] / cArea[blobs[m]] - mv) / spread
            row.fill(0.0)
            row[0] = x; row[1] = y; row[2] = 1.0
            if (dof == 8) { row[6] = -u * x; row[7] = -u * y }
            add(u)
            row.fill(0.0)
            row[3] = x; row[4] = y; row[5] = 1.0
            if (dof == 8) { row[6] = -v * x; row[7] = -v * y }
            add(v)
        }
        val sol = gauss(ata, atb, size) ?: return null
        val g = DoubleArray(9)
        for (i in 0 until size) g[i] = sol[i]
        g[8] = 1.0
        // undo the normalisation: pixel = (mu, mv) + spread * (u, v), and plate permille / 1000 = x
        val a0 = g[0] / 1000.0; val a1 = g[1] / 1000.0; val a2 = g[2]
        val b0 = g[3] / 1000.0; val b1 = g[4] / 1000.0; val b2 = g[5]
        val c0 = g[6] / 1000.0; val c1 = g[7] / 1000.0; val c2 = g[8]
        return doubleArrayOf(
            spread * a0 + mu * c0, spread * a1 + mu * c1, spread * a2 + mu * c2,
            spread * b0 + mv * c0, spread * b1 + mv * c1, spread * b2 + mv * c2,
            c0, c1, c2,
        )
    }

    private fun gauss(a: Array<DoubleArray>, b: DoubleArray, n: Int): DoubleArray? {
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col until n + 1) m[r][c] -= f * m[col][c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = m[r][n]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return x
    }

    // ── 4. measure ───────────────────────────────────────────────────────────────────

    private fun invert3(h: DoubleArray): DoubleArray? {
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val hh = h[7]; val i = h[8]
        val det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g)
        if (abs(det) < 1e-18) return null
        val inv = 1.0 / det
        return doubleArrayOf(
            (e * i - f * hh) * inv, (c * hh - b * i) * inv, (b * f - c * e) * inv,
            (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            (d * hh - e * g) * inv, (b * g - a * hh) * inv, (a * e - b * d) * inv,
        )
    }

    private val windowRadius = 50.0

    /** The dot is measured inside this radius (permille); the ring of plate around it, from [ANNULUS_FROM] to the window radius, says how light the plate is right there. */
    private val dotRadius = 41.0
    private val annulusFrom = 44.0

    private fun measure(h: DoubleArray) {
        val inv = invert3(h)
        slotMass.fill(0.0)
        if (inv == null) return
        hist.fill(0)
        val r2 = windowRadius * windowRadius
        // pass 1: the plate's light and dark levels, from the pixels inside the slot windows
        for (slot in 0 until RingCodeLayout.SLOTS) {
            val cx = project(h, qx[slot], qy[slot], 0)
            val cy = project(h, qx[slot], qy[slot], 1)
            slotCx[slot] = cx
            slotCy[slot] = cy
            forWindow(h, inv, slot, cx, cy, r2) { x, y, _ -> hist[gray[y * gw + x]]++ }
        }
        var total = 0
        for (v in hist) total += v
        if (total < 100) return
        var acc = 0
        var fg = 0
        var bg = 255
        var gotFg = false
        for (v in 0..255) {
            acc += hist[v]
            if (!gotFg && acc * 100L >= total * 8L) {
                fg = v
                gotFg = true
            }
            if (acc * 100L >= total * 92L) {
                bg = v
                break
            }
        }
        if (bg - fg < 24) return
        val dotR2 = dotRadius * dotRadius
        val annR2 = annulusFrom * annulusFrom
        val darkShare = 1.0 - fg.toDouble() / bg
        for (slot in 0 until RingCodeLayout.SLOTS) {
            var dotSum = 0
            var dotCount = 0
            var annSum = 0
            var annCount = 0
            forWindow(h, inv, slot, slotCx[slot], slotCy[slot], r2) { x, y, d2 ->
                val g = gray[y * gw + x]
                if (d2 <= dotR2) {
                    dotSum += g
                    dotCount++
                } else if (d2 >= annR2) {
                    annSum += g
                    annCount++
                }
            }
            if (dotCount == 0 || annCount == 0) continue
            // The plate's own level right at this dot (a smudge, a gradient or a glare edge moves it), and how far
            // under it the dot's pixels sit, in units of a full dot's depth at that level. A dot hidden under an
            // opaque smudge has the smudge's level all round it and reads as no dot at all.
            val local = annSum.toDouble() / annCount
            val depth = local * darkShare
            if (depth < 12.0) continue
            var mass = 0.0
            forWindow(h, inv, slot, slotCx[slot], slotCy[slot], dotR2) { x, y, _ ->
                mass += ((local - gray[y * gw + x]) / depth).coerceIn(0.0, 1.0)
            }
            slotMass[slot] = mass / dotCount * PI * dotR2
        }
    }

    private inline fun forWindow(h: DoubleArray, inv: DoubleArray, slot: Int, cx: Double, cy: Double, r2: Double, body: (Int, Int, Double) -> Unit) {
        val rr = kotlin.math.sqrt(r2)
        val ex = project(h, qx[slot] + rr, qy[slot], 0) - cx
        val ey = project(h, qx[slot] + rr, qy[slot], 1) - cy
        val fx = project(h, qx[slot], qy[slot] + rr, 0) - cx
        val fy = project(h, qx[slot], qy[slot] + rr, 1) - cy
        val hx = abs(ex) + abs(fx) + 1.5
        val hy = abs(ey) + abs(fy) + 1.5
        val x0 = max(0, (cx - hx).toInt())
        val x1 = min(gw - 1, (cx + hx).toInt())
        val y0 = max(0, (cy - hy).toInt())
        val y1 = min(gh - 1, (cy + hy).toInt())
        val qxs = qx[slot]
        val qys = qy[slot]
        for (y in y0..y1) for (x in x0..x1) {
            val px = x + 0.5
            val py = y + 0.5
            val w = inv[6] * px + inv[7] * py + inv[8]
            val ux = (inv[0] * px + inv[1] * py + inv[2]) / w - qxs
            val uy = (inv[3] * px + inv[4] * py + inv[5]) / w - qys
            val d2 = ux * ux + uy * uy
            if (d2 <= r2) body(x, y, d2)
        }
    }

    // ── 5. decode ────────────────────────────────────────────────────────────────────

    private fun decode(): RingScanResult {
        val d = diagnostics
        val smallM = PI * RingCodeLayout.SMALL_RADIUS * RingCodeLayout.SMALL_RADIUS.toDouble()
        val bigM = PI * RingCodeLayout.BIG_RADIUS * RingCodeLayout.BIG_RADIUS.toDouble()
        val globalT = twoMeans(0, RingCodeLayout.SLOTS, smallM, bigM, (smallM + bigM) / 2.0)
        val gap = (bigM - smallM) / 2.0
        for (ring in 0 until RingCodeLayout.RINGS) {
            val first = RingCodeLayout.RING_FIRST_SLOT[ring]
            val n = RingCodeLayout.RING_SLOTS[ring]
            val t = twoMeans(first, first + n, smallM, bigM, globalT)
            for (s in first until first + n) {
                val m = slotMass[s]
                slotBit[s] = if (m > t) 1 else 0
                var conf = abs(m - t) / gap
                if (m < smallM * 0.3) conf = 0.0 // no dot at all (covered up): nothing is known
                if (m > bigM * 1.25) conf = 0.0 // more dark than the biggest dot can make: nothing is known
                slotConf[s] = (min(1.0, conf) * 100.0).toInt()
            }
        }
        for (s in 0 until RingCodeLayout.SLOTS) {
            d.slotBits[s] = slotBit[s]
            d.slotConfidence[s] = slotConf[s]
            d.slotMass[s] = slotMass[s]
        }
        val word = RingCodeLayout.slotsToCodeword(slotBit)
        val byteConf = IntArray(RingCodeLayout.BYTES) { 100 }
        for (s in 0 until RingCodeLayout.SLOTS) {
            val b = RingCodeLayout.byteOfSlot(s)
            if (slotConf[s] < byteConf[b]) byteConf[b] = slotConf[s]
        }
        val order = (0 until RingCodeLayout.BYTES).sortedBy { byteConf[it] }
        var sawChecksumFailure = false
        for (k in MAX_ERASURE_STEPS) {
            val rs = ReedSolomon.decode(word, IntArray(k) { order[it] })
            if (!rs.ok) continue
            when (val u = RingCodePayloads.fromCodeword(rs.codeword!!)) {
                is RingUnpack.Ok -> {
                    d.rsCorrected = rs.corrected
                    d.erasuresUsed = k
                    return RingScanResult.Decoded(u.payload, rs.corrected, k)
                }
                is RingUnpack.Rejected -> sawChecksumFailure = true
            }
        }
        return RingScanResult.Failed(if (sawChecksumFailure) RingScanFailure.BAD_CHECKSUM else RingScanFailure.UNREADABLE)
    }

    /** Two-means on masses [from, to): the decision line between the two clusters, or [fallback] when the data does not show two. */
    private fun twoMeans(from: Int, to: Int, lowSeed: Double, highSeed: Double, fallback: Double): Double {
        var lo = lowSeed
        var hi = highSeed
        var t = (lo + hi) / 2.0
        repeat(8) {
            var sl = 0.0
            var nl = 0
            var sh = 0.0
            var nh = 0
            for (s in from until to) {
                val m = slotMass[s]
                if (m > t) { sh += m; nh++ } else { sl += m; nl++ }
            }
            if (nl > 0) lo = sl / nl
            if (nh > 0) hi = sh / nh
            t = (lo + hi) / 2.0
        }
        if (hi / max(lo, 1.0) < 1.8) return fallback
        val mid = (lowSeed + highSeed) / 2.0
        return min(max(t, mid * 0.6), mid * 1.5)
    }

    companion object {
        /** How many of the least certain bytes are flagged as erasures, in the order they are tried. More than 10 is not tried: see RingPayloadTest. */
        private const val MAX_OUTLINES = 4
        private val MAX_ERASURE_STEPS = intArrayOf(0, 3, 5, 7, 10, 12)
        private val STAGE_RINGS = intArrayOf(1, 1, 2, 3, 4)
        private val STAGE_TOLERANCE = doubleArrayOf(0.46, 0.36, 0.40, 0.40, 0.40)
    }
}

/**
 * Collects scan results over time and accepts a code only after `required` consistent decodes: the same
 * payload each time, with no different decode in between and no gap longer than `maxGapMs` between two
 * decodes. A frame that fails to decode neither counts nor breaks the run (a blurred frame is not a
 * disagreement), but it cannot keep a stale run alive past the gap.
 */
class RingScanAccumulator(private val required: Int = 3, private val maxGapMs: Long = 2_000L) {
    private var last: RingCodePayload? = null
    private var count = 0
    private var lastAtMs = 0L

    /** The accepted payload once it has been seen `required` times in a row, else null. */
    fun offer(result: RingScanResult, nowMs: Long): RingCodePayload? {
        if (result !is RingScanResult.Decoded) {
            if (last != null && nowMs - lastAtMs > maxGapMs) reset()
            return null
        }
        val p = result.payload
        if (last != null && p == last && nowMs - lastAtMs <= maxGapMs) count++ else {
            last = p
            count = 1
        }
        lastAtMs = nowMs
        return if (count >= required) p else null
    }

    fun reset() {
        last = null
        count = 0
    }
}
