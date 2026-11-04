package owntally.model.format

import java.util.Locale
import owntally.model.Money

enum class BalanceKind { OWED, OWES, SETTLED }

/** A balance ready to put in a sentence: "you are owed [text]", "you owe [text]", or settled up. [amount] has no sign. */
data class BalanceView(val kind: BalanceKind, val amount: Money, val text: String)

/**
 * How money is written on screen: ₹3,200, with the grouping and signs of the phone's language and region (₹1,00,000 in India,
 * ₹100,000 in the US, ₹100.000 in Germany), and paise only when there are some (₹2,400.50). Every function takes the locale so
 * tests can name it; without one, the phone's current locale is used. Digits are always 0 to 9 (see [NumberStyle]).
 */
object MoneyFormat {
    const val RUPEE = "₹"

    private class Parts(val negative: Boolean, val rupees: String, val paise: String)

    private fun parts(money: Money): Parts {
        // Work on the digits so that Long.MIN_VALUE needs no negation.
        val digits = money.paise.toString().removePrefix("-").padStart(3, '0')
        return Parts(money.paise < 0, digits.dropLast(2), digits.takeLast(2))
    }

    /** Thousands, then (in the lakh style) groups of two: 1234567 becomes 12,34,567; otherwise groups of three: 1,234,567. */
    private fun grouped(rupees: String, style: NumberStyle): String {
        if (rupees.length <= 3) return rupees
        val g = style.group.toString()
        if (!style.lakh) return rupees.reversed().chunked(3).map { it.reversed() }.reversed().joinToString(g)
        val head = rupees.dropLast(3)
        val pairs = head.reversed().chunked(2).map { it.reversed() }.reversed()
        return pairs.joinToString(g) + g + rupees.takeLast(3)
    }

    private fun paiseSuffix(p: Parts, style: NumberStyle) = if (p.paise == "00") "" else style.decimal + p.paise

    private fun plain(money: Money, style: NumberStyle): String = parts(money).let { grouped(it.rupees, style) + paiseSuffix(it, style) }

    /** The number alone, with a leading minus if negative: 12,34,567.89 */
    fun number(money: Money, locale: Locale? = null): String {
        val style = locale?.let { NumberStyle.of(it) } ?: NumberStyle.current
        return (if (money.paise < 0) "-" else "") + plain(money, style)
    }

    /** ₹2,400 or -₹1,850 */
    fun format(money: Money, locale: Locale? = null): String {
        val style = locale?.let { NumberStyle.of(it) } ?: NumberStyle.current
        return (if (money.paise < 0) "-" else "") + RUPEE + plain(money, style)
    }

    /** +₹3,200 or -₹1,850; zero has no sign. */
    fun signed(money: Money, locale: Locale? = null): String = if (money.paise > 0) "+" + format(money, locale) else format(money, locale)

    fun describe(balance: Money, locale: Locale? = null): BalanceView = when {
        balance.paise > 0 -> BalanceView(BalanceKind.OWED, balance, format(balance, locale))
        balance.paise < 0 -> {
            val amount = if (balance.paise == Long.MIN_VALUE) Money(Long.MAX_VALUE) else -balance
            BalanceView(BalanceKind.OWES, amount, format(amount, locale))
        }
        else -> BalanceView(BalanceKind.SETTLED, Money.ZERO, format(Money.ZERO, locale))
    }

    /** For a text field: no symbol and no grouping, 2400 or 2400.50 (with the locale's decimal sign). [MoneyInput.parse] reads it back exactly. */
    fun editable(money: Money, locale: Locale? = null): String {
        val style = locale?.let { NumberStyle.of(it) } ?: NumberStyle.current
        return parts(money).let { (if (it.negative) "-" else "") + it.rupees + paiseSuffix(it, style) }
    }
}
