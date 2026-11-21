package com.theveloper.pixelplay.songtap.ring.image

import com.theveloper.pixelplay.songtap.SplitMix64
import com.theveloper.pixelplay.songtap.ring.RingCodePayload
import com.theveloper.pixelplay.songtap.ring.RingCodePayloads
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Shared helpers for the image-side tests: random codes, rendering, scanning, and PNGs of what was rendered. */
object ScanHarness {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    class Case(val payload: RingCodePayload, val bits: ByteArray)

    fun randomCase(rng: SplitMix64): Case {
        val id = String(CharArray(11) { ALPHABET[rng.nextInt(64)] })
        val p = RingCodePayload(id, rng.nextInt(64), rng.nextInt(256))
        return Case(p, RingCodePayloads.encodeSlots(p.videoId, p.minuteBucket, p.random)!!)
    }

    fun frameOf(r: Rendered, withHue: Boolean = true): RingFrame =
        RingFrame(r.image.width, r.image.height, r.image.luma, if (withHue) r.image.orange else null)

    fun scan(scanner: RingScanner, c: Case, p: RenderParams, withHue: Boolean = true): RingScanResult {
        val r = RingCodeRenderer.render(c.bits, p)
        return scanner.scan(frameOf(r, withHue))
    }

    fun ok(result: RingScanResult, c: Case): Boolean = result is RingScanResult.Decoded && result.payload == c.payload

    fun describe(result: RingScanResult, scanner: RingScanner): String {
        val d = scanner.diagnostics
        val what = when (result) {
            is RingScanResult.Decoded -> "decoded corrected=${result.corrected} erasures=${result.erasures}"
            is RingScanResult.Failed -> "failed ${result.reason.code}"
        }
        return "$what | found=${d.found} marker=${d.markerFound} matched=${d.matched} resid=${"%.2f".format(d.residualPx)}px cand=${d.candidates}"
    }

    fun savePng(name: String, image: LumaImage) {
        try {
            val dir = File("build/ring-preview")
            dir.mkdirs()
            val bi = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val v = image.luma[y * image.width + x].toInt() and 0xFF
                bi.raster.setSample(x, y, 0, v)
            }
            ImageIO.write(bi, "png", File(dir, "$name.png"))
        } catch (_: Exception) {
        }
    }
}
