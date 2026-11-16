package com.theveloper.pixelplay.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPillMathTest {

    private val total = 1000
    private val gap = 30
    private val small = 200

    @Test
    fun bigPill_takesWhatSmallAndGapLeave() {
        assertEquals(770, SplitPillMath.bigPillWidth(total, gap, small))
        // Never narrower than the small pill, even on an absurdly narrow window.
        assertEquals(200, SplitPillMath.bigPillWidth(300, gap, small))
    }

    @Test
    fun leftPill_isBigAtRestAndSmallOnSearch() {
        assertEquals(770, SplitPillMath.leftPillWidth(total, gap, small, 0f))
        assertEquals(200, SplitPillMath.leftPillWidth(total, gap, small, 1f))
        assertEquals(485, SplitPillMath.leftPillWidth(total, gap, small, 0.5f))
    }

    @Test
    fun pills_alwaysFillTheRowExactly() {
        for (step in 0..20) {
            val p = step / 20f
            val left = SplitPillMath.leftPillWidth(total, gap, small, p)
            val right = SplitPillMath.rightPillWidth(total, gap, left)
            assertEquals(total, left + gap + right)
        }
    }

    @Test
    fun springOvershoot_neverCrushesEitherPill() {
        val minSmall = (small * 0.8f).toInt()
        val overshootPast = SplitPillMath.leftPillWidth(total, gap, small, 1.6f)
        assertEquals(minSmall, overshootPast)
        val overshootBack = SplitPillMath.leftPillWidth(total, gap, small, -0.6f)
        assertEquals(total - gap - minSmall, overshootBack)
        assertTrue(SplitPillMath.rightPillWidth(total, gap, overshootBack) >= minSmall)
    }

    @Test
    fun selectedTab_getsTheExtraWeight() {
        val avail = 480f
        val home = SplitPillMath.tabSlotWidth(0, 2, avail, 0f)
        val library = SplitPillMath.tabSlotWidth(1, 2, avail, 0f)
        assertEquals(280f, home, 0.01f) // 480 × 1.4 / 2.4
        assertEquals(200f, library, 0.01f)
        assertEquals(avail, home + library, 0.01f)
    }

    @Test
    fun tabSlots_swapSmoothlyAndStillFill() {
        val avail = 480f
        for (step in 0..10) {
            val s = step / 10f
            val sum = SplitPillMath.tabSlotWidth(0, 2, avail, s) + SplitPillMath.tabSlotWidth(1, 2, avail, s)
            assertEquals(avail, sum, 0.01f)
        }
        assertEquals(200f, SplitPillMath.tabSlotWidth(0, 2, avail, 1f), 0.01f)
        assertEquals(280f, SplitPillMath.tabSlotWidth(1, 2, avail, 1f), 0.01f)
    }

    @Test
    fun tabSlotStart_addsWidthsAndGaps() {
        val avail = 480f
        assertEquals(0f, SplitPillMath.tabSlotStart(0, 2, avail, 8f, 0f), 0.01f)
        assertEquals(288f, SplitPillMath.tabSlotStart(1, 2, avail, 8f, 0f), 0.01f)
    }

    @Test
    fun indicator_coversTheSelectedSlotAtRest() {
        val avail = 480f
        assertEquals(0f, SplitPillMath.indicatorStart(2, avail, 8f, 0f), 0.01f)
        assertEquals(280f, SplitPillMath.indicatorWidth(2, avail, 0f), 0.01f)
        assertEquals(208f, SplitPillMath.indicatorStart(2, avail, 8f, 1f), 0.01f)
        assertEquals(280f, SplitPillMath.indicatorWidth(2, avail, 1f), 0.01f)
    }

    @Test
    fun indicator_overshootsALittleWithTheSpring() {
        val avail = 480f
        val past = SplitPillMath.indicatorStart(2, avail, 8f, 1.1f)
        assertTrue("indicator should travel past the last slot while overshooting", past > 208f)
        val before = SplitPillMath.indicatorStart(2, avail, 8f, -0.1f)
        assertTrue(before < 0f)
    }

    @Test
    fun singleTab_isHarmless() {
        assertEquals(0f, SplitPillMath.indicatorStart(1, 300f, 8f, 0f), 0.01f)
        assertEquals(300f, SplitPillMath.indicatorWidth(1, 300f, 0f), 0.01f)
        assertEquals(300f, SplitPillMath.tabSlotWidth(0, 1, 300f, 0f), 0.01f)
    }

    @Test
    fun tabEmphasis_isOneOnlyOnTheSelectedTab() {
        assertEquals(1f, SplitPillMath.tabEmphasis(0, 0f), 0f)
        assertEquals(0f, SplitPillMath.tabEmphasis(1, 0f), 0f)
        assertEquals(0.5f, SplitPillMath.tabEmphasis(1, 0.5f), 0f)
        assertEquals(0f, SplitPillMath.tabEmphasis(0, 1.3f), 0f)
    }

    @Test
    fun crossfade_handsOverWithoutAGap() {
        assertEquals(1f, SplitPillMath.fadeOut(0f), 0f)
        assertEquals(0f, SplitPillMath.fadeOut(0.45f), 0f)
        assertEquals(0f, SplitPillMath.fadeIn(0.35f), 0f)
        assertEquals(1f, SplitPillMath.fadeIn(1f), 0f)
        // Overshoot beyond 1 or below 0 stays a valid alpha.
        assertEquals(1f, SplitPillMath.fadeIn(1.2f), 0f)
        assertEquals(1f, SplitPillMath.fadeOut(-0.2f), 0f)
    }

    @Test
    fun imeLift_putsThePillAboveTheKeyboard() {
        assertEquals(0f, SplitPillMath.imeLift(0f, 100f, 20f), 0f)
        // Keyboard shorter than where the pill already sits: nothing to do.
        assertEquals(0f, SplitPillMath.imeLift(60f, 100f, 20f), 0f)
        assertEquals(820f, SplitPillMath.imeLift(900f, 100f, 20f), 0f)
    }

    @Test
    fun cornerRadius_isCappedAtAFullPill() {
        assertEquals(32f, SplitPillMath.pillCornerRadius(32f, 68f), 0f)
        assertEquals(34f, SplitPillMath.pillCornerRadius(60f, 68f), 0f)
        assertEquals(0f, SplitPillMath.pillCornerRadius(-4f, 68f), 0f)
    }
}
