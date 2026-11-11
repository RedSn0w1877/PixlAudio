package com.theveloper.pixelplay.presentation.viewmodel

import java.time.LocalDate

/** What the greeting can know about the listener's day. Everything is optional; nothing here is a count of plays or songs. */
data class GreetingContext(
    /** "morning", "afternoon", "evening" or "night". */
    val phase: String,
    val topArtist: String? = null,
    val topGenre: String? = null,
    /** The song played most recently, only when that was within the last few hours. */
    val lastTitle: String? = null,
    val lastArtist: String? = null,
    /** Days in a row with at least one play, only when it is a known count (>= 3 is worth saying). */
    val streakDays: Int = 0,
    /** An artist heard lately that wasn't in the listening history before this week. */
    val freshArtist: String? = null,
    /** Varies the wording from day to day without any randomness. */
    val dayOfYear: Int = 0,
)

/**
 * The words of Home's greeting card, pure so they are tested without Android. The card has room for one short
 * headline and one short line under it, so every text here is kept to a length that fits and is cut at a word
 * (never mid-word). Nothing here ever states a play count or a library size ("N plays across N songs in your
 * library" is gone on purpose): the lines are about the day, what just played, something new, a streak or a mood.
 */
internal object HomeGreetingText {
    /** Two lines of the card's headline style. */
    const val MAX_HEADLINE_CHARS = 52
    const val MAX_SUBTITLE_CHARS = 40
    /** The expanded note under the card: one or two short sentences. */
    const val MAX_INSIGHT_CHARS = 140

    private val statPhrasing = Regex(
        """\b\d[\d,.]*\s*(plays?|songs?|tracks?|times)\b|\bplays? (across|logged)\b|\b(songs?|tracks?) in your library\b|\bin your library\b""",
        RegexOption.IGNORE_CASE,
    )

    /** True when [text] would read like a stat line ("340 plays", "12 songs in your library"). */
    fun soundsLikeStats(text: String): Boolean = statPhrasing.containsMatchIn(text)

