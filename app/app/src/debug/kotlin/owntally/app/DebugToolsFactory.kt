package owntally.app

import owntally.crypto.KeyDerivation
import owntally.data.secrets.SecretLoad
import owntally.data.session.AddResult
import owntally.data.session.GroupSession
import owntally.model.EventBody
import owntally.model.Money
import owntally.model.Split
import owntally.ui.groups.DebugTools

/**
 * Debug builds only: puts two test friends and a few expenses in a group so the screens can be tried before joining by link
 * exists (M4). The friends are made from this phone's own seed, at slots far above those real groups use, and are named "(test)".
 * A group never has more than three members here, the limit until M4.
 */
internal object DebugToolsFactory {
    private const val FIRST_TEST_SLOT = 2_000_000L

    fun create(container: AppContainer): DebugTools = object : DebugTools {
        override fun addTestData(session: GroupSession) {
            val seed = (container.secrets.loadMasterSeed() as? SecretLoad.Found)?.value ?: return
            val state = session.view.value.state
            if (state.currentMembers.size > 1) return // already has friends: do nothing twice
            val me = session.view.value.me
            val friends = listOf("Ben (test)", "Meera (test)").mapIndexed { i, name ->
                val id = KeyDerivation.groupIdentity(seed, FIRST_TEST_SLOT + i)
                session.append(EventBody.MemberJoined(id.publicKey, id.encryptionKey, name, "98765 0000$i"))
                id.publicKey
            }
            val (ben, meera) = friends
            val day = System.currentTimeMillis() / 86_400_000L
            val all = setOf(me, ben, meera)
            check(session.addExpense("Beach shack dinner", Money(4_800_00), me, day, Split.Equal(all)) is AddResult.Added)
            check(session.addExpense("Villa, 3 nights", Money(21_000_00), meera, day - 1, Split.Equal(all)) is AddResult.Added)
            check(session.addExpense("Cab from airport", Money(1_900_00), me, day - 2, Split.Equal(setOf(me, ben))) is AddResult.Added)
        }
    }
}
