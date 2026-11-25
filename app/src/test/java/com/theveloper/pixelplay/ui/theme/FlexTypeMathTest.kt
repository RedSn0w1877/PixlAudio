package com.theveloper.pixelplay.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pure maths behind the Google Sans Flex toolkit: quantisation (cache keys stay bounded and
 * stable), opsz-from-size, ramp stepping under spring overshoot, the fit-by-width solver and the
 * GRAD bump over artwork.
 */
class FlexTypeMathTest {

    // --- quantisation / cache keys ---------------------------------------------------------

    @Test
    fun `quantized clamps every axis to the font's ranges`() {
        val q = FlexSpec(weight = 5000f, width = 10f, opticalSize = 400f, grade = -20f, roundness = 250f, slant = 7f).quantized()
        assertThat(q.weight).isEqualTo(FlexAxis.WGHT_MAX)
        assertThat(q.width).isEqualTo(FlexAxis.WDTH_MIN)
        assertThat(q.opticalSize).isEqualTo(FlexAxis.OPSZ_MAX)
        assertThat(q.grade).isEqualTo(FlexAxis.GRAD_MIN)
        assertThat(q.roundness).isEqualTo(FlexAxis.ROND_MAX)
        assertThat(q.slant).isEqualTo(FlexAxis.SLNT_MAX)
    }

    @Test
    fun `quantized snaps to the grid`() {
        val q = FlexSpec(weight = 612.4f, width = 99.6f, opticalSize = 33.4f, grade = 12f, roundness = 97f, slant = -3.4f).quantized()
        assertThat(q.weight).isEqualTo(610f)
        assertThat(q.width).isEqualTo(100f)
        assertThat(q.opticalSize).isEqualTo(33f)
        assertThat(q.grade).isEqualTo(10f)
        assertThat(q.roundness).isEqualTo(95f)
        assertThat(q.slant).isEqualTo(-3f)
    }

    @Test
    fun `specs that draw the same share a cache key`() {
        val a = FlexSpec(weight = 600f, width = 100f, opticalSize = 48f)
        val b = FlexSpec(weight = 601.9f, width = 100.3f, opticalSize = 48.2f)
        assertThat(a.cacheKey).isEqualTo(b.cacheKey)
    }

    @Test
    fun `every axis changes the cache key`() {
        val base = FlexSpec(weight = 600f, width = 100f, opticalSize = 48f, grade = 20f, roundness = 50f, slant = -5f)
        val variants = listOf(
            base.copy(weight = 605f),
            base.copy(width = 101f),
            base.copy(opticalSize = 49f),
            base.copy(grade = 25f),
            base.copy(roundness = 55f),
            base.copy(slant = -6f),
        )
        val keys = (variants + base).map { it.cacheKey }.toSet()
        assertThat(keys).hasSize(variants.size + 1)
    }

    @Test
    fun `cache keys never collide across the extreme corners`() {
        val corners = buildList {
            for (w in listOf(FlexAxis.WGHT_MIN, FlexAxis.WGHT_MAX))
                for (wd in listOf(FlexAxis.WDTH_MIN, FlexAxis.WDTH_MAX))
                    for (o in listOf(FlexAxis.OPSZ_MIN, FlexAxis.OPSZ_MAX))
                        for (g in listOf(FlexAxis.GRAD_MIN, FlexAxis.GRAD_MAX))
                            for (r in listOf(FlexAxis.ROND_MIN, FlexAxis.ROND_MAX))
                                for (s in listOf(FlexAxis.SLNT_MIN, FlexAxis.SLNT_MAX))
                                    add(FlexSpec(w, wd, o, g, r, s))
        }
        assertThat(corners.map { it.cacheKey }.toSet()).hasSize(corners.size)
    }

    @Test
    fun `variation settings name all six axes with quantised values`() {
        val settings = FlexSpec(weight = 702f, width = 112.2f, opticalSize = 14f, grade = 40f, roundness = 100f, slant = 0f)
            .toVariationSettings()
        assertThat(settings).isEqualTo("'wght' 700.0, 'wdth' 112.0, 'opsz' 14.0, 'GRAD' 40.0, 'ROND' 100.0, 'slnt' 0.0")
    }

