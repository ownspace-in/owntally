package owntally.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import owntally.crypto.GroupIdentity
import owntally.crypto.LinkAuth
import owntally.model.PublicKey

/**
 * The certificate a phone shows in TLS (T6.8): self-made, with a fresh P-256 key, and carrying the member's own key and the member's signature
 * over the certificate's key ([LinkAuth.signTlsBinding]). A phone that checks this against the key it meant to reach knows the other end of
 * the TLS session holds that member's key. All cryptography is the platform's (the key pair and the certificate signature: JCA) and Tink's
 * (the member's signature); the only thing written here is the byte layout of a certificate, which the platform's own parser and signature
 * check judge in the tests.
 */
internal object TlsCertificates {
    /**
     * The extension that carries the member key and its signature. The OID is 2.999.1: the arc 2.999 is set aside for private use and examples
     * (ITU-T X.660). A short one on purpose: Android's TLS library can only look up an extension by an OID whose numbers fit in 64 bits, and an
     * OID made from a random UUID (2.25...) is silently "not found" there (found in T6.8 on the emulator).
     */
    const val BINDING_OID = "2.999.1"
    private val BINDING_OID_DER = byteArrayOf(0x88.toByte(), 0x37, 0x01)

    class Issued(val certificate: X509Certificate, val keyPair: KeyPair)

    fun issue(identity: GroupIdentity, random: SecureRandom = SecureRandom()): Issued {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val signature = LinkAuth.signTlsBinding(identity, pair.public.encoded)
        val der = build(pair, identity.publicKey.bytes(), signature, random)
        return Issued(CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate, pair)
    }

    /** The member key and signature carried in the certificate, unchecked; null if there is no well-formed extension. */
    fun bindingOf(cert: X509Certificate): Pair<ByteArray, ByteArray>? {
        val value = cert.getExtensionValue(BINDING_OID) ?: return null
        // getExtensionValue gives an OCTET STRING holding the extension's own OCTET STRING, which holds key (32) then signature (64).
        return try {
            var pos = 0
            fun header(): Int {
                require(value[pos] == 0x04.toByte()); pos++
                var length = value[pos++].toInt() and 0xff
                if (length and 0x80 != 0) { val n = length and 0x7f; length = 0; repeat(n) { length = (length shl 8) or (value[pos++].toInt() and 0xff) } }
                return length
            }
            header(); val inner = header()
            if (inner != 96 || value.size - pos != 96) return null
            value.copyOfRange(pos, pos + 32) to value.copyOfRange(pos + 32, pos + 96)
        } catch (e: RuntimeException) {
            null
        }
    }

    /** The member whose key this certificate carries, if the certificate is self-signed correctly and the member signed its key; null otherwise. */
    fun memberKeyOf(cert: X509Certificate): PublicKey? {
        try {
            cert.verify(cert.publicKey)
        } catch (e: Exception) {
            return null
        }
        val (key, signature) = bindingOf(cert) ?: return null
        val member = runCatching { PublicKey(key) }.getOrNull() ?: return null
        return member.takeIf { LinkAuth.verifyTlsBinding(it, cert.publicKey.encoded, signature) }
    }

    fun build(pair: KeyPair, memberKey: ByteArray, memberSignature: ByteArray, random: SecureRandom = SecureRandom()): ByteArray =
        assemble(pair, random, extension(tlv(0x04, memberKey + memberSignature)))

    fun buildWithoutBinding(pair: KeyPair): ByteArray = assemble(pair, SecureRandom(), null)

    // ---- the bytes: DER, the encoding every certificate parser reads -----------------------------------------------------------------

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray = ByteArrayOutputStream().apply { write(tag); write(length(content.size)); write(content) }.toByteArray()
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun oid(bytes: ByteArray) = tlv(0x06, bytes)

    private val ecdsaWithSha256 = seq(oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)))
    private val name = seq(tlv(0x31, seq(oid(byteArrayOf(0x55, 0x04, 0x03)), tlv(0x0C, "owntally".toByteArray()))))
    private val validity = seq(tlv(0x17, "200101000000Z".toByteArray()), tlv(0x17, "490101000000Z".toByteArray()))

    private fun extension(value: ByteArray): ByteArray = seq(oid(BINDING_OID_DER), tlv(0x04, value))

    private fun assemble(pair: KeyPair, random: SecureRandom, extension: ByteArray?): ByteArray {
        val serial = BigInteger(63, random).add(BigInteger.ONE).toByteArray()
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))), // version 3
            tlv(0x02, serial),
            ecdsaWithSha256,
            name,
            validity,
            name,
            pair.public.encoded, // already a SubjectPublicKeyInfo
            *(if (extension != null) arrayOf(tlv(0xA3, seq(extension))) else emptyArray()),
        )
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private, random); update(tbs) }.sign()
        return seq(tbs, ecdsaWithSha256, tlv(0x03, byteArrayOf(0) + signature))
    }
}
