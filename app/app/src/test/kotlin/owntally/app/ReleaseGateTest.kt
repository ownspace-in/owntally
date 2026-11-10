package owntally.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Assume.assumeTrue
import owntally.ui.privacy.PrivacyFacts

/**
 * M10: what must be true before a build goes to the store and cannot be checked by an ordinary run, because it waits for the owner (the operator's name
 * and contact in the privacy text, a real assetlinks.json). It runs only when asked: `OWNTALLY_RELEASE=1 ./gradlew :app:app:testDebugUnitTest --tests '*ReleaseGateTest'`.
 * An ordinary run skips it (JUnit reports it as skipped), so the build stays green while the marks are still there; the release checklist runs it.
 */
class ReleaseGateTest {
    private fun asked() = assumeTrue("the release gate runs only with OWNTALLY_RELEASE=1", System.getenv("OWNTALLY_RELEASE") == "1")

    @Test fun thePrivacyTextHasNoMarkLeftToFillIn() {
        asked()
        val left = (PrivacyFacts.allLines + PrivacyFacts.OPERATOR + PrivacyFacts.CONTACT).filter { PrivacyFacts.PLACEHOLDER_MARK in it }
        assertEquals(emptyList(), left, "the owner fills in the operator's name and the contact address in PrivacyFacts.kt, then rewrites the page (OWNTALLY_WRITE_PRIVACY_PAGE=1)")
        assertFalse(PrivacyFacts.PLACEHOLDER_MARK in File("../../site/privacy/index.html").readText())
    }

    @Test fun theStoreDraftHasNoMarkLeftToFillIn() {
        asked()
        val draft = File("../../docs/STORE_LISTING_DRAFT.md").readText()
        assertFalse("TO FILL IN" in draft, "every TO FILL IN of docs/STORE_LISTING_DRAFT.md is answered")
    }

    @Test fun anAssetlinksFileIfOneIsHereIsNotATemplate() {
        asked()
        val real = File("../../site/.well-known/assetlinks.json")
        if (real.exists()) {
            val text = real.readText()
            assertFalse("REPLACE" in text)
            assertEquals(2, Regex("""([0-9A-F]{2}:){31}[0-9A-F]{2}""").findAll(text).map { it.value }.toSet().size, "assetlinks.json holds two fingerprints: Play's app signing key and the upload key (docs/RELEASE.md)")
        }
    }

    @Test fun theVersionIsARealOne() {
        asked()
        assertFalse(BuildConfig.VERSION_NAME.startsWith("0."), "the first release is not 0.x (BuildConfig.VERSION_NAME is ${BuildConfig.VERSION_NAME})")
    }
}
