package owntally.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M10, T10.4: layout rules that keep a screen usable at a large font size. A button with a fixed height cuts its label when the label wraps, so a
 * button has a minimum height (`heightIn(min = ...)`), never a fixed one. (Icon-only controls and spacers may be fixed.)
 */
class LayoutRulesTest {
    private val sources = File("src/main/kotlin/owntally/ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun noButtonHasAFixedHeight() {
        val offenders = sources.flatMap { f ->
            f.readLines().withIndex().filter { l ->
                Regex("""\.height\((4\d|5\d|6\d)\.dp\)""").containsMatchIn(l.value) && Regex("""Button|clickable|ActionButton""").containsMatchIn(l.value) && "Spacer" !in l.value
            }.map { "${f.name}:${it.index + 1}: ${it.value.trim().take(120)}" }
        }
        assertEquals(emptyList(), offenders)
    }
}
