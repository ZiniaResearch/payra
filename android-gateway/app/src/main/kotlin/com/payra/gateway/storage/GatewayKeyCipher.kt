package com.payra.gateway.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object GatewayKeyCipher {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "payra_gateway_key_cipher"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    suspend fun encrypt(key: String): String = useCipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        Base64.encodeToString(cipher.iv + cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    suspend fun decrypt(encrypted: String): String = useCipher {
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        if (bytes.size <= IV_LENGTH) throw IOException("Invalid encrypted gateway key")
        val key = readStore().getKey(ALIAS, null) as? SecretKey
            ?: throw IOException("Gateway encryption key is unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH).toString(Charsets.UTF_8)
    }

    @Synchronized
    private fun encryptionKey(): SecretKey {
        val store = readStore()
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun readStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private suspend fun <T> useCipher(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: GeneralSecurityException) {
            throw IOException("Could not access the gateway key", error)
        } catch (error: ProviderException) {
            throw IOException("Could not access the gateway key", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid encrypted gateway key", error)
        }
    }
}
