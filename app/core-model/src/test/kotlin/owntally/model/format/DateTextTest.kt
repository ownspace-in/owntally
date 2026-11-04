package owntally.model.format

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DateTextTest {
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private val today = day(2026, 10, 13)

    @Test
    fun theMockupDate() {
        assertEquals("Today, 13 Oct", DateText.format(today, today))
    }

    @Test
    fun yesterdayAndOtherDaysOfTheSameYear() {
        assertEquals("Yesterday, 12 Oct", DateText.format(day(2026, 10, 12), today))
        assertEquals("9 Oct", DateText.format(day(2026, 10, 9), today))
        assertEquals("1 Jan", DateText.format(day(2026, 1, 1), today))
    }

    @Test
    fun otherYearsShowTheYear() {
        assertEquals("31 Dec 2025", DateText.format(day(2025, 12, 31), today))
        assertEquals("1 Jan 1970", DateText.format(0, today))
        assertEquals("5 Nov 2027", DateText.format(day(2027, 11, 5), today), "a date in the future")
    }

    @Test
    fun theDayAroundNewYear() {
        val newYear = day(2027, 1, 1)
        assertEquals("Today, 1 Jan", DateText.format(newYear, newYear))
        assertEquals("Yesterday, 31 Dec", DateText.format(day(2026, 12, 31), newYear), "yesterday is in last year but still just 'Yesterday'")
    }

    @Test
    fun everyMonthHasItsThreeLetterName() {
        val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        for (m in 1..12) assertEquals("15 ${names[m - 1]}", DateText.format(day(2026, m, 15), today), "month $m")
    }

    @Test
    fun aLeapDay() {
        assertEquals("29 Feb", DateText.format(day(2028, 2, 29), day(2028, 6, 1)))
    }

    @Test
    fun datesBeforeTheEpochWork() {
        assertEquals("31 Dec 1969", DateText.format(-1, today))
    }

    @Test
    fun aDayFromTheWireThatIsNotARealDateIsShownAsUnknownNotACrash() {
        // The wire format allows any 64-bit number for a day (docs/WIRE_FORMAT.md).
        for (day in listOf(Long.MAX_VALUE, Long.MIN_VALUE, 400_000_000_000L, -400_000_000_000L)) {
            assertEquals("Unknown date", DateText.format(day, today), "day $day")
            assertEquals("Unknown date", DateText.short(day), "day $day")
        }
    }

    @Test
    fun theShortFormHasNoTodayOrYear() {
        assertEquals("13 Oct", DateText.short(today))
        assertEquals("13 Oct", DateText.short(day(2020, 10, 13)))
    }
}
