package owntally.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import owntally.model.EventBody

class OnboardingRulesTest {
    @Test
    fun aNameNeedsOneToFortyCharactersAfterTrimming() {
        assertNull(OnboardingRules.nameProblem("Asha"))
        assertNull(OnboardingRules.nameProblem("  Asha  "))
        assertNull(OnboardingRules.nameProblem("x".repeat(40)))
        assertEquals(NameProblem.EMPTY, OnboardingRules.nameProblem(""))
        assertEquals(NameProblem.EMPTY, OnboardingRules.nameProblem("    "))
        assertEquals(NameProblem.TOO_LONG, OnboardingRules.nameProblem("x".repeat(41)))
        assertNull(OnboardingRules.nameProblem("अशोक ☃"), "any script")
    }

    @Test
    fun aPhoneNumberIsDigitsPlusMinusBracketsDotAndSpacesOfFiveToTwentyCharactersWithAtLeastFiveDigits() {
        for (ok in listOf("98765 43210", "+91 98765 43210", "12345", "1".repeat(20), "  98765 43210 ", "98765-43210", "(98765) 43210", "98765.43210", "+91 (98765) 43-210")) assertNull(OnboardingRules.phoneProblem(ok), ok)
        assertEquals(PhoneProblem.EMPTY, OnboardingRules.phoneProblem(""))
        assertEquals(PhoneProblem.EMPTY, OnboardingRules.phoneProblem("   "))
        assertEquals(PhoneProblem.BAD_LENGTH, OnboardingRules.phoneProblem("1234"))
        assertEquals(PhoneProblem.BAD_LENGTH, OnboardingRules.phoneProblem("1".repeat(21)))
        for (bad in listOf("98765a43210", "9876०१२", "98765_43210", "98765/43210")) {
            assertEquals(PhoneProblem.BAD_CHARACTERS, OnboardingRules.phoneProblem(bad), bad)
        }
        assertEquals(PhoneProblem.TOO_FEW_DIGITS, OnboardingRules.phoneProblem("+(  )-.. +"), "no digits")
        assertEquals(PhoneProblem.TOO_FEW_DIGITS, OnboardingRules.phoneProblem("12-34 +.()"), "four digits")
    }

    @Test
    fun whatTheRulesAcceptIsAcceptedByTheEventRulesToo() {
        // The same limits as the events the name and phone end up in (docs/PROTOCOL.md section 1).
        for ((name, phone) in listOf("Asha" to "98765 43210", "x".repeat(40) to "+91 98765 43210", "A" to "12345")) {
            assertNull(OnboardingRules.nameProblem(name))
            assertNull(OnboardingRules.phoneProblem(phone))
            EventBody.ProfileUpdated(name.trim(), phone.trim())
        }
    }

    @Test
    fun wordsAreComparedIgnoringCaseAndSpaces() {
        assertEquals(true, OnboardingRules.sameWord("  Lemon ", "lemon"))
        assertEquals(true, OnboardingRules.sameWord("HARBOR", "harbor"))
        assertEquals(false, OnboardingRules.sameWord("lemons", "lemon"))
        assertEquals(false, OnboardingRules.sameWord("", "lemon"))
        assertEquals(false, OnboardingRules.sameWord("lem on", "lemon"))
    }

    @Test
    fun threeDistinctPositionsAreChosenInOrder() {
        for (seed in 1..200) {
            val p = OnboardingRules.confirmPositions(kotlin.random.Random(seed))
            assertEquals(3, p.size)
            assertEquals(p.sorted(), p)
            assertEquals(3, p.toSet().size)
            assertEquals(true, p.all { it in 1..12 })
        }
    }

    @Test
    fun allTwelvePositionsGetAskedAboutOverTime() {
        val asked = (1..500).flatMap { OnboardingRules.confirmPositions(kotlin.random.Random(it)) }.toSet()
        assertEquals((1..12).toSet(), asked)
    }
}
