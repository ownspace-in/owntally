package owntally.model.format

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * How numbers are written on this phone: the decimal sign, the sign that separates groups of digits, and whether groups are
 * thousands then pairs (the lakh style of India: 12,34,567) or plain thousands (1,234,567). It follows the phone's language and
 * region. Digits are always 0 to 9, and only "." "," and spaces are used as signs, so that anything a phone shows can be read
 * back by [MoneyInput]; a language that uses other signs falls back to "." and ",".
 */
data class NumberStyle(val decimal: Char, val group: Char, val lakh: Boolean) {
    companion object {
        private val GROUPS = setOf('.', ',', ' ', ' ', ' ')

        fun of(locale: Locale): NumberStyle {
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val dec = symbols.decimalSeparator
            val grp = symbols.groupingSeparator
            val usable = (dec == '.' || dec == ',') && grp in GROUPS && grp != dec
            val lakh = locale.country == "IN" || locale.language == "hi"
            return if (usable) NumberStyle(dec, grp, lakh) else NumberStyle('.', ',', lakh)
        }

        val current: NumberStyle get() = of(Locale.getDefault())
    }
}
