package owntally.model.format

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import owntally.model.Money

class MoneyFormatTest {
    private fun paise(p: Long) = Money(p)
    private fun rupees(r: Long) = Money(r * 100)

    @Test
    fun smallAmountsAndPaise() {
        assertEquals("₹0", MoneyFormat.format(paise(0)))
        assertEquals("₹0.01", MoneyFormat.format(paise(1)))
        assertEquals("₹0.99", MoneyFormat.format(paise(99)))
        assertEquals("₹1", MoneyFormat.format(paise(100)))
        assertEquals("₹1.05", MoneyFormat.format(paise(105)))
        assertEquals("₹33.34", MoneyFormat.format(paise(3334)))
        assertEquals("₹999.99", MoneyFormat.format(paise(99_999)))
    }

    @Test
    fun paiseAreShownOnlyWhenThereAreSomeAndAlwaysWithTwoDigits() {
        assertEquals("₹2,400", MoneyFormat.format(rupees(2400)))
        assertEquals("₹2,400.50", MoneyFormat.format(paise(240_050)))
        assertEquals("₹2,400.05", MoneyFormat.format(paise(240_005)))
    }

    @Test
    fun groupingIsIndian() {
        // Thousands, then groups of two: lakh and crore.
        val expected = mapOf(
            999L to "₹999",
            1_000L to "₹1,000",
            99_999L to "₹99,999",
            100_000L to "₹1,00,000",
            1_234_567L to "₹12,34,567",
            10_000_000L to "₹1,00,00,000",
            1_000_000_000L to "₹1,00,00,00,000",
        )
        for ((r, text) in expected) assertEquals(text, MoneyFormat.format(rupees(r)), "rupees $r")
    }

    @Test
    fun theNumberAloneHasNoSymbol() {
        assertEquals("12,34,567.89", MoneyFormat.number(paise(123_456_789)))
        assertEquals("0", MoneyFormat.number(paise(0)))
    }

    @Test
    fun negativeAndSignedAmounts() {
        assertEquals("-₹1,850", MoneyFormat.format(rupees(-1850)))
        assertEquals("-₹0.05", MoneyFormat.format(paise(-5)))
        assertEquals("+₹3,200", MoneyFormat.signed(rupees(3200)))
        assertEquals("-₹1,850", MoneyFormat.signed(rupees(-1850)))
        assertEquals("₹0", MoneyFormat.signed(paise(0)), "zero has no sign")
    }

    @Test
    fun theLargestAndSmallestValuesDoNotOverflow() {
        // 9,223,372,036,854,775,807 paise = 92,233,720,368,547,758 rupees and 7 paise... worked out by hand: 17 digits,
        // last three "758", the other fourteen in pairs from the right.
        assertEquals("₹92,23,37,20,36,85,47,758.07", MoneyFormat.format(Money(Long.MAX_VALUE)))
        assertEquals("-₹92,23,37,20,36,85,47,758.08", MoneyFormat.format(Money(Long.MIN_VALUE)))
    }

    /** Every amount drawn in the mockups in design/, as shown there. */
    @Test
    fun everyAmountInTheMockups() {
        val shown = mapOf(
            4820L to "₹4,820", 1150L to "₹1,150", 3200L to "₹3,200", 1620L to "₹1,620", 38460L to "₹38,460",
            1850L to "₹1,850", 1350L to "₹1,350", 4800L to "₹4,800", 3840L to "₹3,840", 2500L to "₹2,500",
            21000L to "₹21,000", 4200L to "₹4,200", 1900L to "₹1,900", 1520L to "₹1,520", 2400L to "₹2,400",
            600L to "₹600", 900L to "₹900", 2740L to "₹2,740", 1220L to "₹1,220", 0L to "₹0",
        )
        assertEquals(20, shown.size)
        for ((r, text) in shown) assertEquals(text, MoneyFormat.format(rupees(r)), "mockup amount $r")
        // The remove-member screen: before and after, with signs.
        assertEquals(
            listOf("+₹3,200", "+₹2,740", "-₹1,850", "-₹1,520", "-₹1,350", "-₹1,220", "₹0"),
            listOf(3200L, 2740L, -1850L, -1520L, -1350L, -1220L, 0L).map { MoneyFormat.signed(rupees(it)) },
        )
    }

    @Test
    fun aBalanceIsOwedOwesOrSettledWithTheAmountWithoutSign() {
        assertEquals(BalanceView(BalanceKind.OWED, rupees(3200), "₹3,200"), MoneyFormat.describe(rupees(3200)))
        assertEquals(BalanceView(BalanceKind.OWES, rupees(1150), "₹1,150"), MoneyFormat.describe(rupees(-1150)))
        assertEquals(BalanceView(BalanceKind.SETTLED, Money.ZERO, "₹0"), MoneyFormat.describe(Money.ZERO))
        assertEquals(BalanceKind.OWES, MoneyFormat.describe(paise(-1)).kind)
        assertEquals("₹0.01", MoneyFormat.describe(paise(-1)).text)
    }

    @Test
    fun editableTextHasNoSymbolOrGrouping() {
        assertEquals("2400", MoneyFormat.editable(rupees(2400)))
        assertEquals("2400.50", MoneyFormat.editable(paise(240_050)))
        assertEquals("0.05", MoneyFormat.editable(paise(5)))
        assertEquals("0", MoneyFormat.editable(paise(0)))
    }

    @Test
    fun whatIsShownCanBeTypedBack() = runBlocking<Unit> {
        checkAll(2000, Arb.long(0, MoneyInput.MAX.paise)) { p ->
            val m = Money(p)
            assertEquals(ParsedAmount.Valid(m), MoneyInput.parse(MoneyFormat.format(m)), MoneyFormat.format(m))
            assertEquals(ParsedAmount.Valid(m), MoneyInput.parse(MoneyFormat.editable(m)), MoneyFormat.editable(m))
        }
    }
}
