package owntally.ui.groups

import java.security.SecureRandom
import owntally.crypto.Clock
import owntally.crypto.KeyDerivation
import owntally.data.repo.GroupRepository
import owntally.data.repo.IdentityRepository
import owntally.data.secrets.InMemorySecretStore
import owntally.data.secrets.SecretLoad
import owntally.data.session.GroupSession
import owntally.data.session.Sessions
import owntally.data.store.InMemoryEventStore
import owntally.data.store.InMemorySettingsStore
import owntally.model.EventBody
import owntally.model.GroupId
import owntally.model.Money
import owntally.model.PublicKey
import owntally.model.Split

/** A whole app's storage in memory, with first run done, for tests of what the screens show. */
class TestWorld(name: String = "Asha", phone: String = "98765 43210") {
    val secrets = InMemorySecretStore()
    val settings = InMemorySettingsStore()
    val events = InMemoryEventStore()
    var nowSeconds = 1_800_000_000L
    val clock = Clock { nowSeconds }
    val identity = IdentityRepository(secrets, settings).also { it.completeFirstRun(it.newPhrase(), name, phone) }
    val groups = GroupRepository(secrets, events, identity, clock, SecureRandom())
    val sessions = Sessions(groups, secrets, identity, clock, settings)

    fun rupees(n: Long) = Money(n * 100)

    /** A friend who joins the group (written by the owner, as the debug test members are). */
    fun addFriend(session: GroupSession, name: String, slot: Long): PublicKey {
        val seed = (secrets.loadMasterSeed() as SecretLoad.Found).value
        val friend = KeyDerivation.groupIdentity(seed, slot)
        session.append(EventBody.MemberJoined(friend.publicKey, friend.encryptionKey, name, "98765 %05d".format(slot % 100_000)))
        return friend.publicKey
    }

    fun expense(session: GroupSession, description: String, total: Money, paidBy: PublicKey, vararg split: PublicKey, day: Long = 20_000) =
        session.addExpense(description, total, paidBy, day, Split.Equal(split.toSet()))

    fun newGroup(name: String = "Goa Trip", colour: Int? = 0): GroupId = sessions.createGroup(name, "INR", colour)
}
