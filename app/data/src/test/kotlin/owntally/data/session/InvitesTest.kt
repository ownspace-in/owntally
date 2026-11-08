package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.crypto.InMemoryTicketRecords
import owntally.data.repo.TestEnv
import owntally.model.GroupId

/** T6.3 on real repositories: tickets of a real group, a "restart" being a new Invites over the same records. */
class InvitesTest {
    private val env = TestEnv().also { it.firstRun() }
    private val group = env.groups.createGroup("Goa Trip")
    private val records = InMemoryTicketRecords()
    private fun invites() = Invites(records, env.groups, env.secrets, env.identity, env.clock)

    @Test fun aTicketIssuedForAGroupIsUsableOnceEvenAfterARestart() {
        val ticket = invites().registry(group)!!.issue(group, 1, env.secrets.loadKeyring(group).keyring[1]!!)
        assertEquals(env.identity.groupIdentity(env.groups.group(group)!!.keySlot)!!.publicKey, ticket.hostId)
        assertNotNull(invites().registry(group)!!.find(ticket.ticketId()))
        assertTrue(invites().registry(group)!!.accept(ticket))
        assertFalse(invites().registry(group)!!.accept(ticket), "used before the restart, still used")
    }

    @Test fun theKeyIsReadFromTheKeyringSoTheRecordHoldsNone() {
        val key = env.secrets.loadKeyring(group).keyring[1]!!
        val ticket = invites().registry(group)!!.issue(group, 1, key)
        val record = records.get(ticket.ticketId())!!
        assertFalse(record.javaClass.declaredFields.any { it.type.simpleName.contains("Key") && it.type.simpleName.contains("Group") })
        env.secrets.deleteGroupKey(group, 1)
        assertNull(invites().registry(group)!!.find(ticket.ticketId()), "no key, no ticket")
    }

    @Test fun aGroupThatIsNotThisPhonesHasNoRegistry() {
        assertNull(invites().registry(GroupId(ByteArray(16) { 1 })))
    }
}
