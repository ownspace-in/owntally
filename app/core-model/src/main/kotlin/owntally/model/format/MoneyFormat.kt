package owntally.model.format

import owntally.model.Money

enum class BalanceKind { OWED, OWES, SETTLED }

/** A balance ready to put in a sentence: "you are owed [text]", "you owe [text]", or settled up. [amount] has no sign. */
data class BalanceView(val kind: BalanceKind, val amount: Money, val text: String)

/**
 * How money is written on screen, as in the mockups in design/: ₹3,200, with Indian digit grouping (₹1,00,000) and paise only
 * when there are some (₹2,400.50). Plain Kotlin on purpose: no Locale, so a phone's settings cannot change a number.
 */
object MoneyFormat {
    const val RUPEE = "₹"

    private class Parts(val negative: Boolean, val rupees: String, val paise: String)

    private fun parts(money: Money): Parts {
        // Work on the digits so that Long.MIN_VALUE needs no negation.
        val digits = money.paise.toString().removePrefix("-").padStart(3, '0')
        return Parts(money.paise < 0, digits.dropLast(2), digits.takeLast(2))
    }

    /** Thousands, then groups of two: 1234567 becomes 12,34,567. */
    private fun grouped(rupees: String): String {
        if (rupees.length <= 3) return rupees
        val head = rupees.dropLast(3)
        val pairs = head.reversed().chunked(2).map { it.reversed() }.reversed()
        return pairs.joinToString(",") + "," + rupees.takeLast(3)
    }

    private fun paiseSuffix(p: Parts) = if (p.paise == "00") "" else "." + p.paise

    /** The number alone, with a leading minus if negative: 12,34,567.89 */
    fun number(money: Money): String = parts(money).let { (if (it.negative) "-" else "") + grouped(it.rupees) + paiseSuffix(it) }

    /** ₹2,400 or -₹1,850 */
    fun format(money: Money): String = parts(money).let { (if (it.negative) "-" else "") + RUPEE + grouped(it.rupees) + paiseSuffix(it) }

    /** +₹3,200 or -₹1,850; zero has no sign. */
    fun signed(money: Money): String = if (money.paise > 0) "+" + format(money) else format(money)

    fun describe(balance: Money): BalanceView = when {
        balance.paise > 0 -> BalanceView(BalanceKind.OWED, balance, format(balance))
        balance.paise < 0 -> {
            val amount = if (balance.paise == Long.MIN_VALUE) Money(Long.MAX_VALUE) else -balance
            BalanceView(BalanceKind.OWES, amount, format(amount))
        }
        else -> BalanceView(BalanceKind.SETTLED, Money.ZERO, format(Money.ZERO))
    }

    /** For a text field: no symbol and no grouping, 2400 or 2400.50. [MoneyInput.parse] reads it back exactly. */
    fun editable(money: Money): String = parts(money).let { (if (it.negative) "-" else "") + it.rupees + paiseSuffix(it) }
}
