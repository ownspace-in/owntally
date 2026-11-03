package owntally.model.format

import java.time.DateTimeException
import java.time.LocalDate

/**
 * Dates as in the mockups: "Today, 13 Oct". An expense's day is days since 1970-01-01. English month names for now; the day
 * can be any 64-bit number off the wire, so a value that is not a real date shows as "Unknown date" and never throws.
 */
object DateText {
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val UNKNOWN = "Unknown date"

    private fun date(day: Long): LocalDate? = try {
        LocalDate.ofEpochDay(day)
    } catch (e: DateTimeException) {
        null
    }

    /** 13 Oct */
    fun short(day: Long): String = date(day)?.let { "${it.dayOfMonth} ${MONTHS[it.monthValue - 1]}" } ?: UNKNOWN

    /** Today, 13 Oct; Yesterday, 12 Oct; 9 Oct; and 31 Dec 2025 when the year is not [today]'s year. */
    fun format(day: Long, today: Long): String {
        val d = date(day) ?: return UNKNOWN
        val now = date(today)
        return when {
            day == today -> "Today, ${short(day)}"
            day == today - 1 -> "Yesterday, ${short(day)}"
            now == null || d.year == now.year -> short(day)
            else -> "${short(day)} ${d.year}"
        }
    }
}
