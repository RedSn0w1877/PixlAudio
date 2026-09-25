package com.theveloper.pixelplay.presentation.lyrics.background

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi

/**
 * The lyrics background colour grade, as plain numbers so the AGSL shader (tier B), the
 * `ColorMatrixColorFilter` (tier A) and the bright-art luma check all share one definition.
 *
 * Grade, in gamma-encoded sRGB, clamped only at the very end:
 *  1. saturation 2.75 around Rec.709 luma
 *  2. contrast 1.9 around mid-grey
 *  3. brightness ×0.7
 * then (after the clamp) black at 50% and white at 5% on top.
 *
 * Pure Kotlin, no Android types — unit-tested on the JVM.
 */
internal object LyricsBackgroundGrade {
    const val SATURATION = 2.75f
    const val CONTRAST = 1.9f
    const val BRIGHTNESS = 0.7f

    const val LUMA_R = 0.2125f
    const val LUMA_G = 0.7154f
    const val LUMA_B = 0.0721f

    /** Black overlay alpha, applied after the clamp. */
    const val BLACK_OVERLAY = 0.5f

    /** White overlay alpha, applied after the black one. */
    const val WHITE_OVERLAY = 0.05f

    /** Graded mean luma above which the art counts as "bright" (spec §1.2). */
    const val BRIGHT_ART_LUMA = 0.6f

    /** Black scrim drawn over the background when the art is bright (spec §1.2). */
    const val BRIGHT_ART_SCRIM = 0.35f

    /**
     * Saturation → contrast → brightness collapsed into one 4×5 row-major colour matrix, in the
     * layout `android.graphics.ColorMatrix` uses (offsets on the 0–255 scale). The overlays are
     * deliberately NOT folded in: the clamp has to happen between the grade and the overlays.
     *
     * Every RGB row sums to CONTRAST × BRIGHTNESS = 1.33 and the offset is −0.315 × 255.
     */
    val GRADE: FloatArray = buildGradeMatrix()

    private fun buildGradeMatrix(): FloatArray {
        val gain = CONTRAST * BRIGHTNESS
        val offset = BRIGHTNESS * 0.5f * (1f - CONTRAST) * 255f
        val weights = floatArrayOf(LUMA_R, LUMA_G, LUMA_B)
        val m = FloatArray(20)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val identity = if (row == col) SATURATION else 0f
                m[row * 5 + col] = gain * (identity + (1f - SATURATION) * weights[col])
            }
            m[row * 5 + 3] = 0f
            m[row * 5 + 4] = offset
        }
        // Alpha passes through untouched.
        m[18] = 1f
        return m
    }

    /**
     * The three-step reference grade on 0..1 sRGB components, clamped only at the end.
     * Writes r, g, b into [out] (size ≥ 3).
     */
    fun gradeReference(r: Float, g: Float, b: Float, out: FloatArray) {
        val luma = r * LUMA_R + g * LUMA_G + b * LUMA_B
        var rr = luma + (r - luma) * SATURATION
        var gg = luma + (g - luma) * SATURATION
        var bb = luma + (b - luma) * SATURATION
        rr = (rr - 0.5f) * CONTRAST + 0.5f
        gg = (gg - 0.5f) * CONTRAST + 0.5f
        bb = (bb - 0.5f) * CONTRAST + 0.5f
        out[0] = (rr * BRIGHTNESS).coerceIn(0f, 1f)
        out[1] = (gg * BRIGHTNESS).coerceIn(0f, 1f)
        out[2] = (bb * BRIGHTNESS).coerceIn(0f, 1f)
    }

    /** Rec.709 luma of the graded (pre-overlay) colour, for the bright-art check. */
    fun gradedLuma(r: Float, g: Float, b: Float, scratch: FloatArray): Float {
        gradeReference(r, g, b, scratch)
        return scratch[0] * LUMA_R + scratch[1] * LUMA_G + scratch[2] * LUMA_B
    }
}

/**
 * Tier B (API 33+): one full-screen AGSL pass that does, per pixel,
 * twist → composite the four pre-blurred sprites → grade → overlays → dither.
 *
 * Our own shader, written from the maths in the lyrics spec §2.3 (twist is the textbook
 * "rotate by θ·((R−|d|)/R)²" swirl; dither is interleaved-gradient noise, public domain).
 *
 * Each sprite child is a [BitmapShader] whose local matrix only centres the texture on the
 * origin (set once). The per-frame sprite transform is passed as a uniform instead, so a frame
 * never rebuilds a native child shader: it is just a handful of uniform writes.
 */
@RequiresApi(33)
internal object LyricsBackgroundShader {

    const val TWIST_ANGLE = -3.25f

    /** Twist radius as a fraction of the short side (Apple's 900 px, rescaled; tune by eye). */
    const val TWIST_RADIUS_FRACTION = 1.0f

    private val G = LyricsBackgroundGrade

