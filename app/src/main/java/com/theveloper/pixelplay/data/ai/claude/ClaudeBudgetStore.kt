package com.theveloper.pixelplay.data.ai.claude

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** This month's spending on the built-in Claude key, and the cap. */
data class ClaudeBudgetState(
    val monthKey: String,
    val spentMicros: Long,
    val capMicros: Long,
) {
    val exhausted: Boolean get() = ClaudeBudgetRules.isExhausted(spentMicros, capMicros)
    val spentUsd: Double get() = ClaudePricing.microsToUsd(spentMicros)
    val capUsd: Double get() = ClaudePricing.microsToUsd(capMicros)
}

/**
 * The per-device monthly budget for the built-in Claude key (default $50, Developer mode
 * $0–$200). Device-local on purpose: its own SharedPreferences file, never part of the settings
 * DataStore that .pxpl backups carry, so a restore on another phone doesn't bring this phone's
 * spending along. Counts only the built-in key; "Cloud assistants" with the user's own keys are
 * theirs to pay for.
 */
@Singleton
class ClaudeBudgetStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _state = MutableStateFlow(load(System.currentTimeMillis()))
    val state: StateFlow<ClaudeBudgetState> = _state.asStateFlow()

    /** Whether the built-in key may be used right now (the month rolls over here too). */
    fun canSpend(nowMs: Long = System.currentTimeMillis()): Boolean = !current(nowMs).exhausted

    fun current(nowMs: Long = System.currentTimeMillis()): ClaudeBudgetState = synchronized(lock) {
        val month = ClaudeBudgetRules.monthKey(nowMs)
        val state = _state.value
        if (state.monthKey == month) return state
        val reset = state.copy(monthKey = month, spentMicros = 0L)
        persist(reset)
        reset
    }

    fun record(usage: ClaudeUsage, nowMs: Long = System.currentTimeMillis()) {
        val cost = ClaudePricing.costMicros(usage)
        if (cost <= 0L) return
        synchronized(lock) {
            val state = current(nowMs)
            persist(state.copy(spentMicros = state.spentMicros + cost))
        }
    }

    fun setMonthlyCapUsd(usd: Double) {
        val capMicros = ClaudePricing.usdToMicros(ClaudeBudgetRules.clampCapUsd(usd))
        synchronized(lock) {
            persist(current().copy(capMicros = capMicros))
        }
    }

    private fun persist(state: ClaudeBudgetState) {
        prefs.edit()
            .putString(KEY_MONTH, state.monthKey)
            .putLong(KEY_SPENT, state.spentMicros)
            .putLong(KEY_CAP, state.capMicros)
            .apply()
        _state.value = state
    }

    private fun load(nowMs: Long): ClaudeBudgetState {
        val month = ClaudeBudgetRules.monthKey(nowMs)
        val storedMonth = prefs.getString(KEY_MONTH, null)
        val cap = prefs.getLong(KEY_CAP, ClaudePricing.usdToMicros(ClaudeConfig.DEFAULT_MONTHLY_CAP_USD))
        val spent = if (storedMonth == month) prefs.getLong(KEY_SPENT, 0L) else 0L
        return ClaudeBudgetState(monthKey = month, spentMicros = spent, capMicros = cap)
    }

    private companion object {
        const val PREFS_NAME = "claude_budget"
        const val KEY_MONTH = "month"
        const val KEY_SPENT = "spent_micros"
        const val KEY_CAP = "cap_micros"
    }
}
