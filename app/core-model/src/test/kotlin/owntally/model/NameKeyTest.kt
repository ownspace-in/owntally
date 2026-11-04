package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NameKeyTest {
    @Test fun capitalsDoNotMatter() {
        assertEquals(NameKey.of("Rohan"), NameKey.of("rohan"))
        assertEquals(NameKey.of("Rohan"), NameKey.of("ROHAN"))
    }

    @Test fun outerAndRepeatedSpacesDoNotMatter() {
        assertEquals(NameKey.of("Rohan V"), NameKey.of("  rohan    v "))
        assertEquals(NameKey.of("Rohan V"), NameKey.of("Rohan\tV"))
        assertEquals(NameKey.of("Rohan V"), NameKey.of("Rohan V"))
    }

    @Test fun invisibleCharactersAndOddSpacesDoNotMakeACopyLookDifferent() {
        assertEquals(NameKey.of("Rohan"), NameKey.of("Roh\u200Ban"))   // zero-width space
        assertEquals(NameKey.of("Rohan"), NameKey.of("Rohan\uFEFF"))   // byte-order mark
        assertEquals(NameKey.of("Rohan"), NameKey.of("\u200BRohan\u200B"))
        assertEquals(NameKey.of("Rohan V"), NameKey.of("Rohan\u2003V")) // em space
        assertEquals(NameKey.of("Rohan V"), NameKey.of("Rohan\u3000V")) // ideographic space
    }

    @Test fun differentNamesStayDifferent() {
        assertNotEquals(NameKey.of("Rohan"), NameKey.of("Rohan V"))
        assertNotEquals(NameKey.of("Rohan V"), NameKey.of("RohanV"))
        assertNotEquals(NameKey.of("Ben"), NameKey.of("Ben 2"))
    }

    @Test fun otherScriptsWork() {
        assertEquals(NameKey.of("रोहन"), NameKey.of(" रोहन "))
        assertEquals(NameKey.of("ПЕТР"), NameKey.of("петр"))
    }

    @Test fun lookalikeLettersAreNotTheSameName() {
        assertNotEquals(NameKey.of("Rohan"), NameKey.of("Rоhan")) // Cyrillic o
    }

    @Test fun theResultDoesNotDependOnThePhonesLanguage() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals("insan", NameKey.of("INSAN"), "no Turkish dotted-i surprise")
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
