package owntally.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M11, T11.1 (owner decision D1, key chaining; docs/PROTOCOL.md "KeyLinked"): after a key change the owner writes a `KeyLinked` event, in the new
 * epoch, that holds the key of the epoch before, so that whoever holds the new key can read back to epoch 1. Replay decides which one counts.
 */
class KeyLinkedTest {
    private val a = key(1)
    private val b = key(2)
    private val prev = PreviousKey(ByteArray(32) { 0x11 })
    private fun Sim.reasonOf(e: Event) = e.reason(state())

    @Test fun theOwnersLinkRightAfterAKeyChangeIsAcceptedAndRemembered() {
        val s = standardGroup()
        s.rotate(a)
        val link = s.emit(a, EventBody.KeyLinked(2, prev))
        assertNull(s.reasonOf(link))
        assertEquals(link.id, s.state().keyLinks[2L])
        assertEquals(2L, s.state().epoch)
    }

    @Test fun aRemovalThatStartsAnEpochCanBeLinkedToo() {
        val s = standardGroup()
        s.remove(a, key(4))
        val link = s.emit(a, EventBody.KeyLinked(2, prev))
        assertNull(s.reasonOf(link))
        assertEquals(link.id, s.state().keyLinks[2L])
    }

    @Test fun onlyTheOwnerMayLinkAnEpoch() {
        val s = standardGroup()
        s.emit(a, EventBody.AdminAdded(b))
        s.rotate(a)
        assertEquals(IgnoreReason.NOT_ALLOWED, s.reasonOf(s.emit(b, EventBody.KeyLinked(2, prev))), "an admin")
        assertEquals(IgnoreReason.NOT_ALLOWED, s.reasonOf(s.emit(key(3), EventBody.KeyLinked(2, prev))), "a plain member")
        assertEquals(emptyMap(), s.state().keyLinks)
    }

    @Test fun theEpochMustBeTheOneTheAuthorWasIn() {
        val s = standardGroup()
        // Epoch 1: nothing to link to.
        assertEquals(IgnoreReason.INVALID, s.reasonOf(s.emit(a, EventBody.KeyLinked(2, prev))), "no key change yet")
        s.rotate(a)
        assertEquals(IgnoreReason.INVALID, s.reasonOf(s.emit(a, EventBody.KeyLinked(3, prev))), "an epoch that does not exist yet")
    }

    @Test fun onlyTheFirstLinkOfAnEpochCounts() {
        val s = standardGroup()
        s.rotate(a)
        val first = s.emit(a, EventBody.KeyLinked(2, prev))
        val second = s.emit(a, EventBody.KeyLinked(2, PreviousKey(ByteArray(32) { 0x22 })))
        assertEquals(IgnoreReason.ALREADY_DONE, s.reasonOf(second))
        assertEquals(first.id, s.state().keyLinks[2L], "a second link cannot replace the first")
    }

    @Test fun eachEpochHasItsOwnLink() {
        val s = standardGroup()
        s.rotate(a); val l2 = s.emit(a, EventBody.KeyLinked(2, prev))
        s.rotate(a); val l3 = s.emit(a, EventBody.KeyLinked(3, PreviousKey(ByteArray(32) { 0x33 })))
        assertEquals(mapOf(2L to l2.id, 3L to l3.id), s.state().keyLinks)
    }

    @Test fun aLinkChangesNothingElseInTheGroup() {
        val s = standardGroup()
        s.rotate(a)
        val before = s.state().copy(keyLinks = emptyMap())
        s.emit(a, EventBody.KeyLinked(2, prev))
        assertEquals(before.copy(ignored = before.ignored), s.state().copy(keyLinks = emptyMap()))
    }
}
