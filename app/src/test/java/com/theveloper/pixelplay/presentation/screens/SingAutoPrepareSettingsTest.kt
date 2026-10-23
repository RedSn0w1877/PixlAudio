package com.theveloper.pixelplay.presentation.screens

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The numbers behind Settings › Sing › Prepare ahead: the $0.25-step budget slider and the 1-30 daily limit. */
class SingAutoPrepareSettingsTest {
    @Test
    fun `the default budget of one dollar sits on the fourth step`() {
        assertEquals(4f, SingAutoPrepareSettings.budgetStep(1_000_000L))
        assertEquals(1_000_000L, SingAutoPrepareSettings.budgetMicroUsd(4f))
    }

    @Test
    fun `every quarter dollar from zero to three dollars survives a round trip`() {
        for (step in 0..12) {
            val micro = step * 250_000L
            assertEquals(step.toFloat(), SingAutoPrepareSettings.budgetStep(micro))
            assertEquals(micro, SingAutoPrepareSettings.budgetMicroUsd(step.toFloat()))
        }
        assertEquals(0L, SingAutoPrepareSettings.budgetMicroUsd(0f))
        assertEquals(3_000_000L, SingAutoPrepareSettings.budgetMicroUsd(12f))
    }

    @Test
    fun `the budget slider has a stop at each quarter dollar`() {
        // 12 quarter dollars between $0.00 and $3.00 means 11 stops strictly between the ends.
        assertEquals(11, SingAutoPrepareSettings.BUDGET_STOPS_BETWEEN)
        assertEquals(
            3_000_000L,
            SingAutoPrepareSettings.BUDGET_MAX_STEP * SingAutoPrepareSettings.BUDGET_STEP_MICRO_USD,
        )
    }

    @Test
    fun `an off-grid budget snaps to the nearest quarter dollar`() {
        assertEquals(4f, SingAutoPrepareSettings.budgetStep(1_100_000L))
        assertEquals(5f, SingAutoPrepareSettings.budgetStep(1_130_000L))
        assertEquals(1f, SingAutoPrepareSettings.budgetStep(125_000L))
        assertEquals(0f, SingAutoPrepareSettings.budgetStep(124_999L))
        assertEquals(1_250_000L, SingAutoPrepareSettings.budgetMicroUsd(4.6f))
        assertEquals(1_000_000L, SingAutoPrepareSettings.budgetMicroUsd(4.4f))
    }

    @Test
    fun `a budget outside the slider is held to its ends`() {
        assertEquals(0f, SingAutoPrepareSettings.budgetStep(-500_000L))
        assertEquals(12f, SingAutoPrepareSettings.budgetStep(10_000_000L))
        assertEquals(0L, SingAutoPrepareSettings.budgetMicroUsd(-3f))
        assertEquals(3_000_000L, SingAutoPrepareSettings.budgetMicroUsd(40f))
    }

    @Test
    fun `spendable money is the budget until the monthly cap is lower`() {
        assertEquals(1_000_000L, SingAutoPrepareSettings.spendableMicroUsd(1_000_000L, 3_000_000L))
        assertEquals(500_000L, SingAutoPrepareSettings.spendableMicroUsd(2_000_000L, 500_000L))
        assertEquals(0L, SingAutoPrepareSettings.spendableMicroUsd(1_000_000L, 0L))
        assertEquals(0L, SingAutoPrepareSettings.spendableMicroUsd(1_000_000L, -250_000L))
        assertEquals(0L, SingAutoPrepareSettings.spendableMicroUsd(0L, 3_000_000L))
    }

    @Test
    fun `the daily limit rounds to whole songs between 1 and 30`() {
        assertEquals(12, SingAutoPrepareSettings.dailyCap(12f))
        assertEquals(12, SingAutoPrepareSettings.dailyCap(11.6f))
        assertEquals(11, SingAutoPrepareSettings.dailyCap(11.4f))
        assertEquals(1, SingAutoPrepareSettings.dailyCap(0f))
        assertEquals(1, SingAutoPrepareSettings.dailyCap(-4f))
        assertEquals(30, SingAutoPrepareSettings.dailyCap(99f))
    }

    @Test
    fun `a stored daily limit outside 1 to 30 is shown at the nearest end`() {
        assertEquals(12f, SingAutoPrepareSettings.dailyCapValue(12))
        assertEquals(1f, SingAutoPrepareSettings.dailyCapValue(0))
        assertEquals(1f, SingAutoPrepareSettings.dailyCapValue(-7))
        assertEquals(30f, SingAutoPrepareSettings.dailyCapValue(500))
    }

    @Test
    fun `the daily limit slider has a stop at each whole song`() {
        // 29 steps between 1 and 30 means 28 stops strictly between the ends.
        assertEquals(28, SingAutoPrepareSettings.DAILY_CAP_STOPS_BETWEEN)
    }
}
