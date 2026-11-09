package owntally.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M9, decision A1: a waiting invite link is opened only once the person is past the lock. The app cannot drive the phone's lock prompt in a test, so the
 * rule is kept structurally: the screen that uses the waiting link is composed only inside the lock, and nothing outside it reads or opens the link.
 */
class PendingInviteLockTest {
    @Test fun everyPlaceTheHomeFlowIsComposedIsInsideTheLock() {
        val lines = File("src/main/kotlin/owntally/ui/OwnTallyApp.kt").readLines().filter { "HomeFlow(" in it }
        assertTrue(lines.size >= 2, "found the places the home flow is composed")
        for (l in lines) assertTrue("WithLock(lock)" in l, "outside the lock: ${l.trim()}")
    }

    @Test fun theWaitingLinkIsReadAndOpenedOnlyInsideTheHomeFlow() {
        val others = File("src/main/kotlin/owntally").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "HomeFlow.kt" }.toList()
        val offenders = others.flatMap { f -> f.readLines().withIndex().filter { Regex("""pending(Invites)?\.(waiting|takeProblem)\(|InviteLinkEffect""").containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }
        assertEquals(emptyList(), offenders)
        val activity = File("../app/src/main/kotlin/owntally/app/MainActivity.kt").readText()
        assertTrue(!Regex("""\.waiting\(|takeProblem|joinGroup\.open""").containsMatchIn(activity), "the activity only hands the link over; it never opens it")
    }
}
