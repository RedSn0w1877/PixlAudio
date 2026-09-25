package com.theveloper.pixelplay.presentation.lyrics

import androidx.compose.animation.core.EaseInOut
import kotlin.math.PI
import kotlin.math.sin

/**
 * Interlude dots (spec §1.6) as pure functions of the lyrics time `t` and the gap `[g0, g1)`
 * (`g0` = gap start, `g1` = next line start). Nothing is remembered between frames, so jumping
 * into a gap after a seek recomputes everything from `t` and the dots never animate from a stale
 * state.
 *
 * Phases:
 * - **Expand**, `g0 … g0+300`: row height 0 → full, group scale 0.1 → 1, alpha 0 → 1 (EaseInOut).
 * - **Fill**: dot k (0..2) goes 0.3 → 1.0 linearly over `[g0 + k·D/3, g0 + (k+1)·D/3]`.
 * - **Breathing** until `g1 − 1500`: scale `1 + 0.2·sin²(π·((t − g0) mod 5000)/5000)`.
 * - **Final pulse** `g1 − 1500 … g1 − 300`: scale `1.1 + 0.3·sin²(π·(t − (g1 − 1500))/1000)`,
 *   blended in from the breathing value over its first 250 ms so the switch never pops.
 * - **Collapse** `g1 − 300 … g1`: scale → 0.1, alpha → 0, height → 0 (EaseInOut).
 * - **Short gaps** (`g1 − g0 < 3000`): no breathing or pulse; all dots lit; expand then collapse.
 */
object InterludeTimeline {
    const val EXPAND_MS = 300L
    const val COLLAPSE_MS = 300L
    const val BREATH_PERIOD_MS = 5_000L
    const val BREATH_AMPLITUDE = 0.2f
    const val FINAL_PULSE_LEAD_MS = 1_500L
    const val FINAL_PULSE_PERIOD_MS = 1_000L
    const val FINAL_PULSE_BASE = 1.1f
    const val FINAL_PULSE_AMPLITUDE = 0.3f
    const val FINAL_PULSE_BLEND_MS = 250L
    const val SHORT_GAP_MS = 3_000L
    const val HIDDEN_SCALE = 0.1f
    const val DOT_UNLIT_ALPHA = 0.3f
    const val DOT_LIT_ALPHA = 1.0f
    const val DOT_COUNT = 3

    /** Dot diameter and gap in em, and the row's vertical margins (each side). */
    const val DOT_SIZE_EM = 0.3f
    const val DOT_GAP_EM = 0.15f
    const val ROW_MARGIN_EM = 0.4f

    /** True while `t` is inside the gap, i.e. the dots row needs to be drawn and animated. */
    fun isActive(tMs: Long, g0: Long, g1: Long): Boolean = g1 > g0 && tMs >= g0 && tMs < g1

    /**
     * Presence 0..1: the expand-in multiplied by the collapse-out. It is the row-height factor
     * (read in placement) and the group alpha.
     */
    fun presence(tMs: Long, g0: Long, g1: Long): Float {
        if (!isActive(tMs, g0, g1)) return 0f
        val expandIn = EaseInOut.transform(((tMs - g0).toFloat() / EXPAND_MS).coerceIn(0f, 1f))
        val collapse = EaseInOut.transform(((tMs - (g1 - COLLAPSE_MS)).toFloat() / COLLAPSE_MS).coerceIn(0f, 1f))
        return (expandIn * (1f - collapse)).coerceIn(0f, 1f)
    }

    /** Row height factor, read in placement so the rows below slide instead of re-measuring. */
    fun expand(tMs: Long, g0: Long, g1: Long): Float = presence(tMs, g0, g1)

    /** Group alpha. */
    fun alpha(tMs: Long, g0: Long, g1: Long): Float = presence(tMs, g0, g1)

    /** Group scale including the expand/collapse: `0.1 + (base − 0.1) × presence`. */
    fun scale(tMs: Long, g0: Long, g1: Long): Float {
        val p = presence(tMs, g0, g1)
        if (p <= 0f) return HIDDEN_SCALE
        return HIDDEN_SCALE + (baseScale(tMs, g0, g1) - HIDDEN_SCALE) * p
    }

    /** The breathing / final-pulse scale before expand and collapse are applied. */
    fun baseScale(tMs: Long, g0: Long, g1: Long): Float {
        if (g1 - g0 < SHORT_GAP_MS) return 1f
        val pulseStart = g1 - FINAL_PULSE_LEAD_MS
        val breath = breathing(tMs, g0)
        if (tMs < pulseStart) return breath
        val pulse = finalPulse(tMs, pulseStart)
        val w = EaseInOut.transform(((tMs - pulseStart).toFloat() / FINAL_PULSE_BLEND_MS).coerceIn(0f, 1f))
        return breath + (pulse - breath) * w
    }

    /** `1 + 0.2·sin²(π·((t − g0) mod 5000)/5000)`. */
    fun breathing(tMs: Long, g0: Long): Float {
        val phase = Math.floorMod(tMs - g0, BREATH_PERIOD_MS).toFloat() / BREATH_PERIOD_MS
        val s = sin(PI.toFloat() * phase)
        return 1f + BREATH_AMPLITUDE * s * s
    }

    /** `1.1 + 0.3·sin²(π·(t − pulseStart)/1000)`, pulsing between 1.1 and 1.4. */
    fun finalPulse(tMs: Long, pulseStartMs: Long): Float {
        val s = sin(PI.toFloat() * (tMs - pulseStartMs).toFloat() / FINAL_PULSE_PERIOD_MS)
        return FINAL_PULSE_BASE + FINAL_PULSE_AMPLITUDE * s * s
    }

    /** Alpha of dot [k] (0..2): 0.3 → 1.0 linearly across its third of the gap (all lit on short gaps). */
    fun dotAlpha(tMs: Long, g0: Long, g1: Long, k: Int): Float {
        val d = g1 - g0
        if (d <= 0L) return DOT_UNLIT_ALPHA
        if (d < SHORT_GAP_MS) return DOT_LIT_ALPHA
        val segment = d.toFloat() / DOT_COUNT
        val segStart = g0 + segment * k
        val f = ((tMs - segStart) / segment).coerceIn(0f, 1f)
        return DOT_UNLIT_ALPHA + (DOT_LIT_ALPHA - DOT_UNLIT_ALPHA) * f
    }
}
