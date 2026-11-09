package owntally.sync

import java.io.IOException
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import owntally.model.PublicKey

/**
 * TLS 1.3 between two members over any socket (T6.8; used for the local link and, since T7.3, through the relay): each side shows a
 * certificate that carries its member key ([TlsCertificates]) and only a certificate whose member signed its key is accepted.
 */
internal object TlsLinks {
    /**
     * Runs the TLS handshake as the caller on [socket] and checks the other end holds [expected]. Throws [LinkAuthFailed] if it shows another
     * key or none, [PeerUnreachable] if the handshake fails any other way.
     */
    fun client(socket: Socket, host: String, port: Int, own: TlsCertificates.Issued, expected: PublicKey, handshakeMs: Int, random: SecureRandom): SSLSocket {
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(SingleKeyManager(own)), arrayOf<TrustManager>(MemberTrust(expected)), random) }
        val ssl = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            ssl.useClientMode = true
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            ssl.soTimeout = handshakeMs
            ssl.startHandshake()
            val member = ssl.session.peerCertificates.firstOrNull()?.let { (it as? X509Certificate)?.let(TlsCertificates::memberKeyOf) }
            if (member != expected) throw LinkAuthFailed("the other phone does not hold the key it should")
            return ssl
        } catch (e: SSLException) {
            runCatching { ssl.close() }
            throw if (e.cause is LinkAuthFailed || e.message?.contains("owntally") == true) LinkAuthFailed("the other phone does not hold the key it should") else PeerUnreachable(expected, e)
        } catch (e: LinkAuthFailed) {
            runCatching { ssl.close() }
            throw e
        }
    }

    /** Runs the TLS handshake as the answerer; the caller's member key comes from its certificate. Null (and the socket closed) if anything is wrong. */
    fun server(socket: Socket, own: TlsCertificates.Issued, handshakeMs: Int, random: SecureRandom): Pair<SSLSocket, PublicKey>? {
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(SingleKeyManager(own)), arrayOf<TrustManager>(MemberTrust(null)), random) }
        val ssl = context.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
        return try {
            ssl.useClientMode = false
            ssl.needClientAuth = true
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            ssl.soTimeout = handshakeMs
            ssl.startHandshake()
            val member = (ssl.session.peerCertificates.firstOrNull() as? X509Certificate)?.let(TlsCertificates::memberKeyOf)
            if (member == null) { ssl.close(); null } else ssl to member
        } catch (e: IOException) {
            runCatching { ssl.close() }
            null
        }
    }

    /** The one certificate and key a connection shows: this phone's for the identity the connection is for. */
    private class SingleKeyManager(private val own: TlsCertificates.Issued) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = "own"
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = "own"
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?): String = "own"
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: javax.net.ssl.SSLEngine?): String = "own"
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(own.certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = own.keyPair.private
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("own")
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf("own")
    }

    /**
     * Accepts a certificate only if its member signed its key (and, for a caller, it is the key it meant to reach). Dates are not checked:
     * phones' clocks differ, and the certificate is fresh for each node anyway.
     */
    private class MemberTrust(private val expected: PublicKey?) : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            val cert = chain?.singleOrNull() ?: throw java.security.cert.CertificateException("owntally: one certificate expected")
            val member = TlsCertificates.memberKeyOf(cert) ?: throw java.security.cert.CertificateException("owntally: no member key bound to this certificate")
            if (expected != null && member != expected) throw java.security.cert.CertificateException("owntally: not the member that was called")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
