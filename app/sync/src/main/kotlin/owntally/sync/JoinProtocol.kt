package owntally.sync

import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import owntally.crypto.GroupIdentity
import owntally.crypto.JoinCrypto
import owntally.crypto.Ticket
import owntally.model.GroupId
import owntally.model.PublicKey

/** What the host phone gives the join protocol (the data layer provides it in T6.6). */
interface JoinHostBackend {
    val group: GroupId

    /** The host's key for this group: the one named in every ticket it issues. */
    val host: GroupIdentity

    /** A ticket this host issued, not yet used and not expired; null otherwise. It is not used up by asking. */
    fun issued(ticketId: ByteArray): Ticket?

    /** Uses the ticket up. True exactly once. */
    fun consume(ticket: Ticket): Boolean

    /** Keeps the request for the host's person to approve or decline. False if it cannot be kept (for example the person is already a member). */
    fun submit(request: JoinRequestBody): Boolean

    fun statusOf(joinerKey: PublicKey): JoinStatus

    /** What the group looks like to someone invited (decision J1): its name, the owner's name and how many people are in it. */
    fun preview(): GroupPreview
}

/**
 * The host's side of joining (docs/tasks/M6-nearby-invites.md, T6.2). One connection, one conversation:
 * the joiner says which ticket; the host answers with a fresh nonce and a signature proving its key (for any ticket id, so a wrong
 * one is not told apart yet); the joiner sends a request sealed with the ticket's group key; the host opens it, checks the
 * joiner's signature, uses the ticket up and keeps the request. Every failure gets the same answer: `Reply(false)`.
 */
