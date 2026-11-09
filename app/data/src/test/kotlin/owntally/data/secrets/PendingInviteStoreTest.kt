package owntally.data.secrets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import owntally.crypto.GroupKey
import owntally.model.GroupId

/** M9, T9.3, decision A1: an invite link that arrives before the person can use it waits in the protected store, one at a time. */
class PendingInviteStoreTest {
    private val blobs = InMemoryBlobStore()
    private val box = AeadSecretBox(newAead())
    private val boxed: SecretStore = BoxedSecretStore(box, blobs)
    private val link = "https://joinowntally.ownspace.in/j#SECRET-CODE-ABC_def"

    private fun both(check: (SecretStore) -> Unit) { check(boxed); check(InMemorySecretStore()) }

    @Test fun nothingWaitsAtFirst() = both { assertEquals(SecretLoad.Missing, it.loadPendingInvite()) }

    @Test fun theLinkAndItsExpiryComeBack() = both {
        it.savePendingInvite(link, 1_800_000_000)
        assertEquals(SecretLoad.Found(PendingInvite(link, 1_800_000_000)), it.loadPendingInvite())
    }

    @Test fun aNewerLinkReplacesTheOldOne() = both {
        it.savePendingInvite(link, 100)
        it.savePendingInvite("$link-2", 200)
        assertEquals(SecretLoad.Found(PendingInvite("$link-2", 200)), it.loadPendingInvite())
    }

    @Test fun clearingForgetsItAndClearingNothingIsFine() = both {
        it.clearPendingInvite()
        it.savePendingInvite(link, 100)
        it.clearPendingInvite()
        assertEquals(SecretLoad.Missing, it.loadPendingInvite())
    }

    @Test fun wipingEverythingForgetsItToo() = both {
        it.savePendingInvite(link, 100)
        it.wipeAll()
        assertEquals(SecretLoad.Missing, it.loadPendingInvite())
    }

    @Test fun theBlobIsSealedAndNeverHoldsTheLinkAsText() {
        boxed.savePendingInvite(link, 100)
        val all = blobs.list("").flatMap { name -> listOf(blobs.get(name)!!) }
        assertTrue(all.isNotEmpty())
        for (blob in all) assertFalse(String(blob, Charsets.ISO_8859_1).contains("SECRET-CODE"), "the invite holds the group key: it is never stored as plain text")
    }

    @Test fun aBlobMovedToAnotherSlotIsNotAcceptedAsAPendingInvite() {
        boxed.saveGroupKey(GroupId(ByteArray(16) { 3 }), 1, GroupKey(ByteArray(32) { 9 }))
        val keyBlob = blobs.list("").single()
        blobs.put("pending-invite", blobs.get(keyBlob)!!)
        assertEquals(SecretLoad.Unreadable, boxed.loadPendingInvite())
    }

    @Test fun aDamagedBlobIsUnreadableNotAnError() {
        boxed.savePendingInvite(link, 100)
        val name = blobs.list("").single()
        blobs.put(name, blobs.get(name)!!.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertEquals(SecretLoad.Unreadable, boxed.loadPendingInvite())
    }

    @Test fun aWaitingInviteDoesNotShowUpAsAGroupKey() {
        boxed.savePendingInvite(link, 100)
        assertEquals(emptySet(), boxed.loadKeyring(GroupId(ByteArray(16) { 3 })).keyring.epochs())
    }
}
