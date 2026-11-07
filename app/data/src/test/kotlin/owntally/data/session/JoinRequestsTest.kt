package owntally.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import owntally.crypto.KeyDerivation
import owntally.data.repo.TestEnv
import owntally.data.secrets.SecretLoad
import owntally.data.store.InMemoryJoinRequestStore
import owntally.data.store.JoinState
import owntally.model.MAX_MEMBERS
import owntally.model.Role

class JoinRequestsTest {
    private val env = TestEnv().also { it.firstRun("Asha", "98765 43210") }
    private val sessions = Sessions(env.groups, env.secrets, env.identity, env.clock, env.settings)
    private val store = InMemoryJoinRequestStore()
    private val requests = JoinRequests(store, sessions, env.clock)
    private val group = sessions.createGroup("Goa Trip", "INR", 0)

    private fun person(n: Long) = KeyDerivation.groupIdentity((env.secrets.loadMasterSeed() as SecretLoad.Found).value, 3_000_000 + n)
    private fun ask(n: Long, name: String = "Ben", phone: String = "98765 0000$n") =
        person(n).let { requests.receive(group, it.publicKey, it.encryptionKey, name, phone) }

    @Test fun aNewRequestIsWaitingAndListed() {
        assertEquals(ReceiveResult.New, ask(1))
        val r = requests.waiting(group).single()
        assertEquals("Ben", r.name)
        assertEquals(JoinState.WAITING, r.state)
        assertEquals(env.nowSeconds, r.receivedAtSeconds)
    }

    @Test fun theSamePersonAskingTwiceStaysOneRequestWithTheNewerDetails() {
        ask(1, "Ben")
        env.nowSeconds += 60
        assertEquals(ReceiveResult.Updated, ask(1, "Benny"))
        val r = requests.waiting(group).single()
        assertEquals("Benny", r.name)
        assertEquals(env.nowSeconds, r.receivedAtSeconds)
    }

    @Test fun approvingWritesTheJoinWithTheNameTheAdminChoseAndMarksTheRequest() {
        ask(1, "Ben")
        val result = requests.approve(group, person(1).publicKey, "Ben R")
        assertTrue(result is ApproveResult.Approved)
        val member = sessions.session(group).view.value.state.currentMembers.getValue(person(1).publicKey)
        assertEquals("Ben R", member.name)
        assertEquals("98765 00001", member.phone)
        assertEquals(Role.MEMBER, member.role)
        assertEquals(emptyList(), requests.waiting(group))
        assertEquals(JoinState.APPROVED, store.get(group, person(1).publicKey)!!.state)
    }

    @Test fun aNameAlreadyTakenIsRefusedAndTheRequestKeepsWaiting() {
        ask(1, "Asha")
        val result = requests.approve(group, person(1).publicKey, "asha")
        assertTrue(result is ApproveResult.Failed && result.result is AddResult.Refused)
        assertEquals(1, requests.waiting(group).size)
    }

    @Test fun aBadNameIsInvalidAndTheRequestKeepsWaiting() {
        ask(1)
        val result = requests.approve(group, person(1).publicKey, "  ")
        assertTrue(result is ApproveResult.Failed && result.result is AddResult.Invalid)
        assertEquals(1, requests.waiting(group).size)
    }

    @Test fun decliningMarksItDeclinedAndWritesNoEvent() {
        ask(1)
        val before = env.groups.envelopes(group).envelopes.size
        requests.decline(group, person(1).publicKey)
        assertEquals(emptyList(), requests.waiting(group))
        assertEquals(JoinState.DECLINED, store.get(group, person(1).publicKey)!!.state)
        assertEquals(before, env.groups.envelopes(group).envelopes.size)
    }

    @Test fun aDeclinedPersonCanAskAgain() {
        ask(1)
        requests.decline(group, person(1).publicKey)
        assertEquals(ReceiveResult.New, ask(1))
        assertEquals(1, requests.waiting(group).size)
    }

    @Test fun anApprovedPersonAskingAgainIsAlreadyInTheGroup() {
        ask(1)
        requests.approve(group, person(1).publicKey, "Ben")
        assertEquals(ReceiveResult.AlreadyMember, ask(1))
        assertEquals(emptyList(), requests.waiting(group))
    }

    @Test fun approvingOrDecliningSomethingThatIsNotWaitingDoesNothing() {
        assertEquals(ApproveResult.NotWaiting, requests.approve(group, person(9).publicKey, "Zed"))
        ask(1)
        requests.approve(group, person(1).publicKey, "Ben")
        assertEquals(ApproveResult.NotWaiting, requests.approve(group, person(1).publicKey, "Ben"))
        requests.decline(group, person(1).publicKey)
        assertEquals(JoinState.APPROVED, store.get(group, person(1).publicKey)!!.state, "decline does not undo an approval")
    }

    @Test fun aFullGroupSaysSoAndTheRequestKeepsWaiting() {
        for (n in 1L until MAX_MEMBERS) {
            ask(n, "P$n")
            assertTrue(requests.approve(group, person(n).publicKey, "P$n") is ApproveResult.Approved, "member $n")
        }
        assertEquals(MAX_MEMBERS, sessions.session(group).view.value.state.currentMembers.size)
        ask(99, "Late")
        assertEquals(ApproveResult.GroupFull, requests.approve(group, person(99).publicKey, "Late"))
        assertEquals(1, requests.waiting(group).size)
    }

    @Test fun requestsOfOneGroupAreNotShownInAnother() {
        ask(1)
        val other = sessions.createGroup("Other", "INR", 1)
        assertEquals(emptyList(), requests.waiting(other))
    }
}