    /**
     * Uniforms:
     *  - `artK`   sprite K, texture centred on (0,0) in texels.
     *  - `xfK`    (s·cosθ, s·sinθ, centreX, centreY): maps a screen point to sprite texels via
     *             R(−θ)·(q − centre)·s, with s = texels per screen px.
     *  - `size`   view size in px.
     *  - `twistAngle`, `twistRadius` (px).
     *  - `alpha`  crossfade weight of this sprite set (output is premultiplied by it).
     *  - `scrim`  extra black scrim for bright art (0 or 0.35).
     */
    val AGSL: String = """
        uniform shader art0;
        uniform shader art1;
        uniform shader art2;
        uniform shader art3;
        uniform float4 xf0;
        uniform float4 xf1;
        uniform float4 xf2;
        uniform float4 xf3;
        uniform float2 size;
        uniform float twistAngle;
        uniform float twistRadius;
        uniform float alpha;
        uniform float scrim;

        const float3 LUMA = float3(${G.LUMA_R}, ${G.LUMA_G}, ${G.LUMA_B});
        const float SATURATION = ${G.SATURATION};
        const float CONTRAST = ${G.CONTRAST};
        const float BRIGHTNESS = ${G.BRIGHTNESS};
        const float BLACK_OVERLAY = ${G.BLACK_OVERLAY};
        const float WHITE_OVERLAY = ${G.WHITE_OVERLAY};

        float2 toSprite(float4 xf, float2 q) {
            float2 d = q - xf.zw;
            return float2(xf.x * d.x + xf.y * d.y, xf.x * d.y - xf.y * d.x);
        }

        float4 blendOver(float4 top, float4 under) {
            return top + under * (1.0 - top.a);
        }

        half4 main(float2 p) {
            // 1. Twist around the centre.
            float2 c = size * 0.5;
            float2 d = p - c;
            float dist = length(d);
            if (dist < twistRadius) {
                float k = (twistRadius - dist) / twistRadius;
                float a = twistAngle * k * k;
                float cs = cos(a);
                float sn = sin(a);
                d = float2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
            }
            float2 q = c + d;

            // 2. Composite the sprites, premultiplied, back to front.
            float4 col = float4(art0.eval(toSprite(xf0, q)));
            col = blendOver(float4(art1.eval(toSprite(xf1, q))), col);
            col = blendOver(float4(art2.eval(toSprite(xf2, q))), col);
            col = blendOver(float4(art3.eval(toSprite(xf3, q))), col);
            float3 rgb = col.rgb / max(col.a, 0.0001);

            // 3. Grade in float, clamp only at the end.
            float luma = dot(rgb, LUMA);
            rgb = mix(float3(luma), rgb, SATURATION);
            rgb = (rgb - 0.5) * CONTRAST + 0.5;
            rgb *= BRIGHTNESS;
            rgb = clamp(rgb, 0.0, 1.0);

            // 4. Overlays: black, then white, then the bright-art scrim.
            rgb *= 1.0 - BLACK_OVERLAY;
            rgb = rgb * (1.0 - WHITE_OVERLAY) + WHITE_OVERLAY;
            rgb *= 1.0 - scrim;

            // 5. Dither: interleaved-gradient noise, ±0.5/255.
            float ign = fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));
            rgb += (ign - 0.5) / 255.0;

            return half4(float4(rgb * alpha, alpha));
        }
    """.trimIndent()

    /**
     * Builds the shader for one sprite set. Safe off the main thread (no GPU work until draw).
     * [sprites] is sprite 0 (MIRROR) then 1–3 (CLAMP, transparent padding).
     */
    fun create(sprites: Array<Bitmap>): RuntimeShader {
        val shader = RuntimeShader(AGSL)
        for (k in 0 until 4) {
            val bitmap = sprites[k]
            val tile = if (k == 0) Shader.TileMode.MIRROR else Shader.TileMode.CLAMP
            val child = BitmapShader(bitmap, tile, tile)
            // Child shaders don't inherit the paint's filter flag: ask for bilinear explicitly.
            child.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            // eval(p) samples texel p + size/2, i.e. the texture is centred on the origin.
            child.setLocalMatrix(
                Matrix().apply { setTranslate(-bitmap.width / 2f, -bitmap.height / 2f) }
            )
            shader.setInputShader("art$k", child)
        }
        shader.setFloatUniform("twistAngle", TWIST_ANGLE)
        shader.setFloatUniform("alpha", 1f)
        shader.setFloatUniform("scrim", 0f)
        return shader
    }

    /**
     * Per-frame uniforms. [xf] holds 4 × (s·cosθ, s·sinθ, centreX, centreY).
     */
    fun update(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        xf: FloatArray,
        alpha: Float,
        scrim: Float
    ) {
        shader.setFloatUniform("xf0", xf[0], xf[1], xf[2], xf[3])
        shader.setFloatUniform("xf1", xf[4], xf[5], xf[6], xf[7])
        shader.setFloatUniform("xf2", xf[8], xf[9], xf[10], xf[11])
        shader.setFloatUniform("xf3", xf[12], xf[13], xf[14], xf[15])
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("twistRadius", TWIST_RADIUS_FRACTION * minOf(width, height))
        shader.setFloatUniform("alpha", alpha)
        shader.setFloatUniform("scrim", scrim)
    }
}
