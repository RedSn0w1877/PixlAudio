package com.theveloper.pixelplay.data.ai.local

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.utils.LyricsImportSecurity
import com.theveloper.pixelplay.utils.LyricsImportValidationResult
import org.junit.jupiter.api.Test

class LyricsAiChunkerTest {

    private val lrc = """
        [ar:Some Artist]
        [00:01.00]Hello darkness
        [00:05.50]My old friend
        [00:09.00]
        [00:12.00]Hello darkness
        [00:15.25]<00:15.25>I've <00:15.60>come <00:16.00>again
    """.trimIndent()

    @Test
    fun `distinct lines are translated once and tags stay out`() {
        val lines = LyricsAiChunker.parse(lrc)
        val texts = LyricsAiChunker.translatableTexts(lines)
        assertThat(texts).containsExactly("Hello darkness", "My old friend", "I've come again").inOrder()
    }

    @Test
    fun `timestamps are re-attached by the app and the result is valid LRC`() {
        val lines = LyricsAiChunker.parse(lrc)
        val rebuilt = LyricsAiChunker.rebuild(
            lines,
            mapOf("Hello darkness" to "Xin chào bóng tối", "My old friend" to "Người bạn cũ của tôi"),
        )
        assertThat(rebuilt.lines()).containsAtLeast(
            "[00:01.00]Hello darkness",
            "[00:01.00]Xin chào bóng tối",
            "[00:05.50]My old friend",
            "[00:05.50]Người bạn cũ của tôi",
            "[00:12.00]Xin chào bóng tối",
        ).inOrder()
        // The untranslated line keeps only its original.
        assertThat(rebuilt).contains("[00:15.25]<00:15.25>I've <00:15.60>come <00:16.00>again")
        assertThat(rebuilt.lines().count { it.startsWith("[00:15.25]") }).isEqualTo(1)
        assertThat(LyricsImportSecurity.validateImportedLrcContent(rebuilt))
            .isInstanceOf(LyricsImportValidationResult.Valid::class.java)
    }

    @Test
    fun `chunks stay within budget and CJK gets fewer lines per chunk`() {
        val english = (1..120).map { "This is lyric line number $it of the song" }
        val chinese = (1..120).map { "这是这首歌的第${it}行歌词我们一起唱到天亮吧朋友们一起来" }
        val englishChunks = LyricsAiChunker.chunk(english)
        val chineseChunks = LyricsAiChunker.chunk(chinese)

        for (chunk in englishChunks + chineseChunks) {
            assertThat(TokenBudget.estimate(LyricsAiChunker.prompt(chunk))).isAtMost(OnDevicePrompts.TRANSLATION_CHUNK_TOKENS + 10)
        }
        assertThat(chineseChunks.size).isGreaterThan(englishChunks.size)
        assertThat(englishChunks.flatten()).isEqualTo(english)
        assertThat(chineseChunks.flatten()).isEqualTo(chinese)
    }

    @Test
    fun `a reply with missing numbers keeps those lines original`() {
        val chunk = listOf("Hello darkness", "My old friend", "I've come again")
        val parsed = LyricsAiChunker.parseReply("1. Xin chào bóng tối\n3) Tôi lại đến\nsome chatter", chunk)!!
        assertThat(parsed).containsExactly(0, "Xin chào bóng tối", 2, "Tôi lại đến")
    }

    @Test
    fun `a line echoed back untranslated is not a translation`() {
        val chunk = listOf("Hello darkness")
        assertThat(LyricsAiChunker.parseReply("1. Hello darkness", chunk)).isEmpty()
    }

    @Test
    fun `ALREADY_IN_TARGET_LANGUAGE short-circuits`() {
        assertThat(LyricsAiChunker.parseReply("ALREADY_IN_TARGET_LANGUAGE", listOf("x"))).isNull()
    }

    @Test
    fun `stamps the model copies into its answer are stripped`() {
        val parsed = LyricsAiChunker.parseReply("1. [00:01.00] Bonjour", listOf("Hello"))!!
        assertThat(parsed[0]).isEqualTo("Bonjour")
    }
}
