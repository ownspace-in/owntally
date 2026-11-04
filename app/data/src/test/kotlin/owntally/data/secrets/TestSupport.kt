package owntally.data.secrets

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig

/** A real Tink AES-GCM key held in memory: stands in for the Android Keystore key on the JVM. */
fun newAead(): Aead {
    AeadConfig.register()
    return KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
