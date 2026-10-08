package com.theveloper.pixelplay.data.tais.dj

import com.theveloper.pixelplay.data.ai.local.OnDevicePrompts
import com.theveloper.pixelplay.data.ai.local.TokenBudget

/**
 * What Taizo remembers of the conversation: the latest user/assistant turns, each trimmed to
 * [MAX_TURN_CHARS], kept within [budgetTokens] by dropping the oldest. It lives in the chat
 * sheet's ViewModel, so it lasts as long as the chat does and is gone after an app restart
 * (owner decision 5). Not thread-safe; the ViewModel touches it from the main thread only.
 */
class TaizoMemory(private val budgetTokens: Int = OnDevicePrompts.TAIZO_MEMORY_TOKENS) {

    data class Turn(val user: String, val assistant: String)

    private val turns = ArrayDeque<Turn>()

    val size: Int get() = turns.size

    fun add(user: String, assistant: String) {
        turns.addLast(Turn(user.trim().take(MAX_TURN_CHARS), assistant.trim().take(MAX_TURN_CHARS)))
        while (turns.size > 1 && TokenBudget.estimate(render()) > budgetTokens) turns.removeFirst()
        if (turns.size == 1 && TokenBudget.estimate(render()) > budgetTokens) {
            val only = turns.removeFirst()
            turns.addLast(Turn(TokenBudget.clamp(only.user, budgetTokens / 3), TokenBudget.clamp(only.assistant, budgetTokens / 2)))
        }
    }

    fun clear() = turns.clear()

    /** "User: …\nTaizo: …" lines, oldest first; empty when nothing was said yet. */
    fun render(): String = turns.joinToString("\n") { "User: ${it.user}\nTaizo: ${it.assistant}" }

    companion object {
        const val MAX_TURN_CHARS = 300
    }
}
