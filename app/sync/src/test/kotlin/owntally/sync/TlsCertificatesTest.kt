package owntally.sync

import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import owntally.crypto.KeyDerivation
import owntally.crypto.LinkAuth
import owntally.crypto.MasterSeed

/** T6.8: the self-made certificate that carries a member's key into TLS. The platform's own parser is the judge of the encoding. */
class TlsCertificatesTest {
    private val seed = MasterSeed(ByteArray(64) { 4 })
    private val a = KeyDerivation.groupIdentity(seed, 0)
    private val b = KeyDerivation.groupIdentity(seed, 1)

    private fun parse(der: ByteArray) = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    @Test fun theCertificateIsAProperX509TheJdkParsesAndItsOwnSignatureChecks() {
        val issued = TlsCertificates.issue(a)
        val cert = parse(issued.certificate.encoded)
        assertEquals(3, cert.version)
        assertEquals("CN=owntally", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        cert.verify(cert.publicKey) // throws if the encoding or the signature is wrong
        assertEquals("EC", cert.publicKey.algorithm)
        assertTrue(cert.getExtensionValue(TlsCertificates.BINDING_OID) != null)
        cert.checkValidity() // in date today, with room either way
    }

    @Test fun theMemberKeyComesOutOnlyOfACertificateTheMemberBound() {
        val issued = TlsCertificates.issue(a)
        assertEquals(a.publicKey, TlsCertificates.memberKeyOf(parse(issued.certificate.encoded)))
        assertEquals(b.publicKey, TlsCertificates.memberKeyOf(parse(TlsCertificates.issue(b).certificate.encoded)))
    }

    @Test fun twoCertificatesOfOnePhoneHaveDifferentKeysAndSerials() {
        val x = TlsCertificates.issue(a); val y = TlsCertificates.issue(a)
        assertNotEquals(x.certificate.publicKey, y.certificate.publicKey)
        assertNotEquals(x.certificate.serialNumber, y.certificate.serialNumber)
    }

    @Test fun aBindingMadeForAnotherCertificateKeyIsNotAccepted() {
        // Mallory has her own TLS key and wants Asha's key in her certificate: she can only copy Asha's signature, which covers Asha's certificate key.
        val ashas = TlsCertificates.issue(a)
        val binding = TlsCertificates.bindingOf(ashas.certificate)!!
        val malloryKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val forged = TlsCertificates.build(malloryKey, binding.first, binding.second)
        parse(forged).verify(parse(forged).publicKey)
        assertNull(TlsCertificates.memberKeyOf(parse(forged)), "the signature does not cover this certificate key")
    }

    @Test fun aBindingByAnotherMemberUnderThisMembersNameIsNotAccepted() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val spki = key.public.encoded
        val bySomeoneElse = LinkAuth.signTlsBinding(b, spki)
        val cert = parse(TlsCertificates.build(key, a.publicKey.bytes(), bySomeoneElse)) // claims Asha, signed by Ben
        assertNull(TlsCertificates.memberKeyOf(cert))
    }

    @Test fun aCertificateWithNoBindingOrAMalformedOneIsNotAccepted() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertNull(TlsCertificates.memberKeyOf(parse(TlsCertificates.build(key, ByteArray(32), ByteArray(10)))), "wrong sizes")
        assertNull(TlsCertificates.memberKeyOf(parse(TlsCertificates.build(key, ByteArray(32), ByteArray(64)))), "zeros")
        val plain = parse(TlsCertificates.buildWithoutBinding(key))
        assertNull(TlsCertificates.memberKeyOf(plain), "an ordinary self-signed certificate")
        assertNotNull(plain)
    }

    @Test fun aCertificateThatIsNotSelfSignedIsNotAccepted() {
        val issued = TlsCertificates.issue(a)
        val other = TlsCertificates.issue(b)
        val der = issued.certificate.encoded.copyOf()
        // Put the other certificate's signature bytes at the end (same length, DER-valid): the self-signature no longer matches.
        val otherSig = other.certificate.encoded.takeLast(70).toByteArray()
        System.arraycopy(otherSig, 0, der, der.size - 70, 70)
        val tampered = runCatching { parse(der) }.getOrNull()
        if (tampered != null) assertNull(TlsCertificates.memberKeyOf(tampered))
    }
}