class JoinHost(
    private val backend: JoinHostBackend,
    private val room: GroupRoom,
    private val stallTimeoutMs: Long = 30_000,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun serve(link: Link) = coroutineScope {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val pump = launch {
            try { link.received.collect { inbox.send(it) } } finally { inbox.close() }
        }
        try {
            conversation(link, inbox)
        } finally {
            pump.cancel()
        }
    }

    private suspend fun next(inbox: Channel<ByteArray>): JoinMessage? {
        val bytes = withTimeoutOrNull(stallTimeoutMs) { inbox.receiveCatching() }?.getOrNull() ?: return null
        return JoinCodec.decode(bytes)
    }

    private suspend fun send(link: Link, m: JoinMessage): Boolean = try {
        link.send(JoinCodec.encode(m))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun conversation(link: Link, inbox: Channel<ByteArray>) {
        when (val first = next(inbox)) {
            is JoinMessage.Hello -> {
                if (first.room != room) return
                val nonce = JoinCrypto.newNonce(random)
                if (!send(link, JoinMessage.Challenge(nonce, JoinCrypto.signHost(backend.host, backend.group, first.ticketId, nonce)))) return
                var message = next(inbox)
                if (message is JoinMessage.PreviewRequest) {
                    // Only for a ticket that still works; asking does not use it up. Anything else is the same refusal as everywhere.
                    val ticket = backend.issued(first.ticketId)
                    val sealed = ticket?.let { JoinCrypto.sealPreview(it.groupKey, first.ticketId, backend.host.publicKey, nonce, backend.preview().encode()) }
                    if (!send(link, if (sealed != null) JoinMessage.Preview(sealed) else JoinMessage.Reply(false))) return
                    if (sealed == null) return
                    message = next(inbox)
                }
                val request = message as? JoinMessage.Request ?: return
                send(link, JoinMessage.Reply(accept(first.ticketId, nonce, request.sealed)))
            }
            is JoinMessage.StatusHello -> {
                if (first.room != room) return
                val nonce = JoinCrypto.newNonce(random)
                if (!send(link, JoinMessage.StatusChallenge(nonce))) return
                val proof = next(inbox) as? JoinMessage.StatusProof ?: return
                val ok = JoinCrypto.verifyStatus(first.joinerKey, backend.group, backend.host.publicKey, nonce, proof.signature)
                send(link, JoinMessage.StatusReply(if (ok) backend.statusOf(first.joinerKey) else JoinStatus.UNKNOWN))
            }
            else -> return
        }
    }

    private fun accept(ticketId: ByteArray, nonce: ByteArray, sealed: ByteArray): Boolean {
        val ticket = backend.issued(ticketId) ?: return false
        val plain = JoinCrypto.openRequest(ticket.groupKey, ticketId, backend.host.publicKey, nonce, sealed) ?: return false
        if (plain.size <= JoinLimits.SIGNATURE_SIZE) return false
        val bodyBytes = plain.copyOfRange(0, plain.size - JoinLimits.SIGNATURE_SIZE)
        val signature = plain.copyOfRange(plain.size - JoinLimits.SIGNATURE_SIZE, plain.size)
        val body = JoinRequestBody.decode(bodyBytes) ?: return false
        if (!JoinCrypto.verifyRequest(body.joinerKey, backend.group, backend.host.publicKey, nonce, bodyBytes, signature)) return false
        if (!backend.consume(ticket)) return false
        return backend.submit(body)
    }
}

/** How a joiner's attempt ended. */
enum class JoinOutcome {
    /** The host kept the request: wait for the host's person to approve. */
    REQUESTED,

    /** The host did not keep it (a ticket that is expired, used or unknown all look alike). */
    REFUSED,

    /** The peer could not prove it holds the key the ticket names: nothing was sent that it could use. */
    HOST_NOT_VERIFIED,
    INTERRUPTED,
    TIMED_OUT,
}

class JoinStatusResult(val outcome: JoinOutcome?, val status: JoinStatus?)

/** What opening an invite found out: the group's description, or why not ([outcome] is REFUSED for a ticket that no longer works). */
class JoinPreviewResult(val outcome: JoinOutcome?, val preview: GroupPreview?)

/**
 * The joiner's side. The group key is used only to seal the request; it is never sent. The request is sent only after the host has
 * proved it holds the key named in the ticket.
 */
class JoinClient(
    private val ticket: Ticket,
    private val identity: GroupIdentity,
    private val name: String,
    private val phone: String,
    private val stallTimeoutMs: Long = 30_000,
) {
    private val room get() = GroupRoom.fromGroupId(ticket.groupId.bytes())

    private class Ended(val outcome: JoinOutcome) : Exception(null, null, false, false)

    private suspend fun <T> conversation(link: Link, body: suspend (send: suspend (JoinMessage) -> Unit, next: suspend () -> JoinMessage) -> T): T = coroutineScope {
        val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        val pump = launch {
            try { link.received.collect { inbox.send(it) } } finally { inbox.close() }
        }
        try {
            body(
                { m ->
                    try {
                        link.send(JoinCodec.encode(m))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw Ended(JoinOutcome.INTERRUPTED)
                    }
                },
                {
                    val got = withTimeoutOrNull(stallTimeoutMs) { inbox.receiveCatching() } ?: throw Ended(JoinOutcome.TIMED_OUT)
                    val bytes = got.getOrNull() ?: throw Ended(JoinOutcome.INTERRUPTED)
                    JoinCodec.decode(bytes) ?: throw Ended(JoinOutcome.REFUSED)
                },
            )
        } finally {
            pump.cancel()
        }
    }

    suspend fun requestToJoin(link: Link): JoinOutcome = try {
        conversation(link) { send, next ->
            send(JoinMessage.Hello(room, ticket.ticketId()))
            val challenge = next() as? JoinMessage.Challenge ?: throw Ended(JoinOutcome.REFUSED)
            if (!JoinCrypto.verifyHost(ticket.hostId, ticket.groupId, ticket.ticketId(), challenge.nonce, challenge.signature)) throw Ended(JoinOutcome.HOST_NOT_VERIFIED)
            val body = JoinRequestBody(identity.publicKey, identity.encryptionKey, name, phone).encode()
            val signature = JoinCrypto.signRequest(identity, ticket.groupId, ticket.hostId, challenge.nonce, body)
            send(JoinMessage.Request(JoinCrypto.sealRequest(ticket.groupKey, ticket.ticketId(), ticket.hostId, challenge.nonce, body + signature)))
            val reply = next() as? JoinMessage.Reply ?: throw Ended(JoinOutcome.REFUSED)
            if (reply.accepted) JoinOutcome.REQUESTED else JoinOutcome.REFUSED
        }
    } catch (e: Ended) {
        e.outcome
    }

    /** Asks the host, after it has proved its key, what the group looks like (J1). Nothing is used up. */
    suspend fun preview(link: Link): JoinPreviewResult = try {
        conversation(link) { send, next ->
            send(JoinMessage.Hello(room, ticket.ticketId()))
            val challenge = next() as? JoinMessage.Challenge ?: throw Ended(JoinOutcome.REFUSED)
            if (!JoinCrypto.verifyHost(ticket.hostId, ticket.groupId, ticket.ticketId(), challenge.nonce, challenge.signature)) throw Ended(JoinOutcome.HOST_NOT_VERIFIED)
            send(JoinMessage.PreviewRequest)
            val reply = next() as? JoinMessage.Preview ?: throw Ended(JoinOutcome.REFUSED)
            val plain = JoinCrypto.openPreview(ticket.groupKey, ticket.ticketId(), ticket.hostId, challenge.nonce, reply.sealed) ?: throw Ended(JoinOutcome.REFUSED)
            JoinPreviewResult(null, GroupPreview.decode(plain) ?: throw Ended(JoinOutcome.REFUSED))
        }
    } catch (e: Ended) {
        JoinPreviewResult(e.outcome, null)
    }

    /**
     * Asks on a later connection what became of the request. The host's answer is only a hint (the link may not prove who the host
     * is): the real proof of being admitted is that the host's sync lets this phone in.
     */
    suspend fun status(link: Link): JoinStatusResult = try {
        conversation(link) { send, next ->
            send(JoinMessage.StatusHello(room, identity.publicKey))
            val challenge = next() as? JoinMessage.StatusChallenge ?: throw Ended(JoinOutcome.REFUSED)
            send(JoinMessage.StatusProof(JoinCrypto.signStatus(identity, ticket.groupId, ticket.hostId, challenge.nonce)))
            val reply = next() as? JoinMessage.StatusReply ?: throw Ended(JoinOutcome.REFUSED)
            JoinStatusResult(null, reply.status)
        }
    } catch (e: Ended) {
        JoinStatusResult(e.outcome, null)
    }
}
