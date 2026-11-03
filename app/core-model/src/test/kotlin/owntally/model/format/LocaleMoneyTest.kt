package owntally.model.format

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import owntally.model.Money

/** Amounts follow the phone's language and region. Every test here names its locale, so the result never depends on the machine. */
class LocaleMoneyTest {
    private val inIN = Locale.forLanguageTag("en-IN")
    private val enUS = Locale.forLanguageTag("en-US")
    private val deDE = Locale.forLanguageTag("de-DE")
    private val frFR = Locale.forLanguageTag("fr-FR")
    private val hiIN = Locale.forLanguageTag("hi-IN")

    private fun r(rupees: Long, paise: Long = 0) = Money(rupees * 100 + paise)

    @Test fun indianRegionsGroupInLakhs() {
        assertEquals("₹12,34,567", MoneyFormat.format(r(1_234_567), inIN))
        assertEquals("₹12,34,567", MoneyFormat.format(r(1_234_567), hiIN))
        assertEquals("₹12,34,567", MoneyFormat.format(r(1_234_567), Locale.forLanguageTag("hi")), "Hindi without a region")
    }

    @Test fun otherRegionsGroupInThousands() {
        assertEquals("₹1,234,567", MoneyFormat.format(r(1_234_567), enUS))
        assertEquals("₹100,000", MoneyFormat.format(r(100_000), enUS))
        assertEquals("₹999", MoneyFormat.format(r(999), enUS))
    }

    @Test fun germanUsesDotsAndCommas() {
        assertEquals("₹1.234.567,50", MoneyFormat.format(Money(123_456_750), deDE))
        assertEquals("₹2.400", MoneyFormat.format(r(2400), deDE))
        assertEquals("-₹0,05", MoneyFormat.format(Money(-5), deDE))
    }

    @Test fun frenchUsesASpaceAndAComma() {
        val text = MoneyFormat.format(Money(123_456_750), frFR)
        assertEquals("₹1 234 567,50", text.replace(' ', ' ').replace(' ', ' '))
    }

    @Test fun unusualSeparatorsFallBackToPlainOnes() {
        // Some languages use their own digits and separator signs; amounts always use 0-9 with "," and "." so every phone can read them.
        val arabic = Locale.forLanguageTag("ar-EG")
        assertEquals("₹1,234.50", MoneyFormat.format(Money(123_450), arabic))
        val swiss = Locale.forLanguageTag("de-CH")
        assertEquals("₹1,234.50", MoneyFormat.format(Money(123_450), swiss))
    }

    @Test fun editableTextUsesTheLocalesDecimalSign() {
        assertEquals("2400.50", MoneyFormat.editable(Money(240_050), enUS))
        assertEquals("2400,50", MoneyFormat.editable(Money(240_050), deDE))
        assertEquals("2400", MoneyFormat.editable(r(2400), deDE))
    }

    @Test fun whatIsShownForEditingReadsBackExactlyInEveryLocale() {
        for (loc in listOf(inIN, enUS, deDE, frFR, hiIN)) {
            for (p in listOf(0L, 1L, 5L, 99L, 100L, 240_050L, 123_456_789L, 9_999_999_999L)) {
                val text = MoneyFormat.editable(Money(p), loc)
                assertEquals(ParsedAmount.Valid(Money(p)), MoneyInput.parse(text, loc), "$loc $text")
            }
        }
    }

    @Test fun aDecimalCommaIsNotReadAsAThousandsComma() {
        // The dangerous case: on a German phone "2,5" means two and a half rupees, not 25.
        assertEquals(ParsedAmount.Valid(Money(250)), MoneyInput.parse("2,5", deDE))
        assertEquals(ParsedAmount.Valid(Money(240_050)), MoneyInput.parse("2.400,50", deDE))
        assertEquals(ParsedAmount.Valid(Money(240_000)), MoneyInput.parse("2.400", deDE))
        // On an English phone the comma still groups.
        assertEquals(ParsedAmount.Valid(Money(240_050)), MoneyInput.parse("2,400.50", enUS))
        assertEquals(ParsedAmount.Valid(Money(240_000)), MoneyInput.parse("2,400", enUS))
    }

    @Test fun aDotTypedOnAGermanKeyboardStillWorksAsADecimalWhenItCannotBeGrouping() {
        // "2.5" cannot be a thousands group (a group has three digits), so it is read as two and a half.
        assertEquals(ParsedAmount.Valid(Money(250)), MoneyInput.parse("2.5", deDE))
    }

    @Test fun frenchGroupingSpacesAreIgnoredWhenTyped() {
        assertEquals(ParsedAmount.Valid(Money(240_050)), MoneyInput.parse("2 400,50", frFR))
        assertEquals(ParsedAmount.Valid(Money(240_050)), MoneyInput.parse("2 400,50", frFR))
    }

    @Test fun stillRefusesThreeDecimalsAndLettersInEveryLocale() {
        for (loc in listOf(inIN, enUS, deDE, frFR)) {
            assertEquals(ParsedAmount.Invalid(AmountProblem.BAD_CHARACTER), MoneyInput.parse("12a", loc), "$loc")
            val tooMany = if (loc == deDE || loc == frFR) "1,2345" else "1.2345"
            assertEquals(ParsedAmount.Invalid(AmountProblem.TOO_MANY_DECIMALS), MoneyInput.parse(tooMany, loc), "$loc")
        }
    }

    @Test fun describeKeepsItsMeaningWhateverTheLocale() {
        val v = MoneyFormat.describe(Money(-185_000), deDE)
        assertEquals(BalanceKind.OWES, v.kind)
        assertEquals("₹1.850", v.text)
    }

    @Test fun signedFollowsTheLocale() {
        assertEquals("+₹1.850,25", MoneyFormat.signed(Money(185_025), deDE))
    }
}