    fun dayPhase(hour: Int): String = when (hour) {
        in 5..10 -> "morning"
        in 11..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    /** Shortens [text] to at most [max] characters at a word boundary, adding "…" only when it had to cut. */
    fun fitWords(text: String, max: Int): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= max) return clean
        val cut = clean.take(max - 1)
        val atWord = cut.substringBeforeLast(' ', missingDelimiterValue = "")
        val base = if (atWord.length >= max / 2) atWord else cut
        return base.trimEnd(',', ';', ':', '-', '—', ' ') + "…"
    }

    /** "Song (Remastered 2011) - Live" -> "Song": the part of a title people actually say. */
    fun cleanTitle(title: String): String =
        title.substringBefore(" - ").substringBefore(" (").substringBefore(" [").trim().ifBlank { title.trim() }

    /** The local, instant headline, kept to [MAX_HEADLINE_CHARS] by dropping the artist or genre when it is too long. */
    fun headline(ctx: GreetingContext): String {
        val salutation = when (ctx.phase) {
            "morning" -> "Good morning"
            "afternoon" -> "Good afternoon"
            "evening" -> "Good evening"
            else -> "Still up?"
        }
        // "Good morning — ready for more X?" / "Still up? Ready for more X?"
        fun line(phrase: String): String =
            if (salutation.endsWith("?")) "$salutation ${phrase.replaceFirstChar { it.uppercase() }}"
            else "$salutation — $phrase"
        val candidates = buildList {
            ctx.topArtist?.let { add(line("ready for more $it?")) }
            ctx.topGenre?.let { add(line("in the mood for $it?")) }
            add(line("what are we playing?"))
            add(salutation)
        }
        return candidates.firstOrNull { it.length <= MAX_HEADLINE_CHARS } ?: fitWords(salutation, MAX_HEADLINE_CHARS)
    }

    /** The short line under the headline: just played, something new, a streak, or a mood for the hour. */
    fun subtitle(ctx: GreetingContext): String {
        ctx.lastTitle?.takeIf { it.isNotBlank() }?.let { title ->
            return fitWords("Last up: ${cleanTitle(title)}", MAX_SUBTITLE_CHARS)
        }
        ctx.freshArtist?.takeIf { it.isNotBlank() }?.let { artist ->
            return fitWords("Fresh find: ${artist.trim()}", MAX_SUBTITLE_CHARS)
        }
        if (ctx.streakDays >= 3) {
            return "${ctx.streakDays} days in a row. Keep it rolling."
        }
        val genre = ctx.topGenre?.takeIf { it.isNotBlank() }
        val lines = when (ctx.phase) {
            "morning" -> listOfNotNull(genre?.let { "$it to ease into the day" }, "An easy start to the day?", "A fresh start. Pick a mood.")
            "afternoon" -> listOfNotNull(genre?.let { "Some $it for the afternoon" }, "Keep the focus going.", "Something to get through the afternoon?")
            "evening" -> listOfNotNull(genre?.let { "$it for the evening" }, "Time to wind down, or not.", "The evening is yours.")
            else -> listOfNotNull(genre?.let { "Late-night $it?" }, "Quiet hours, good music.", "Something low for the night?")
        }
        return fitWords(lines[Math.floorMod(ctx.dayOfYear, lines.size)], MAX_SUBTITLE_CHARS)
    }

    /**
     * A generated headline made fit for the card: one line of plain text that is short enough and doesn't state a
     * stat. Null means "use the local headline instead" (never cut a generated sentence in the middle).
     */
    fun acceptGeneratedHeadline(raw: String?): String? {
        val clean = raw?.trim()?.trim('"', '“', '”')?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (clean.contains('\n')) return null
        if (clean.length > MAX_HEADLINE_CHARS + 8) return null
        if (soundsLikeStats(clean)) return null
        return clean
    }

    /** A generated note for the expanded card: kept to whole sentences within [MAX_INSIGHT_CHARS], or null. */
    fun acceptGeneratedInsight(raw: String?): String? {
        val clean = raw?.trim()?.trim('"', '“', '”')?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() } ?: return null
        if (soundsLikeStats(clean)) return null
        if (clean.length <= MAX_INSIGHT_CHARS) return clean
        // Keep the whole sentences that fit; a single long sentence is rejected rather than cut.
        val sentences = Regex("(?<=[.!?])\\s+").split(clean)
        val kept = StringBuilder()
        for (sentence in sentences) {
            val next = if (kept.isEmpty()) sentence else "$kept $sentence"
            if (next.length > MAX_INSIGHT_CHARS) break
            kept.clear()
            kept.append(next)
        }
        return kept.toString().takeIf { it.isNotBlank() }
    }

    /** The expanded note when no AI answered: the day, the artist or genre on repeat, never a count. */
    fun insightFallback(ctx: GreetingContext): String {
        val parts = buildList {
            ctx.topArtist?.let { add("${it.trim()} has been on repeat lately.") }
            ctx.topGenre?.let { add("$it is where you keep landing.") }
            ctx.freshArtist?.let { if (size < 2) add("${it.trim()} is a fresh find.") }
        }
        val text = parts.take(2).joinToString(" ")
        return text.ifBlank { "Play a few songs and this starts to sound like you." }
    }

    /**
     * The length of the current streak of days with a play, or 0 when it is short or can't be known.
     *
     * [playDays] are the days of the fetched plays. A streak counts back from today (or from yesterday while today
     * has no play yet). When the fetched plays only reach back as far as the streak itself ([windowComplete]
     * false and no gap found) the real length is unknown, so no claim is made.
     */
    fun streakDays(playDays: Collection<LocalDate>, today: LocalDate, windowComplete: Boolean): Int {
        if (playDays.isEmpty()) return 0
        val days = playDays.toHashSet()
        val oldestEpoch = playDays.minOf { it.toEpochDay() }
        var cursor = if (today in days) today else today.minusDays(1)
        if (cursor !in days) return 0
        var count = 0
        while (cursor in days) {
            count++
            cursor = cursor.minusDays(1)
        }
        // `cursor` is the first day with no play. It is a real gap only when it lies inside the fetched window.
        val gapIsReal = windowComplete || cursor.toEpochDay() > oldestEpoch
        return if (gapIsReal) count else 0
    }
}