    @Test
    fun `lerp moves every axis`() {
        val mid = FlexPresets.NavLabel.lerp(FlexPresets.NavLabelSelected, 0.5f)
        assertThat(mid.weight).isEqualTo(610f)
        assertThat(mid.width).isEqualTo(106f)
    }

    // --- optical size ------------------------------------------------------------------------

    @Test
    fun `opsz follows the drawn size, clamped to the axis`() {
        assertThat(FlexMath.opticalSizeFor(48f)).isEqualTo(48f)
        assertThat(FlexMath.opticalSizeFor(11.4f)).isEqualTo(11f)
        assertThat(FlexMath.opticalSizeFor(3f)).isEqualTo(FlexAxis.OPSZ_MIN)
        assertThat(FlexMath.opticalSizeFor(200f)).isEqualTo(FlexAxis.OPSZ_MAX)
        assertThat(FlexMath.opticalSizeFor(Float.NaN)).isEqualTo(FlexAxis.OPSZ_DEFAULT)
        assertThat(FlexSpec().atSize(57f).opticalSize).isEqualTo(57f)
    }

    // --- ramps --------------------------------------------------------------------------------

    @Test
    fun `step index covers the ramp end to end`() {
        assertThat(FlexMath.stepIndex(0f, 7)).isEqualTo(0)
        assertThat(FlexMath.stepIndex(1f, 7)).isEqualTo(6)
        assertThat(FlexMath.stepIndex(0.5f, 7)).isEqualTo(3)
        assertThat(FlexMath.stepIndex(0.09f, 7)).isEqualTo(1) // 0.54 rounds up
    }

    @Test
    fun `spring overshoot cannot index out of the ramp`() {
        assertThat(FlexMath.stepIndex(1.18f, 7)).isEqualTo(6)
        assertThat(FlexMath.stepIndex(-0.2f, 7)).isEqualTo(0)
        assertThat(FlexMath.stepIndex(Float.NaN, 7)).isEqualTo(0)
        assertThat(FlexMath.stepIndex(0.7f, 1)).isEqualTo(0)
    }

    @Test
    fun `step fraction is the inverse of step index`() {
        for (i in 0 until 8) {
            assertThat(FlexMath.stepIndex(FlexMath.stepFraction(i, 8), 8)).isEqualTo(i)
        }
    }

    @Test
    fun `continuous scale lands exactly on each step and is smooth between`() {
        // On a step the stepped size is exact: no correction.
        assertThat(FlexMath.continuousScale(0f, 48f, 22f, 8)).isEqualTo(1f)
        assertThat(FlexMath.continuousScale(1f, 48f, 22f, 8)).isEqualTo(1f)
        // Between steps the visual size equals the continuous size.
        val f = 0.3f
        val idx = FlexMath.stepIndex(f, 8)
        val stepSize = FlexMath.lerp(48f, 22f, FlexMath.stepFraction(idx, 8))
        val visual = stepSize * FlexMath.continuousScale(f, 48f, 22f, 8)
        assertThat(visual).isWithin(1e-3f).of(FlexMath.lerp(48f, 22f, f))
    }

    // --- fit by width -------------------------------------------------------------------------

    @Test
    fun `short titles take the widest cut`() {
        assertThat(FlexMath.solveSingleLineWidth(150f, 260f, 75f, 125f, available = 300f)).isEqualTo(125f)
    }

    @Test
    fun `titles too long for the narrowest single line return null`() {
        assertThat(FlexMath.solveSingleLineWidth(400f, 660f, 75f, 125f, available = 360f)).isNull()
        assertThat(FlexMath.solveSingleLineWidth(10f, 20f, 75f, 125f, available = 0f)).isNull()
    }

    @Test
    fun `in-between titles condense just enough, floored toward fitting`() {
        // Linear model: 300 px at 75, 400 px at 125 -> 2 px per unit; 351 px available -> 100.5 -> 100.
        val axis = FlexMath.solveSingleLineWidth(300f, 400f, 75f, 125f, available = 351f)
        assertThat(axis).isEqualTo(100f)
        val modelWidth = 300f + (axis!! - 75f) * 2f
        assertThat(modelWidth).isAtMost(351f)
    }

    @Test
    fun `width-insensitive text that fits keeps the widest cut`() {
        assertThat(FlexMath.solveSingleLineWidth(300f, 300f, 75f, 125f, available = 300f)).isEqualTo(125f)
        assertThat(FlexMath.solveSingleLineWidth(300f, 290f, 75f, 125f, available = 295f)).isEqualTo(125f)
    }

