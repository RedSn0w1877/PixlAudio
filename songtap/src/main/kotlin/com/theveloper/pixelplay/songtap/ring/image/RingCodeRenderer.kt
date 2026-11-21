package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.ring.RingCodeLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A grayscale picture as a camera's Y plane records it, plus the "orangeness" map a chroma plane gives
 * (255 where the start dot's colour is, 0 elsewhere). [luma] is row-major, one byte per pixel.
 */
class LumaImage(val width: Int, val height: Int, val luma: ByteArray, val orange: ByteArray)

/** A bright patch (screen glare): adds up to [gain] luma at its centre, Gaussian falloff, an ellipse of radii [rx], [ry] pixels turned by [angle]. */
class Glare(val cx: Double, val cy: Double, val rx: Double, val ry: Double, val angle: Double, val gain: Double)

/** A smudge on the lens or the screen: inside [radius] pixels of ([cx], [cy]) the picture fades to [luma] by [alpha] (soft edge of 10 percent of the radius). */
class Smudge(val cx: Double, val cy: Double, val radius: Double, val luma: Int, val alpha: Double)

enum class ArtMode { TEXTURE, DARK, LIGHT, STRIPES, ORANGE_TEXTURE }

/**
 * How a test (or the Developer mode preview) wants a code drawn. Angles are radians. The plate is drawn
 * [plateHalfPx] pixels from its centre to its edge before tilt; tilt turns it about an axis through its centre
 * ([tiltAxisRad] says which way) and [camDistance] (in plate half-sizes) sets how strong the perspective is.
 */
class RenderParams(
    val width: Int = 360,
    val height: Int = 360,
    val centerX: Double = width / 2.0,
    val centerY: Double = height / 2.0,
    val plateHalfPx: Double = 130.0,
    val rotationRad: Double = 0.0,
    val tiltRad: Double = 0.0,
    val tiltAxisRad: Double = 0.0,
    val camDistance: Double = 6.0,
    val blurSigmaPx: Double = 0.0,
    val noiseSigma: Double = 0.0,
    val noiseSeed: Long = 1L,
    val glares: List<Glare> = emptyList(),
    val smudges: List<Smudge> = emptyList(),
    val dotLuma: Int = 58,
    val plateLuma: Int = 238,
    val markerLuma: Int = 105,
    val backgroundLuma: Int = 120,
    val clutter: Boolean = false,
    val artMode: ArtMode = ArtMode.TEXTURE,
    val artSeed: Int = 7,
    val supersample: Int = 2,
    /** Multiplies every luma value around 128 (a flat, low-contrast camera when below 1). */
    val contrast: Double = 1.0,
    /** Draws the start dot (a test can leave it out). */
    val marker: Boolean = true,
)

/** A rendered code and the matrix that placed it: [plateToImage] takes plate coordinates in permille (x right, y down, centre 0,0) to pixels. */
class Rendered(val image: LumaImage, val plateToImage: DoubleArray)

object RingCodeRenderer {

    /** The 3x3 homography (row major) from plate permille to pixels. */
    fun homography(p: RenderParams): DoubleArray {
        val cr = cos(p.rotationRad)
        val sr = sin(p.rotationRad)
        // in-plane rotation of the code (clockwise on screen, y down): (x, y) -> (x cr - y sr, x sr + y cr)
        val ax = cos(p.tiltAxisRad)
        val ay = sin(p.tiltAxisRad)
        val ct = cos(p.tiltRad)
        val st = sin(p.tiltRad)
        // P = Rz * (x, y); tilt about axis a: P'.x = P.x ct + ax (a.P)(1-ct), P'.y = P.y ct + ay (a.P)(1-ct), P'.z = (ax P.y - ay P.x) st
        // all linear in (x, y): build the 3x2 matrix B (rows x', y', z')
        val rx = doubleArrayOf(cr, -sr, sr, cr) // [m11 m12; m21 m22] of Rz
        fun pxCoef(i: Int, j: Int): Double = rx[i * 2 + j]
        val b = Array(3) { DoubleArray(2) }
        for (j in 0..1) {
            val px = pxCoef(0, j)
            val py = pxCoef(1, j)
            val dot = ax * px + ay * py
            b[0][j] = px * ct + ax * dot * (1 - ct)
            b[1][j] = py * ct + ay * dot * (1 - ct)
            b[2][j] = (ax * py - ay * px) * st
        }
        val s = p.plateHalfPx / 1000.0
        val c1 = b[2][0] / p.camDistance / 1000.0
        val c2 = b[2][1] / p.camDistance / 1000.0
        return doubleArrayOf(
            p.centerX * c1 + s * b[0][0], p.centerX * c2 + s * b[0][1], p.centerX,
            p.centerY * c1 + s * b[1][0], p.centerY * c2 + s * b[1][1], p.centerY,
            c1, c2, 1.0,
        )
    }

    fun invert(h: DoubleArray): DoubleArray {
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val hh = h[7]; val i = h[8]
        val det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g)
        val inv = 1.0 / det
        return doubleArrayOf(
            (e * i - f * hh) * inv, (c * hh - b * i) * inv, (b * f - c * e) * inv,
            (f * g - d * i) * inv, (a * i - c * g) * inv, (c * d - a * f) * inv,
            (d * hh - e * g) * inv, (b * g - a * hh) * inv, (a * e - b * d) * inv,
        )
    }


    private fun hash(x: Int, y: Int, seed: Int): Double {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535.0
    }

    private fun valueNoise(x: Double, y: Double, seed: Int): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(x0, y0, seed)
        val b = hash(x0 + 1, y0, seed)
        val c = hash(x0, y0 + 1, seed)
        val d = hash(x0 + 1, y0 + 1, seed)
        return (a + (b - a) * sx) * (1 - sy) + (c + (d - c) * sx) * sy
    }

    private class Scene(val p: RenderParams, val bits: ByteArray) {
        var orange = 0.0
        private val ringSlots = RingCodeLayout.RING_SLOTS
        private val bigR2 = (RingCodeLayout.BIG_RADIUS * RingCodeLayout.BIG_RADIUS).toDouble()
        private val smallR2 = (RingCodeLayout.SMALL_RADIUS * RingCodeLayout.SMALL_RADIUS).toDouble()
        private val markerR2 = (RingCodeLayout.MARKER_RADIUS * RingCodeLayout.MARKER_RADIUS).toDouble()

        fun luma(qx: Double, qy: Double): Double {
            orange = 0.0
            val r = sqrt(qx * qx + qy * qy)
            val inPlate = insidePlate(qx, qy)
            if (!inPlate) return outside(qx, qy)
            if (r < RingCodeLayout.ART_RADIUS) {
                return art(qx, qy)
            }
            if (r < RingCodeLayout.BEZEL_INNER) return p.plateLuma.toDouble() // the light gap around the art
            if (r < RingCodeLayout.BEZEL_OUTER) return p.dotLuma.toDouble()
            if (p.marker) {
                val mdx = qx
                val mdy = qy + RingCodeLayout.MARKER_RADIUS_AT
                if (mdx * mdx + mdy * mdy <= markerR2) { orange = 1.0; return p.markerLuma.toDouble() }
            }
            // rings
            var ring = -1
            var best = 1e9
            for (k in 0 until 5) {
                val d = abs(r - RingCodeLayout.RING_RADIUS[k])
                if (d < best) { best = d; ring = k }
            }
            if (best <= RingCodeLayout.BIG_RADIUS + 1) {
                val n = ringSlots[ring]
                var ang = atan2(qx, -qy) // clockwise from 12 o'clock
                if (ang < 0) ang += 2 * PI
                val idx = ((ang / (2 * PI) * n) + 0.5).toInt() % n
                val a = idx * 2 * PI / n
                val cx = RingCodeLayout.RING_RADIUS[ring] * sin(a)
                val cy = -RingCodeLayout.RING_RADIUS[ring] * cos(a)
                val dx = qx - cx
                val dy = qy - cy
                val d2 = dx * dx + dy * dy
                val big = bits[RingCodeLayout.slotOf(ring, idx)].toInt() != 0
                if (d2 <= (if (big) bigR2 else smallR2)) return p.dotLuma.toDouble()
            }
            return p.plateLuma.toDouble()
        }

        private fun insidePlate(qx: Double, qy: Double): Boolean {
            val lim = 1000.0 - RingCodeLayout.PLATE_CORNER
            val dx = max(abs(qx) - lim, 0.0)
            val dy = max(abs(qy) - lim, 0.0)
            return dx * dx + dy * dy <= RingCodeLayout.PLATE_CORNER.toDouble() * RingCodeLayout.PLATE_CORNER
        }

        private fun art(qx: Double, qy: Double): Double = when (p.artMode) {
            ArtMode.DARK -> 25.0
            ArtMode.LIGHT -> 245.0
            ArtMode.STRIPES -> if ((floor(qx / 45.0).toInt() + floor(qy / 45.0).toInt()) and 1 == 0) 30.0 else 225.0
            ArtMode.TEXTURE -> 20.0 + 215.0 * valueNoise(qx / 80.0, qy / 80.0, p.artSeed)
            ArtMode.ORANGE_TEXTURE -> {
                val v = valueNoise(qx / 80.0, qy / 80.0, p.artSeed)
                orange = if (v > 0.5) 1.0 else 0.0
                20.0 + 215.0 * v
            }
        }

        private fun outside(qx: Double, qy: Double): Double {
            var v = p.backgroundLuma + 18.0 * sin(qx / 600.0) * cos(qy / 700.0)
            if (p.clutter) {
                // dark book-spine bars and a few round objects around the plate, in the plate's plane
                for (k in 0 until 7) {
                    val cx = (hash(k, 1, 91) * 2.0 - 1.0) * 2600.0
                    val cy = (hash(k, 2, 91) * 2.0 - 1.0) * 2600.0
                    val w = 90.0 + hash(k, 3, 91) * 400.0
                    val hh = 90.0 + hash(k, 4, 91) * 400.0
                    if (abs(cx) < 1250.0 && abs(cy) < 1250.0) continue
                    if (abs(qx - cx) < w && abs(qy - cy) < hh) v = 35.0 + 30.0 * hash(k, 5, 91)
                }
            }
            return v
        }
    }

    private fun boxBlurPass(src: FloatArray, dst: FloatArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        if (radius <= 0) { src.copyInto(dst); return }
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int): Float {
                val j = if (i < 0) 0 else if (i >= n) n - 1 else i
                return if (horizontal) src[line * w + j] else src[j * w + line]
            }
            var acc = 0f
            for (i in -radius..radius) acc += at(i)
            for (i in 0 until n) {
                val v = acc / window
                if (horizontal) dst[line * w + i] = v else dst[i * w + line] = v
                acc += at(i + radius + 1) - at(i - radius)
            }
        }
    }

    /** Gaussian-like blur: three box passes each way. */
    fun blur(values: FloatArray, w: Int, h: Int, sigma: Double) {
        if (sigma <= 0.05) return
        val box = sqrt(12.0 * sigma * sigma / 3.0 + 1.0)
        val radius = max(0, ((box - 1.0) / 2.0 + 0.5).toInt())
        val tmp = FloatArray(values.size)
        repeat(3) {
            boxBlurPass(values, tmp, w, h, radius, true)
            boxBlurPass(tmp, values, w, h, radius, false)
        }
    }

    fun render(bits: ByteArray, p: RenderParams): Rendered {
        require(bits.size == RingCodeLayout.SLOTS) { "200 slot bits" }
        val w = p.width
        val h = p.height
        val hom = homography(p)
        val inv = invert(hom)
        val scene = Scene(p, bits)
        val ss = max(1, p.supersample)
        val img = FloatArray(w * h)
        val orange = ByteArray(w * h)
        val inv2 = 1.0 / (ss * ss)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0.0
                var orangeAcc = 0.0
                for (sy in 0 until ss) for (sx in 0 until ss) {
                    val px = x + (sx + 0.5) / ss
                    val py = y + (sy + 0.5) / ss
                    val wq = inv[6] * px + inv[7] * py + inv[8]
                    val qx = (inv[0] * px + inv[1] * py + inv[2]) / wq
                    val qy = (inv[3] * px + inv[4] * py + inv[5]) / wq
                    acc += if (wq <= 0.0) p.backgroundLuma.toDouble() else scene.luma(qx, qy)
                    orangeAcc += scene.orange
                }
                img[y * w + x] = (acc * inv2).toFloat()
                orange[y * w + x] = (orangeAcc * inv2 * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }
        // smudges (a physical overlay), then glare (light added before the lens blurs it)
        for (s in p.smudges) {
            val soft = max(1.0, s.radius * 0.10)
            val x0 = max(0, (s.cx - s.radius - soft).toInt())
            val x1 = min(w - 1, (s.cx + s.radius + soft).toInt())
            val y0 = max(0, (s.cy - s.radius - soft).toInt())
            val y1 = min(h - 1, (s.cy + s.radius + soft).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val d = sqrt((x + 0.5 - s.cx) * (x + 0.5 - s.cx) + (y + 0.5 - s.cy) * (y + 0.5 - s.cy))
                val k = ((s.radius + soft - d) / (2 * soft)).coerceIn(0.0, 1.0) * s.alpha
                if (k > 0.0) {
                    val i = y * w + x
                    img[i] = (img[i] * (1 - k) + s.luma * k).toFloat()
                    orange[i] = (orange[i] * (1 - k)).toInt().toByte()
                }
            }
        }
        for (g in p.glares) {
            val ca = cos(g.angle)
            val sa = sin(g.angle)
            val reach = 3.0 * max(g.rx, g.ry)
            val x0 = max(0, (g.cx - reach).toInt())
            val x1 = min(w - 1, (g.cx + reach).toInt())
            val y0 = max(0, (g.cy - reach).toInt())
            val y1 = min(h - 1, (g.cy + reach).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x + 0.5 - g.cx
                val dy = y + 0.5 - g.cy
                val u = (dx * ca + dy * sa) / g.rx
                val v = (-dx * sa + dy * ca) / g.ry
                img[y * w + x] += (g.gain * exp(-(u * u + v * v) / 2.0)).toFloat()
            }
        }
        blur(img, w, h, p.blurSigmaPx)
        var state = p.noiseSeed * 0x9E3779B97F4A7C15uL.toLong() + 12345L
        fun nextUniform(): Double {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return ((z ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            var v = img[i].toDouble()
            v = 128.0 + (v - 128.0) * p.contrast
            if (p.noiseSigma > 0.0) v += p.noiseSigma * (nextUniform() + nextUniform() + nextUniform() + nextUniform() - 2.0) * sqrt(3.0)
            out[i] = v.toInt().coerceIn(0, 255).toByte()
        }
        return Rendered(LumaImage(w, h, out, orange), hom)
    }

    /** A smudge sized to cover about [fraction] of the 200 data slots: a disc centred on a slot, grown until it covers that many. */
    fun smudgeCovering(bits: ByteArray, p: RenderParams, centerSlot: Int, fraction: Double, luma: Int, alpha: Double = 1.0): Smudge {
        val hom = homography(p)
        fun slotPx(slot: Int): DoubleArray {
            val ring = RingCodeLayout.ringOf(slot)
            val a = RingCodeLayout.angleMicroTurns(slot) / 1_000_000.0 * 2 * PI
            val qx = RingCodeLayout.RING_RADIUS[ring] * sin(a)
            val qy = -RingCodeLayout.RING_RADIUS[ring] * cos(a)
            val wq = hom[6] * qx + hom[7] * qy + hom[8]
            return doubleArrayOf((hom[0] * qx + hom[1] * qy + hom[2]) / wq, (hom[3] * qx + hom[4] * qy + hom[5]) / wq)
        }
        val c = slotPx(centerSlot)
        val dists = DoubleArray(RingCodeLayout.SLOTS) { val s = slotPx(it); sqrt((s[0] - c[0]) * (s[0] - c[0]) + (s[1] - c[1]) * (s[1] - c[1])) }
        val sorted = dists.sortedArray()
        val k = max(1, (fraction * RingCodeLayout.SLOTS).toInt())
        val radius = sorted[min(k, sorted.size - 1)] - 1.0
        return Smudge(c[0], c[1], max(4.0, radius), luma, alpha)
    }
}
