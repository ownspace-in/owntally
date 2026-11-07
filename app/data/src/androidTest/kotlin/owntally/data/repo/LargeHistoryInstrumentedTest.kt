package owntally.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import owntally.crypto.Clock
import owntally.crypto.EnvelopeSigning
import owntally.crypto.KeyDerivation
import owntally.crypto.MasterSeed
import owntally.data.db.OwnTallyDatabase
import owntally.data.secrets.BoxedSecretStore
import owntally.data.secrets.FileBlobStore
import owntally.data.secrets.KeystoreSecretBox
import owntally.data.session.RepositorySyncStore
import owntally.data.store.GroupRow
import owntally.data.store.RoomEventStore
import owntally.data.store.RoomSettingsStore
import owntally.data.store.StoredEnvelope
import owntally.model.Envelope
import owntally.model.GroupId
import owntally.model.PublicKey
import owntally.sync.GroupRoom
import owntally.sync.InMemoryNetwork
import owntally.sync.Offered
import owntally.sync.SyncEngine
import owntally.sync.SyncOutcome
import owntally.sync.SyncRole
import owntally.sync.SyncStore

/**
 * T5.11 on the emulator: a history of 5,000 envelopes goes from one database to another, the receiving app is killed part way
 * (twice), each restart opens the database file again, and in the end both databases hold the same envelopes.
 */
@RunWith(AndroidJUnit4::class)
class LargeHistoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alias = "owntally-largehistory-test-key"
    private val secretsDir = File(context.noBackupFilesDir, "secrets-largehistory-test")
    private val group = GroupId(ByteArray(16) { 8 })
    private val room = GroupRoom.fromGroupId(group.bytes())
    private val seed = MasterSeed(ByteArray(64) { 5 })

    private class Killed : RuntimeException()
    private class Dying(val inner: SyncStore, val dieAfter: Int) : SyncStore by inner {
        var stored = 0
        override fun offer(bytes: ByteArray): Offered {
            val r = inner.offer(bytes)
            if (r == Offered.Stored && ++stored >= dieAfter) throw Killed()
            return r
        }
    }

    private class Phone(val db: OwnTallyDatabase, val repo: GroupRepository)

    private fun open(name: String): Phone {
        val db = Room.databaseBuilder(context, OwnTallyDatabase::class.java, name).build()
        val secrets = BoxedSecretStore(KeystoreSecretBox.create(alias), FileBlobStore(secretsDir))
        val identity = IdentityRepository(secrets, RoomSettingsStore(db))
        return Phone(db, GroupRepository(secrets, RoomEventStore(db), identity, Clock.SYSTEM, SecureRandom()))
    }

    private fun wipe(vararg names: String) {
        for (n in names) context.deleteDatabase(n)
        secretsDir.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    private fun envelope(author: Int, counter: Long): StoredEnvelope {
        val e = EnvelopeSigning.sign(KeyDerivation.groupIdentity(seed, author.toLong()), 1, group, 1, counter, ByteArray(24) { author.toByte() }, ByteArray(200) { (counter + it).toByte() })
        return StoredEnvelope(e.author, e.counter, e.encode())
    }

    private suspend fun sync(a: SyncStore, b: SyncStore): Pair<SyncOutcome?, SyncOutcome?> {
        val keyA = PublicKey(ByteArray(32) { 1 })
        val keyB = PublicKey(ByteArray(32) { 2 })
        val net = InMemoryNetwork()
        val incoming = net.transportFor(keyB).incoming(room)
        val la = net.transportFor(keyA).connect(room, keyB)
        val lb = incoming.first()
        return coroutineScope {
            val x = async { runCatching { SyncEngine(room, a).run(la, SyncRole.INITIATOR).outcome }.getOrNull() }
            val y = async { runCatching { SyncEngine(room, b).run(lb, SyncRole.RESPONDER).outcome }.getOrNull() }
            x.await() to y.await()
        }
    }

    @Test
    fun fiveThousandEnvelopesSurviveTheReceiverBeingKilledTwiceAndEndIdentical() = runBlocking {
        val nameA = "owntally-large-a.db"
        val nameB = "owntally-large-b.db"
        wipe(nameA, nameB)
        try {
            val source = open(nameA)
            val all = (1..5).flatMap { a -> (1L..1000L).map { c -> envelope(a, c) } }
            source.db.runInTransaction { RoomEventStore(source.db).addGroup(GroupRow(group, 0, 0), all) }
            assertEquals(5000, source.repo.rawEnvelopes(group).size)

            var receiver = open(nameB)
            RoomEventStore(receiver.db).addGroup(GroupRow(group, 0, 0), emptyList())

            for (dieAfter in listOf(1700, 900)) {
                val dying = Dying(RepositorySyncStore(group, receiver.repo), dieAfter)
                sync(RepositorySyncStore(group, source.repo), dying)
                assertEquals(dieAfter, dying.stored)
                // The process is gone: close the file and open it again.
                receiver.db.close()
                receiver = open(nameB)
            }
            val held = receiver.repo.rawEnvelopes(group).size
            assertEquals(1700 + 900, held, "everything stored before each kill is on disk, nothing more")

            val (x, y) = sync(RepositorySyncStore(group, source.repo), RepositorySyncStore(group, receiver.repo))
            assertEquals(SyncOutcome.COMPLETED, x)
            assertEquals(SyncOutcome.COMPLETED, y)
            val a = source.repo.rawEnvelopes(group).map { it.toList() }.toSet()
            val b = receiver.repo.rawEnvelopes(group).map { it.toList() }.toSet()
            assertEquals(5000, b.size)
            assertTrue(a == b, "both databases hold the same envelopes")
            source.db.close(); receiver.db.close()
        } finally {
            wipe(nameA, nameB)
        }
    }
}
