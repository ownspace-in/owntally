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
    /** The same limit the event rules enforce ([Money.MAX_AMOUNT]): 100,000,000 whole units. */
    val MAX = Money.MAX_AMOUNT

    /**
     * Reads what a person typed, with the decimal sign and grouping of [locale] (the phone's, if none is given). Where the decimal
     * sign is a comma, a comma means decimals, never thousands: "2,5" is two and a half rupees, not 25.
     */
    fun parse(text: String, locale: java.util.Locale? = null): ParsedAmount {
        val style = locale?.let { NumberStyle.of(it) } ?: NumberStyle.current
        var t = text.trim()
        if (t.startsWith(MoneyFormat.RUPEE)) t = t.removePrefix(MoneyFormat.RUPEE).trim()
        if (t.isEmpty()) return ParsedAmount.Empty
        t = canonical(t, style)
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

    /** Rewrites [t] into the form the rest of [parse] reads: "." for decimals and "," between groups. */
    private fun canonical(t: String, style: NumberStyle): String {
        if (style.group.isWhitespace()) return canonical(t.filterNot { it.isWhitespace() }, style.copy(group = if (style.decimal == ',') '.' else ','))
        if (style.decimal == '.') return t
        // The decimal sign is a comma. A real comma is the decimal sign and a dot is a group.
        if (',' in t) return t.map { if (it == ',') '.' else if (it == '.') ',' else it }.joinToString("")
        // No comma: a dot is a group only if every group after it has exactly three digits ("2.400"); otherwise it is a decimal dot ("2.5").
        val parts = t.split('.')
        val isGrouping = parts.size > 1 && parts.drop(1).all { it.length == 3 && it.all { c -> c in '0'..'9' } }
        return if (isGrouping) t.replace('.', ',') else t
    }
}
