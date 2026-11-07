package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals

class KeyCoverageTest {
    private val a = key(1)
    private val b = key(2)
    private val c = key(3)
    private fun sealedFor(keys: Collection<PublicKey>) = keys.associateWith { sealed(it.bytes()[0].toInt()) }
    private fun needed(s: Sim) = keyChangeNeeded(s.log(), s.state())

    @Test fun aGroupWithNoLeaveAndNoKeyChangeNeedsNone() = assertEquals(false, needed(standardGroup()))

    @Test fun aLeaveTheCurrentKeyDidNotSeeNeedsOneAndTheNewKeyCoversIt() {
        val s = standardGroup()
        s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()))
        assertEquals(true, needed(s))
        s.rotate(a)
        assertEquals(false, needed(s))
    }

    @Test fun aLeaveSeenByTheKeyChangeIsCovered() {
        val s = standardGroup()
        val seen = s.snapshot()
        s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
        s.rotate(a) // the owner had seen the leave
        assertEquals(false, needed(s))
    }

    @Test fun aMemberAdmittedAtTheSameTimeAsTheKeyChangeNeedsAnotherKeyChange() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(c))
        val seen = s.snapshot()
        val members = s.state().currentMembers.keys
        s.emit(c, EventBody.MemberJoined(key(5), encKeyFor(key(5)), "Eve", PHONE), seen)
        s.emit(a, EventBody.KeyRotated(2, sealedFor(members)), seen) // Eve is not in it, and Chitra could not have given her the key
        assertEquals(true, needed(s))
        s.rotate(a) // the owner now sees Eve
        assertEquals(false, needed(s))
    }

    @Test fun aMemberAdmittedAfterTheKeyChangeWasSeenIsNotAGap() {
        val s = standardGroup()
        s.rotate(a)
        s.join(a, key(5), "Eve") // written by someone who had the new key: it is handed over with the history
        assertEquals(false, needed(s))
    }

    @Test fun aMemberAdmittedByAnAdminWhoHadSeenTheKeyChangeIsNotAGap() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(c))
        s.rotate(a)
        s.emit(c, EventBody.MemberJoined(key(5), encKeyFor(key(5)), "Eve", PHONE)) // Chitra saw everything so far, the key change included
        assertEquals(false, needed(s))
    }

    @Test fun aLeaverIsCoveredWhenTheOwnersRemovalOfThemStartedTheKeyEvenIfItNeverSawTheLeave() {
        val s = standardGroup()
        val seen = s.snapshot()
        val members = s.state().currentMembers.keys
        s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
        s.emit(a, EventBody.MemberRemoved(b, 2, sealedFor(members - b)), seen) // not sealed to Ben, so he does not hold this key
        assertEquals(false, needed(s))
    }

    @Test fun aLeaverWhoWasSealedToByTheCurrentKeyIsAGap() {
        val s = standardGroup()
        val seen = s.snapshot()
        val members = s.state().currentMembers.keys
        s.emit(b, EventBody.MemberRemoved(b, 1, emptyMap()), seen)
        s.emit(a, EventBody.KeyRotated(2, sealedFor(members)), seen) // sealed to Ben, who has left
        assertEquals(true, needed(s))
    }

    @Test fun aRemovalThatIsTheKeyStartCoversEveryoneItSealedTo() {
        val s = standardGroup()
        s.remove(a, b)
        assertEquals(false, needed(s))
    }
}
