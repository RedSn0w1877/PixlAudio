package com.theveloper.pixelplay.data.history

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * YouTube Music's history page has no timestamps, only shelves: "Today", "Yesterday", "This week",
 * "Last week", "March 2025"… (English, because requests go out with `hl=en`). This turns a shelf label into
 * the day a play is filed under.
 */
object YouTubeHistoryDates {

    /** [exact] is true for "Today"/"Yesterday", the only shelves that name a single day. */
    data class ShelfDay(val day: LocalDate, val exact: Boolean)

    private val MONTHS: Map<String, Month> = Month.values().associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(Locale.ROOT) }
    private val MONTH_YEAR = Regex("""^([A-Za-z]+)\s+(\d{4})$""")
    private val YEAR = Regex("""^(\d{4})$""")

    fun dayFor(label: String, today: LocalDate): ShelfDay {
        val clean = label.trim().lowercase(Locale.ROOT)
        val result = when (clean) {
            "today" -> ShelfDay(today, true)
            "yesterday" -> ShelfDay(today.minusDays(1), true)
            "this week" -> ShelfDay(today.minusDays(3), false)
            "last week" -> ShelfDay(today.minusDays(10), false)
            "this month" -> ShelfDay(today.minusDays(15), false)
            "last month" -> ShelfDay(today.minusMonths(1).withDayOfMonth(15), false)
            else -> monthOrYear(clean, today) ?: ShelfDay(today.minusDays(30), false)
        }
        return if (result.day.isAfter(today)) result.copy(day = today) else result
    }

    private fun monthOrYear(clean: String, today: LocalDate): ShelfDay? {
        MONTH_YEAR.find(clean)?.let { match ->
            val month = MONTHS[match.groupValues[1]] ?: return null
            val year = match.groupValues[2].toInt()
            return ShelfDay(LocalDate.of(year, month, 15), false)
        }
        MONTHS[clean]?.let { month ->
            // A bare month name is this year's, or last year's when that month hasn't come yet.
            val thisYear = LocalDate.of(today.year, month, 15)
            return ShelfDay(if (thisYear.isAfter(today)) thisYear.minusYears(1) else thisYear, false)
        }
        YEAR.find(clean)?.let { match ->
            return ShelfDay(LocalDate.of(match.groupValues[1].toInt(), 7, 1), false)
        }
        return null
    }
}
