package owntally.model

import java.util.Locale

/**
 * What "the same name" means inside a group (owner decision, 4 Nov 2026): capitals and extra spaces do not count, so "Rohan",
 * "rohan", " Rohan ", "Rohan V" with a non-breaking space or invisible characters and "ROHAN" are one name, and "Rohan V" is another. Letters that merely look alike (a Cyrillic "о" for a
 * Latin "o") are not treated as the same.
 */
object NameKey {
    fun of(name: String): String {
        val visible = StringBuilder()
        for (c in name) {
            when {
                // Invisible characters (zero-width space, joiners, byte-order mark) must not make a copy look different.
                Character.getType(c) == Character.FORMAT.toInt() -> Unit
                // Every kind of space, including the non-breaking space, counts as an ordinary space.
                c.isWhitespace() || Character.isSpaceChar(c) -> visible.append(' ')
                else -> visible.append(c)
            }
        }
        return visible.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ").lowercase(Locale.ROOT)
    }
}