    @Test
    fun `multi-line candidates run widest first and end on the minimum`() {
        assertThat(FlexFitPolicy().multiLineCandidates().toList())
            .containsExactly(100f, 95f, 90f, 85f, 80f, 75f, 70f, 65f, 60f, 55f, 50f, 45f, 40f, 35f, 30f, 25f).inOrder()
        assertThat(FlexFitPolicy(minWidth = 75f, maxWidth = 125f, step = 4f).multiLineCandidates().toList())
            .containsExactly(100f, 96f, 92f, 88f, 84f, 80f, 76f, 75f).inOrder()
    }

    @Test
    fun `snapDown lands on the single-line grid and never widens`() {
        val policy = FlexFitPolicy()
        assertThat(policy.snapDown(118.9f)).isEqualTo(115f)
        assertThat(policy.snapDown(80f)).isEqualTo(80f)
        assertThat(policy.snapDown(20f)).isEqualTo(25f)
        assertThat(policy.snapDown(150.5f)).isEqualTo(150f)
        assertThat(policy.snapDown(160f)).isEqualTo(151f)
        for (raw in 25..151) {
            val snapped = policy.snapDown(raw.toFloat())
            assertThat(snapped).isAtMost(raw.toFloat())
            assertThat(policy.widthGrid().toList()).contains(snapped)
        }
    }

    @Test
    fun `width grid holds every width a fit can use`() {
        val policy = FlexFitPolicy()
        val grid = policy.widthGrid().toList()
        // The font's whole wdth range (25..151) in steps of 5, plus 151 itself: 27 instances.
        assertThat(grid.first()).isEqualTo(FlexAxis.WDTH_MIN)
        assertThat(grid).contains(FlexAxis.WDTH_MAX)
        assertThat(grid).hasSize(27)
        assertThat(grid.max()).isEqualTo(FlexAxis.WDTH_MAX)
        assertThat(policy.widthGrid().toList()).containsAtLeastElementsIn(policy.multiLineCandidates().toList())
    }

    @Test
    fun `first fitting finds the widest candidate that fits`() {
        val candidates = FlexFitPolicy().multiLineCandidates()
        var probes = 0
        val found = FlexMath.firstFitting(candidates) { probes++; it <= 87f }
        assertThat(candidates[found]).isEqualTo(85f)
        assertThat(probes).isAtMost(5)
        assertThat(FlexMath.firstFitting(candidates) { false }).isEqualTo(-1)
        assertThat(FlexMath.firstFitting(candidates) { true }).isEqualTo(0)
    }

    @Test
    fun `house style is flat and the fit uses the font's whole width range`() {
        assertThat(FlexAxis.ROND_HOUSE).isEqualTo(0f)
        assertThat(FlexSpec().roundness).isEqualTo(0f)
        assertThat(FlexPresets.Chip.roundness).isEqualTo(0f)
        assertThat(FlexFitPolicy().minWidth).isEqualTo(25f)
        assertThat(FlexFitPolicy().maxWidth).isEqualTo(151f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `policy rejects an inverted range`() {
        FlexFitPolicy(minWidth = 110f, multiLineMaxWidth = 100f)
    }

    // --- GRAD over artwork --------------------------------------------------------------------

    @Test
    fun `grade rises with backdrop brightness for light text only`() {
        assertThat(FlexMath.gradeForBackdrop(0.1f, lightText = true)).isEqualTo(0f)
        assertThat(FlexMath.gradeForBackdrop(0.5f, lightText = true)).isEqualTo(30f)
        assertThat(FlexMath.gradeForBackdrop(0.95f, lightText = true)).isEqualTo(60f)
        assertThat(FlexMath.gradeForBackdrop(0.95f, lightText = false)).isEqualTo(0f)
        assertThat(FlexMath.gradeForBackdrop(Float.NaN, lightText = true)).isEqualTo(0f)
    }

    @Test
    fun `grade is snapped so a changing backdrop reuses few typefaces`() {
        val grades = (0..100).map { FlexMath.gradeForBackdrop(it / 100f, lightText = true) }.toSet()
        assertThat(grades.size).isAtMost(7)
        grades.forEach { assertThat(it % 10f).isEqualTo(0f) }
    }
}
