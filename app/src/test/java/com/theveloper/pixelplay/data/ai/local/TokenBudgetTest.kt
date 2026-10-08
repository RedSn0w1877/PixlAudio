package com.theveloper.pixelplay.data.ai.local

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.ai.AiSystemPromptType
import org.junit.jupiter.api.Test

class TokenBudgetTest {

    @Test
    fun `latin text costs about one token per three characters`() {
        val text = "Play something calm for a rainy afternoon"
        val estimate = TokenBudget.estimate(text)
        assertThat(estimate).isAtLeast(text.length / 4)
        assertThat(estimate).isAtMost(text.length / 2)
        assertThat(TokenBudget.estimate("")).isEqualTo(0)
    }

    @Test
    fun `CJK and Vietnamese count about one token per letter`() {
        val japanese = "君の名前を呼んでいる"
        assertThat(TokenBudget.estimate(japanese)).isAtLeast(japanese.length)

        val korean = "사랑해요 오늘도"
        assertThat(TokenBudget.estimate(korean)).isAtLeast(7)

        val vietnamese = "Em của ngày hôm qua"
        val plainLatin = "Em cua ngay hom qua"
        assertThat(TokenBudget.estimate(vietnamese)).isGreaterThan(TokenBudget.estimate(plainLatin))
    }

    @Test
    fun `shrinkToFit keeps the longest prefix within the budget`() {
        val items = (1..400).map { "Song number $it — Some Artist · indie" }
        val kept = TokenBudget.shrinkToFit(items, TokenBudget.MAX_INPUT_TOKENS) { it }
        assertThat(kept).isNotEmpty()
        assertThat(kept.size).isLessThan(items.size)
        assertThat(TokenBudget.estimate(kept.joinToString("\n"))).isAtMost(TokenBudget.MAX_INPUT_TOKENS)
        assertThat(kept).isEqualTo(items.take(kept.size))
    }

    @Test
    fun `shrinkToFit always keeps at least one item`() {
        val kept = TokenBudget.shrinkToFit(listOf("x".repeat(10_000), "y"), 10) { it }
        assertThat(kept).hasSize(1)
    }

    @Test
    fun `clamp cuts long text to the budget`() {
        val long = "word ".repeat(5_000)
        val clamped = TokenBudget.clamp(long, 300)
        assertThat(TokenBudget.estimate(clamped)).isAtMost(300)
        assertThat(clamped).isNotEmpty()
        assertThat(TokenBudget.clamp("short", 300)).isEqualTo("short")
    }

    @Test
    fun `every on-device instruction stays under 150 words`() {
        for (instruction in OnDevicePrompts.allInstructions()) {
            assertThat(TokenBudget.wordCount(instruction)).isAtMost(TokenBudget.MAX_INSTRUCTION_WORDS)
        }
        for (type in AiSystemPromptType.entries) {
            val instruction = OnDevicePrompts.instructionFor(type, "A".repeat(500), null)
            assertThat(TokenBudget.wordCount(instruction)).isAtMost(TokenBudget.MAX_INSTRUCTION_WORDS)
        }
    }

    @Test
    fun `the stock cloud persona is not sent to the small model, a custom one is clamped`() {
        assertThat(OnDevicePrompts.clampPersona(com.theveloper.pixelplay.data.preferences.AiPreferencesRepository.DEFAULT_SYSTEM_PROMPT)).isEmpty()
        assertThat(OnDevicePrompts.clampPersona("A chill late-night radio host")).isEqualTo("A chill late-night radio host")
        assertThat(OnDevicePrompts.clampPersona("x".repeat(400)).length).isAtMost(OnDevicePrompts.PERSONA_MAX_CHARS + 1)
    }

    @Test
    fun `the insight gets the insight instruction, the headline the greeting one`() {
        assertThat(OnDevicePrompts.instructionFor(AiSystemPromptType.GREETING, "", OnDevicePrompts.INSIGHT_MAX_OUTPUT))
            .isEqualTo(OnDevicePrompts.INSIGHT)
        assertThat(OnDevicePrompts.instructionFor(AiSystemPromptType.GREETING, "", OnDevicePrompts.GREETING_MAX_OUTPUT))
            .isEqualTo(OnDevicePrompts.GREETING)
    }
}
