package com.theveloper.pixelplay.data.ai.local

import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Translate via AI" on the on-device route: chunked, line by line, with the timestamps kept by
 * the app ([LyricsAiChunker]). Returns the same two-lines-per-timestamp LRC the cloud prompt
 * produces, or [LyricsAiChunker.ALREADY_IN_TARGET] when the song is already in [targetLanguage].
 */
@Singleton
class OnDeviceLyricsTranslator @Inject constructor(
    private val localAi: LocalAi,
) {
    suspend fun translate(lyrics: String, targetLanguage: String, temperature: Float): String {
        val lines = LyricsAiChunker.parse(lyrics)
        val texts = LyricsAiChunker.translatableTexts(lines)
        if (texts.isEmpty()) return lyrics

        val instruction = OnDevicePrompts.translation(targetLanguage)
        val translations = HashMap<String, String>()
        val chunks = LyricsAiChunker.chunk(texts)
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            val result = translateChunk(instruction, chunk, temperature, allowSplit = true)
            if (result == null) {
                // Only the first chunk decides "already in your language"; a later chunk saying so
                // just means those lines (a chorus in English, say) stay as they are.
                if (chunkIndex == 0) return LyricsAiChunker.ALREADY_IN_TARGET
                continue
            }
            translations.putAll(result)
        }
        if (translations.isEmpty()) throw OnDeviceAiException(OnDeviceFailure.BLOCKED)
        return LyricsAiChunker.rebuild(lines, translations)
    }

    /** Text -> translation for [chunk]; null when the model says it's already in the target language. */
    private suspend fun translateChunk(
        instruction: String,
        chunk: List<String>,
        temperature: Float,
        allowSplit: Boolean,
    ): Map<String, String>? {
        val reply = try {
            localAi.generate(
                LocalGenerationRequest(
                    instruction = instruction,
                    prompt = LyricsAiChunker.prompt(chunk),
                    temperature = temperature,
                    topK = 20,
                    maxOutputTokens = OnDevicePrompts.TRANSLATION_MAX_OUTPUT,
                )
            ).text
        } catch (e: OnDeviceAiException) {
            // The estimate is approximate: a chunk the model finds too long is retried once in halves.
            if (e.failure != OnDeviceFailure.TOO_LONG || !allowSplit || chunk.size < 2) throw e
            val half = chunk.size / 2
            val first = translateChunk(instruction, chunk.subList(0, half), temperature, allowSplit = false)
            val second = translateChunk(instruction, chunk.subList(half, chunk.size), temperature, allowSplit = false)
            if (first == null && second == null) return null
            return first.orEmpty() + second.orEmpty()
        }
        val byIndex = LyricsAiChunker.parseReply(reply, chunk) ?: return null
        return byIndex.entries.associate { (index, translation) -> chunk[index] to translation }
    }
}
