package owntally.model

/**
 * What a name (the group's or a member's) may be made of (finding F4, docs/SECURITY_REVIEW.md; owner decision, 10 Nov 2026). Any language, but no
 * invisible character and nothing that changes the direction text is shown in, because they let one name look like another or display reversed.
 * That is every character of the Unicode "format" and "control" kinds (direction marks and overrides, zero-width space, word joiner, soft hyphen,
 * byte-order mark, tag characters, tab, line breaks) and the line and paragraph separators. The two joiners that Persian, Malayalam, Kannada and
 * other scripts need to spell words, ZWNJ and ZWJ, are allowed when they join letters: after a letter or a mark and before a letter or a mark,
 * or at the end of the word after a mark (the Malayalam chillu letters). Not used for the description of an expense.
 */
object NameText {
    const val INVISIBLE = "A name may not hold invisible or direction-changing characters."
    const val JOINER = "A name may use the joining characters of some scripts only between letters."

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    private fun isMark(cp: Int) = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun isLetterOrMark(cp: Int) = Character.isLetter(cp) || isMark(cp)

    /** Null if [name] is acceptable, otherwise the sentence that says what is wrong. */
    fun problem(name: String): String? {
        val cps = name.codePoints().toArray()
        for ((i, cp) in cps.withIndex()) {
            if (cp == ZWNJ || cp == ZWJ) {
                val before = cps.getOrNull(i - 1)
                val after = cps.getOrNull(i + 1)
                if (before == null || !isLetterOrMark(before)) return JOINER
                if (after == null) {
                    if (!isMark(before)) return JOINER
                } else if (!isLetterOrMark(after)) {
                    return JOINER
                }
                continue
            }
            when (Character.getType(cp).toByte()) {
                Character.FORMAT, Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> return INVISIBLE
            }
        }
        return null
    }
}
