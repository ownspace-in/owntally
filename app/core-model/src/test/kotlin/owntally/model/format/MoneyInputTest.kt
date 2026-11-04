package owntally.model.format

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import owntally.model.Money

class MoneyInputTest {
    private fun valid(text: String, paise: Long) = assertEquals(ParsedAmount.Valid(Money(paise)), MoneyInput.parse(text), "\"$text\"")
    private fun invalid(text: String, problem: AmountProblem) = assertEquals(ParsedAmount.Invalid(problem), MoneyInput.parse(text), "\"$text\"")

    @Test
    fun plainAndGroupedAmounts() {
        valid("2400", 240_000)
        valid("2,400", 240_000)
        valid("1,00,000", 10_000_000)
        valid("12,34,567", 123_456_700)
        valid("0", 0)
        valid("00012", 1_200)
    }

    @Test
    fun paise() {
        valid("2400.5", 240_050)
        valid("2400.50", 240_050)
        valid("2400.05", 240_005)
        valid("2400.0", 240_000)
        valid("2400.", 240_000) // while typing
        valid(".5", 50)
        valid("0.05", 5)
    }

    @Test
    fun theRupeeSignAndSpacesAroundAreIgnored() {
        valid(" 2400 ", 240_000)
        valid("₹2,400", 240_000)
        valid("₹ 2,400.50", 240_050)
        valid("\t₹2400\n", 240_000)
    }

    @Test
    fun nothingTypedYetIsEmptyNotAnError() {
        for (text in listOf("", "   ", "₹", "₹ ")) assertEquals(ParsedAmount.Empty, MoneyInput.parse(text), "\"$text\"")
    }

    @Test
    fun moreThanTwoDecimalsIsRefusedNotRounded() {
        invalid("1.234", AmountProblem.TOO_MANY_DECIMALS)
        invalid("2400.505", AmountProblem.TOO_MANY_DECIMALS)
        invalid("0.001", AmountProblem.TOO_MANY_DECIMALS)
    }

    @Test
    fun letterSymbolsSignsSpacesAndOtherScriptDigitsAreBadCharacters() {
        for (text in listOf("abc", "12a", "1e5", "-5", "+5", "(5)", "5%", "2 400", "$5", "₹₹5", "٢٤٠٠", "२४००", "１２")) {
            invalid(text, AmountProblem.BAD_CHARACTER)
        }
    }

    @Test
    fun separatorsInTheWrongPlace() {
        for (text in listOf(".", "₹.", "1..2", "1.2.3", ",5", "5,", "1,,000", "1,000.5,5", "1,.5", "1.,5")) {
            invalid(text, AmountProblem.MISPLACED_SEPARATOR)
        }
    }

    @Test
    fun commasAreAcceptedWherePeopleTypeThem() {
        valid("1,23.45", 12_345) // not a standard grouping, but harmless
        valid("2,4,00", 240_000)
    }

    @Test
    fun theLimitIsOneHundredMillionWholeUnits() {
        assertEquals(Money(10_000_000_000), MoneyInput.MAX)
        valid("100000000", 10_000_000_000)
        valid("10,00,00,000", 10_000_000_000)
        valid("100000000.00", 10_000_000_000)
        invalid("100000000.01", AmountProblem.TOO_LARGE)
        invalid("100000001", AmountProblem.TOO_LARGE)
        invalid("9".repeat(50), AmountProblem.TOO_LARGE)
        invalid("9".repeat(500) + ".99", AmountProblem.TOO_LARGE)
    }

    @Test
    fun nothingTypedEverThrows() = runBlocking<Unit> {
        val alphabet = "0123456789,.₹ -+abc٢ \t\n"
        checkAll(3000, Arb.long()) { seed ->
            val rnd = Random(seed)
            val text = String(CharArray(rnd.nextInt(0, 30)) { alphabet[rnd.nextInt(alphabet.length)] })
            val result = MoneyInput.parse(text) // must not throw
            if (result is ParsedAmount.Valid) assertTrue(result.money.paise in 0..MoneyInput.MAX.paise, text)
        }
    }
}
