package owntally.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException
import java.util.zip.CRC32
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * T2.2: shows that every call docs/CRYPTO.md names exists in the pinned Tink release (or the JDK) and works.
 *
 * The expected values below were NOT produced by Tink. They come from an independent implementation: Python
 * `cryptography` 49.0.0 and `hashlib` for HKDF, Ed25519, X25519 and PBKDF2, and a hand-written RFC 9180 (HPKE base mode,
 * DHKEM X25519 + HKDF-SHA256 + ChaCha20-Poly1305) and XChaCha20-Poly1305 (HChaCha20 + ChaCha20-Poly1305) sealing with
 * fixed inputs. Tink must reproduce or open them exactly.
 */
class TinkCapabilitiesTest {
    private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val SEED = "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4".hex()
    private val SIGN_SEED = "95c5f470ecc4db4b86f2fd11265f3df233866f05852a0e3f31cfb5e6d04fc3ac".hex()
    private val SIGN_PUB = "7500c8382ec89300d935184dbbb9e51eeab57baf6a8088196547509376f013e8".hex()
    private val SIG = "ad86a931f057f44568518eb1239ec47359cda40f39ef372a47ce5be8b4f343bdcf15291103d0eac9505857a27853ee4ec40e561e99a7fb91ff7f008e23e4d107".hex()
    private val ENC_PRIV = "93d7cf055308e1bea280a9cceaa57763ea225f0723db210b91c353786758d9f6".hex()
    private val ENC_PUB = "e9fca55de36d00daebc1252d3904dd4399fe5115896ee99511362d4e2ada9d1b".hex()
    private val X_KEY = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hex()
    private val X_NONCE = "404142434445464748494a4b4c4d4e4f5051525354555657".hex()
    private val X_AAD = "6161642d6279746573".hex()
    private val X_PT = "54686520717569636b2062726f776e20666f78".hex()
    private val X_CT = "80516050a1951075e4d4e5ccc0eb0bb2f4d5d515f1b54d25a76e9db031729ba38d476f".hex()
    private val H_SK = "0707070707070707070707070707070707070707070707070707070707070707".hex()
    private val H_PK = "13be4feaeaf204c7fd3358fc9c00721881d174278128227ec674f37f7fe97b6d".hex()
    private val H_INFO = "6f776e74616c6c792f76312f6b65792d64656c6976657279".hex()
    private val H_PT = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hex()
    private val H_OUT = "57db4b359f23ae5e146e4e2512056704722506348c150c14753d0c933d04d421abc33a787aaa18945d7fc02dc97e855935ddb3873c4fc7462aabf8ea26c5042b0ee449e5461abf384f48db14134a0526".hex()
    private val PB = "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce".hex()

    private val msg = "owntally/v1/event".toByteArray() + "envelope bytes".toByteArray()

    @Test
    fun hkdfSha256DerivesTheGroupKeysFromTheSeed() {
        val sign = Hkdf.computeHkdf("HmacSha256", SEED, "owntally/v1".toByteArray(), "group/0/sign".toByteArray(), 32)
        val enc = Hkdf.computeHkdf("HmacSha256", SEED, "owntally/v1".toByteArray(), "group/0/encrypt".toByteArray(), 32)
        assertEquals(SIGN_SEED.hex(), sign.hex())
        assertEquals(ENC_PRIV.hex(), enc.hex())
        val other = Hkdf.computeHkdf("HmacSha256", SEED, "owntally/v1".toByteArray(), "group/1/sign".toByteArray(), 32)
        assertNotEquals(sign.hex(), other.hex())
    }

    @Test
    fun ed25519KeyPairFromASeedSignsAndVerifies() {
        val pair = Ed25519Sign.KeyPair.newKeyPairFromSeed(SIGN_SEED)
        assertEquals(SIGN_PUB.hex(), pair.publicKey.hex())
        val signature = Ed25519Sign(pair.privateKey).sign(msg)
        assertEquals(SIG.hex(), signature.hex()) // Ed25519 is deterministic
        Ed25519Verify(pair.publicKey).verify(signature, msg)
        val bent = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> { Ed25519Verify(pair.publicKey).verify(bent, msg) }
        assertFailsWith<GeneralSecurityException> { Ed25519Verify(pair.publicKey).verify(signature, msg + 1) }
    }

