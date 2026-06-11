package com.meshchat.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeyManager {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        generateKeyIfNotExists()
    }

    private fun generateKeyIfNotExists() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) 
                .build()
            )
            keyGenerator.generateKey()
        }
    }

    private fun getKey(): SecretKey {
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun wrapKey(keyToWrap: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.WRAP_MODE, getKey())
        val wrappedKey = cipher.wrap(javax.crypto.spec.SecretKeySpec(keyToWrap, "AES"))
        val iv = cipher.iv
        // Prepend IV to the wrapped key for unwrapping later
        return iv + wrappedKey
    }

    fun unwrapKey(wrappedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val iv = wrappedData.copyOfRange(0, 12) // GCM IV is 12 bytes
        val wrappedKey = wrappedData.copyOfRange(12, wrappedData.size)
        val spec = GCMParameterSpec(128, iv)
        
        cipher.init(Cipher.UNWRAP_MODE, getKey(), spec)
        val unwrappedKey = cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY)
        return unwrappedKey.encoded
    }

    companion object {
        private const val KEY_ALIAS = "meshchat_mesh_key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
