package owntally.model.format

import owntally.model.Money

enum class AmountProblem { BAD_CHARACTER, MISPLACED_SEPARATOR, TOO_MANY_DECIMALS, TOO_LARGE }

sealed class ParsedAmount {
    data class Valid(val money: Money) : ParsedAmount()

    /** Nothing typed yet. Not an error. */
    data object Empty : ParsedAmount()
    data class Invalid(val problem: AmountProblem) : ParsedAmount()
}

/** Reads an amount a person typed. Never throws, never rounds: a third decimal is refused. */
object MoneyInput {
    /** ₹100 crore. A sanity limit that also keeps every sum far from overflowing. */
    val MAX = Money(100_000_000_000)

    fun parse(text: String): ParsedAmount {
        var t = text.trim()
        if (t.startsWith(MoneyFormat.RUPEE)) t = t.removePrefix(MoneyFormat.RUPEE).trim()
        if (t.isEmpty()) return ParsedAmount.Empty
        if (t.any { it !in '0'..'9' && it != ',' && it != '.' }) return ParsedAmount.Invalid(AmountProblem.BAD_CHARACTER)

        val dot = t.indexOf('.')
        if (dot >= 0 && t.indexOf('.', dot + 1) >= 0) return ParsedAmount.Invalid(AmountProblem.MISPLACED_SEPARATOR)
        val whole = if (dot < 0) t else t.substring(0, dot)
        val decimals = if (dot < 0) "" else t.substring(dot + 1)

        if (',' in decimals) return ParsedAmount.Invalid(AmountProblem.MISPLACED_SEPARATOR)
        if (whole.isEmpty() && decimals.isEmpty()) return ParsedAmount.Invalid(AmountProblem.MISPLACED_SEPARATOR)
        if (whole.startsWith(",") || whole.endsWith(",") || ",," in whole) return ParsedAmount.Invalid(AmountProblem.MISPLACED_SEPARATOR)
        if (decimals.length > 2) return ParsedAmount.Invalid(AmountProblem.TOO_MANY_DECIMALS)

        val digits = whole.replace(",", "").trimStart('0')
        if (digits.length > 10) return ParsedAmount.Invalid(AmountProblem.TOO_LARGE)
        val rupees = if (digits.isEmpty()) 0L else digits.toLong()
        val paise = decimals.padEnd(2, '0').toLong()
        val total = rupees * 100 + paise
        return if (total > MAX.paise) ParsedAmount.Invalid(AmountProblem.TOO_LARGE) else ParsedAmount.Valid(Money(total))
    }
}
