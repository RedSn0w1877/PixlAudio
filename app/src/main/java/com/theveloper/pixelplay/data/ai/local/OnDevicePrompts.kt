package com.theveloper.pixelplay.data.ai.local

import com.theveloper.pixelplay.data.ai.AiSystemPromptType

/**
 * Compact instructions for the on-device models. They replace [com.theveloper.pixelplay.data.ai.AiSystemPromptEngine]'s
 * layered cloud prompt (persona + strategy + few-shot + constraints, ~1.3-2k characters) on the
 * on-device route only: ML Kit recommends system instructions under 150 words, and every token
 * spent here comes out of a 4k window. No few-shot except the greeting's single example.
 *
 * Budgets (estimated tokens, see [TokenBudget]):
 * | Feature             | Input                                             | Output |
 * |---------------------|---------------------------------------------------|--------|
 * | Greeting            | ~60                                               | 40     |
 * | Insight             | ~80                                               | 160    |
 * | Taizo intro         | ~60                                               | 32     |
 * | Taizo chat          | memory <= 700 + library facts <= 500 + question <= 300 | 320 |
 * | Playlist plan       | request <= 400 + vocabulary <= 450                | 160    |
 * | Playlist order      | <= 40 numbered lines (~560)                        | 200    |
 * | Translation chunk   | <= 600                                            | 1,024  |
 */
object OnDevicePrompts {
    const val PERSONA_MAX_CHARS = 200

    const val GREETING_MAX_OUTPUT = 40
    const val INSIGHT_MAX_OUTPUT = 160
    const val TAIZO_INTRO_MAX_OUTPUT = 32
    const val TAIZO_CHAT_MAX_OUTPUT = 320
    const val PLAN_MAX_OUTPUT = 160
    const val ORDER_MAX_OUTPUT = 200
    const val TRANSLATION_MAX_OUTPUT = TokenBudget.MAX_OUTPUT_TOKENS
    const val GENERAL_MAX_OUTPUT = 512

    const val TAIZO_MEMORY_TOKENS = 700
    const val TAIZO_FACTS_TOKENS = 500
    const val TAIZO_QUESTION_TOKENS = 300
    const val PLAN_REQUEST_TOKENS = 400
    const val PLAN_VOCABULARY_TOKENS = 450
    const val TRANSLATION_CHUNK_TOKENS = 600
    const val ORDER_MAX_SONGS = 40

    val GREETING = """
        You write the one-line greeting at the top of a music app's home screen.
        Use the time of day and, when given, the listener's top artist or genre.
        Reply with one warm, casual sentence under 90 characters. No quotes, no emoji.
        Example input: time_of_day=morning, top_artist=Radiohead
        Example reply: Morning — feels like a Radiohead kind of day.
    """.trimIndent()

    val INSIGHT = """
        You write a short listening insight for a music app's home screen.
        Use only the numbers and names you are given. Reply with 2-3 friendly sentences of plain
        text. No quotes, no emoji, no lists.
    """.trimIndent()

    val TAIZO_INTRO = """
        You are Taizo, the DJ inside a music app. Write one short, upbeat sentence (at most 12
        words) introducing songs that were just found for the listener. No quotes, no emoji.
    """.trimIndent()

    val PLAYLIST_PLAN = """
        You plan playlists from a listener's own music library.
        Read the request and choose matching values from the genres and artists lists only.
        Reply with one JSON object and nothing else:
        {"genres":[up to 4],"artists":[up to 6],"moods":[up to 3],"energy":1-5,"discovery":1-5,"eraFrom":year or null,"eraTo":year or null,"keywords":[up to 4]}
        energy: 1 calm to 5 intense. discovery: 1 familiar favourites to 5 songs rarely played.
        moods use these words: chill, energetic, sad, happy, romantic, workout, party, focus, sleep.
    """.trimIndent()

    val PLAYLIST_ORDER = """
        You order a playlist so it flows well from start to finish.
        Reply with every song number exactly once, in the best listening order, separated by
        commas. Numbers only, nothing else.
    """.trimIndent()

    fun translation(targetLanguage: String): String = """
        You translate song lyrics into $targetLanguage.
        Translate each numbered line on its own, keeping its meaning and tone.
        Reply with the same numbers, one line each, written as: number. translation
        No notes or explanations. If the lines are already in $targetLanguage, reply only:
        ALREADY_IN_TARGET_LANGUAGE
    """.trimIndent()

    fun taizoChat(persona: String): String = buildString {
        append(
            """
            You are Taizo, the friendly DJ inside the PixlAudio music app.
            Reply in 1-4 short sentences of plain, warm text. No markdown, no lists unless asked.
            Use library_facts for questions about the listener's own music; if the facts don't
            answer it, say you're not sure. You can't play or queue songs from a reply; suggest
            saying "play some" plus a mood or genre instead.
            """.trimIndent()
        )
        val clamped = clampPersona(persona)
        if (clamped.isNotBlank()) {
            append("\nPersona: ")
            append(clamped)
        }
    }

    val GENERAL = """
        You are a helpful assistant inside a music app. Follow the task exactly and reply with
        only what it asks for.
    """.trimIndent()

    /** The instruction for a generic [com.theveloper.pixelplay.data.ai.AiHandler] call on the on-device route. */
    fun instructionFor(type: AiSystemPromptType, persona: String, maxOutputTokens: Int?): String = when (type) {
        AiSystemPromptType.GREETING ->
            if ((maxOutputTokens ?: GREETING_MAX_OUTPUT) > GREETING_MAX_OUTPUT) INSIGHT else GREETING
        AiSystemPromptType.TAIZO_CHAT -> taizoChat(persona)
        AiSystemPromptType.PLAYLIST, AiSystemPromptType.DAILY_MIX -> PLAYLIST_PLAN
        else -> GENERAL
    }

    fun maxOutputFor(type: AiSystemPromptType): Int = when (type) {
        AiSystemPromptType.GREETING -> GREETING_MAX_OUTPUT
        AiSystemPromptType.TAIZO_CHAT -> TAIZO_CHAT_MAX_OUTPUT
        AiSystemPromptType.PLAYLIST, AiSystemPromptType.DAILY_MIX -> PLAN_MAX_OUTPUT
        else -> GENERAL_MAX_OUTPUT
    }

    fun clampPersona(persona: String): String {
        val trimmed = persona.trim()
        // The stock cloud persona ("Vibe-Engine, a professional music curator…") would only
        // fight Taizo's own voice and costs ~50 tokens; send the user's own text only.
        if (trimmed.isEmpty() || trimmed == DEFAULT_PERSONA_MARKER || trimmed.startsWith(DEFAULT_PERSONA_MARKER)) return ""
        return if (trimmed.length <= PERSONA_MAX_CHARS) trimmed else trimmed.take(PERSONA_MAX_CHARS).trimEnd() + "…"
    }

    private const val DEFAULT_PERSONA_MARKER = "You are 'Vibe-Engine'"

    /** Every fixed instruction, for the word-count test. */
    internal fun allInstructions(): List<String> = listOf(
        GREETING, INSIGHT, TAIZO_INTRO, PLAYLIST_PLAN, PLAYLIST_ORDER, GENERAL,
        translation("Vietnamese"), taizoChat("x".repeat(PERSONA_MAX_CHARS)),
    )
}