    @Test
    fun x25519KeyPairFromBytesAndSharedSecret() {
        assertEquals(ENC_PUB.hex(), X25519.publicFromPrivate(ENC_PRIV).hex())
        val a = X25519.generatePrivateKey()
        val b = X25519.generatePrivateKey()
        assertEquals(
            X25519.computeSharedSecret(a, X25519.publicFromPrivate(b)).hex(),
            X25519.computeSharedSecret(b, X25519.publicFromPrivate(a)).hex(),
        )
    }

    @Test
    fun xChaCha20Poly1305OpensAnIndependentlySealedMessage() {
        val aead = XChaCha20Poly1305(X_KEY)
        // Tink's format is nonce (24 bytes) followed by ciphertext and tag; the independent sealing has no nonce prefix.
        assertContentEquals(X_PT, aead.decrypt(X_NONCE + X_CT, X_AAD))
        assertFailsWith<GeneralSecurityException> { aead.decrypt(X_NONCE + X_CT, "other".toByteArray()) }
        val bent = (X_NONCE + X_CT).also { it[30] = (it[30].toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> { aead.decrypt(bent, X_AAD) }
        assertFailsWith<GeneralSecurityException> { XChaCha20Poly1305(ByteArray(32) { 1 }).decrypt(X_NONCE + X_CT, X_AAD) }
    }

    @Test
    fun xChaCha20Poly1305SealsWithAFreshNonceThatCanBeSplitOut() {
        val aead = XChaCha20Poly1305(X_KEY)
        val one = aead.encrypt(X_PT, X_AAD)
        val two = aead.encrypt(X_PT, X_AAD)
        assertEquals(24 + X_PT.size + 16, one.size)
        assertNotEquals(one.hex(), two.hex())
        assertNotEquals(one.copyOfRange(0, 24).hex(), two.copyOfRange(0, 24).hex())
        // The envelope keeps the nonce in its own field: split it out and put it back.
        val nonce = one.copyOfRange(0, 24)
        val body = one.copyOfRange(24, one.size)
        assertContentEquals(X_PT, aead.decrypt(nonce + body, X_AAD))
    }

    private fun hpkeParameters() = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.CHACHA20_POLY1305)
        .setVariant(HpkeParameters.Variant.NO_PREFIX)
        .build()

    private fun hpkeKey(privateBytes: ByteArray, publicBytes: ByteArray): HpkePrivateKey {
        val pub = HpkePublicKey.create(hpkeParameters(), Bytes.copyFrom(publicBytes), null)
        return HpkePrivateKey.create(pub, SecretBytes.copyFrom(privateBytes, InsecureSecretKeyAccess.get()))
    }

    private fun decryptor(key: HpkePrivateKey): HybridDecrypt =
        KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary()).build()
            .getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)

    private fun encryptor(key: HpkePrivateKey): HybridEncrypt =
        KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary()).build()
            .publicKeysetHandle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)

    @Test
    fun hpkeOpensAnIndependentRfc9180Sealing() {
        HybridConfig.register()
        val decrypt = decryptor(hpkeKey(H_SK, H_PK))
        assertContentEquals(H_PT, decrypt.decrypt(H_OUT, H_INFO))
        assertFailsWith<GeneralSecurityException> { decrypt.decrypt(H_OUT, "other info".toByteArray()) }
        val bent = H_OUT.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> { decrypt.decrypt(bent, H_INFO) }
    }

    @Test
    fun hpkeRoundTripsAndOnlyTheRightRecipientCanOpen() {
        HybridConfig.register()
        val me = hpkeKey(H_SK, H_PK)
        val sealed = encryptor(me).encrypt(H_PT, H_INFO)
        val sealedAgain = encryptor(me).encrypt(H_PT, H_INFO)
        assertNotEquals(sealed.hex(), sealedAgain.hex())
        assertEquals(32 + 32 + 16, sealed.size) // encapsulated key, the 32-byte group key, tag
        assertContentEquals(H_PT, decryptor(me).decrypt(sealed, H_INFO))
        val otherPrivate = X25519.generatePrivateKey()
        val other = hpkeKey(otherPrivate, X25519.publicFromPrivate(otherPrivate))
        assertFailsWith<GeneralSecurityException> { decryptor(other).decrypt(sealed, H_INFO) }
    }

    @Test
    fun theJdkGivesPbkdf2HmacSha512() {
        val spec = PBEKeySpec("password".toCharArray(), "salt".toByteArray(), 1, 512)
        val out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        assertEquals(PB.hex(), out.hex())
    }

    @Test
    fun theJdkGivesCrc32() {
        val crc = CRC32().apply { update("123456789".toByteArray()) }.value
        assertEquals(0xCBF43926L, crc)
    }
}

