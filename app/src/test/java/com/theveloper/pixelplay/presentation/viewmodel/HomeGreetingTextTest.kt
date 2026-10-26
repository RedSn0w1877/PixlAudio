package com.theveloper.pixelplay.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.jupiter.api.Test

class HomeGreetingTextTest {

    private val today = LocalDate.of(2026, 10, 26)

    @Test
    fun `stat phrasing is recognised`() {
        assertThat(HomeGreetingText.soundsLikeStats("You've logged 340 plays across 1200 songs in your library.")).isTrue()
        assertThat(HomeGreetingText.soundsLikeStats("12 songs in your library, ready to explore")).isTrue()
        assertThat(HomeGreetingText.soundsLikeStats("340 plays logged · mostly rock lately")).isTrue()
        assertThat(HomeGreetingText.soundsLikeStats("Morning — feels like a Radiohead kind of day.")).isFalse()
        assertThat(HomeGreetingText.soundsLikeStats("5 days in a row. Keep it rolling.")).isFalse()
    }

    @Test
    fun `no local line ever states a count`() {
        val contexts = listOf(
            GreetingContext("morning"),
            GreetingContext("night", topArtist = "Radiohead", topGenre = "rock", dayOfYear = 3),
            GreetingContext("evening", lastTitle = "Redbone (Remastered)", lastArtist = "Childish Gambino"),
            GreetingContext("afternoon", freshArtist = "Phoebe Bridgers"),
            GreetingContext("evening", streakDays = 6),
        )
        for (ctx in contexts) {
            assertThat(HomeGreetingText.soundsLikeStats(HomeGreetingText.headline(ctx))).isFalse()
            assertThat(HomeGreetingText.soundsLikeStats(HomeGreetingText.subtitle(ctx))).isFalse()
            assertThat(HomeGreetingText.soundsLikeStats(HomeGreetingText.insightFallback(ctx))).isFalse()
        }
    }

    @Test
    fun `headline drops an artist that would not fit`() {
        val long = GreetingContext("morning", topArtist = "The Extraordinarily Long Named Band Of Nowhere")
        assertThat(HomeGreetingText.headline(long).length).isAtMost(HomeGreetingText.MAX_HEADLINE_CHARS)
        assertThat(HomeGreetingText.headline(long)).doesNotContain("Nowhere")
        assertThat(HomeGreetingText.headline(GreetingContext("morning", topArtist = "Radiohead")))
            .isEqualTo("Good morning — ready for more Radiohead?")
        assertThat(HomeGreetingText.headline(GreetingContext("night", topGenre = "lo-fi")))
            .isEqualTo("Still up? In the mood for lo-fi?")
    }

    @Test
    fun `subtitle prefers what just played, then something new, then a streak, then the hour`() {
        val all = GreetingContext(
            "evening", lastTitle = "Redbone", lastArtist = "Childish Gambino", freshArtist = "Mitski", streakDays = 5,
        )
        assertThat(HomeGreetingText.subtitle(all)).isEqualTo("Last up: Redbone")
        assertThat(HomeGreetingText.subtitle(all.copy(lastTitle = null))).isEqualTo("Fresh find: Mitski")
        assertThat(HomeGreetingText.subtitle(all.copy(lastTitle = null, freshArtist = null)))
            .isEqualTo("5 days in a row. Keep it rolling.")
        assertThat(HomeGreetingText.subtitle(GreetingContext("morning", dayOfYear = 1))).isNotEmpty()
    }

    @Test
    fun `subtitle is cut at a word, never in the middle of one`() {
        val ctx = GreetingContext("evening", lastTitle = "Supercalifragilistic Expialidocious Everything Goes")
        val line = HomeGreetingText.subtitle(ctx)
        assertThat(line.length).isAtMost(HomeGreetingText.MAX_SUBTITLE_CHARS)
        assertThat(line).endsWith("…")
        val words = line.removeSuffix("…").split(' ')
        assertThat("Last up: Supercalifragilistic Expialidocious Everything Goes").contains(words.joinToString(" "))
        assertThat(HomeGreetingText.fitWords("short enough", 40)).isEqualTo("short enough")
    }

    @Test
    fun `a generated headline that is long or a stat is refused`() {
        assertThat(HomeGreetingText.acceptGeneratedHeadline("\"Morning — feels like a Radiohead day.\""))
            .isEqualTo("Morning — feels like a Radiohead day.")
        assertThat(HomeGreetingText.acceptGeneratedHeadline("Good morning, you've played 340 songs in your library")).isNull()
        assertThat(HomeGreetingText.acceptGeneratedHeadline("x ".repeat(60))).isNull()
        assertThat(HomeGreetingText.acceptGeneratedHeadline("two\nlines")).isNull()
        assertThat(HomeGreetingText.acceptGeneratedHeadline(null)).isNull()
        assertThat(HomeGreetingText.acceptGeneratedHeadline("   ")).isNull()
    }

    @Test
    fun `a generated note keeps whole sentences that fit`() {
        val long = "Radiohead has been on repeat all week. " +
            "You keep landing on the same few albums, which says a lot about the mood you are in and where your head has been lately. " +
            "Maybe try something new."
        val kept = HomeGreetingText.acceptGeneratedInsight(long)
        assertThat(kept).isEqualTo("Radiohead has been on repeat all week.")
        assertThat(HomeGreetingText.acceptGeneratedInsight("You played 12 songs today.")).isNull()
        assertThat(HomeGreetingText.acceptGeneratedInsight("A" + "a".repeat(300))).isNull()
    }

    @Test
    fun `streak counts days in a row and only claims what it can see`() {
        val days = listOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(4))
        // The gap on day 3 is inside the fetched window: a real streak of 3.
        assertThat(HomeGreetingText.streakDays(days, today, windowComplete = false)).isEqualTo(3)
        // Streak alive through yesterday (nothing yet today).
        assertThat(HomeGreetingText.streakDays(listOf(today.minusDays(1), today.minusDays(2), today.minusDays(3), today.minusDays(5)), today, false))
            .isEqualTo(3)
        // Every fetched day is part of the streak and the window isn't the whole history: unknown, so no claim.
        assertThat(HomeGreetingText.streakDays(listOf(today, today.minusDays(1), today.minusDays(2)), today, windowComplete = false)).isEqualTo(0)
        // ...but with the whole history fetched, it is exactly 3.
        assertThat(HomeGreetingText.streakDays(listOf(today, today.minusDays(1), today.minusDays(2)), today, windowComplete = true)).isEqualTo(3)
        // No play today or yesterday: no streak.
        assertThat(HomeGreetingText.streakDays(listOf(today.minusDays(3)), today, windowComplete = true)).isEqualTo(0)
        assertThat(HomeGreetingText.streakDays(emptyList(), today, windowComplete = true)).isEqualTo(0)
    }

    @Test
    fun `titles and artists are tidied`() {
        assertThat(HomeGreetingText.cleanTitle("Song Name (Remastered 2011) - Live")).isEqualTo("Song Name")
    }

    @Test
    fun `the day phase follows the hour`() {
        assertThat(HomeGreetingText.dayPhase(7)).isEqualTo("morning")
        assertThat(HomeGreetingText.dayPhase(13)).isEqualTo("afternoon")
        assertThat(HomeGreetingText.dayPhase(19)).isEqualTo("evening")
        assertThat(HomeGreetingText.dayPhase(2)).isEqualTo("night")
    }
}
