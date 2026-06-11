package com.meshchat.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual class PlatformCrypto actual constructor() {

    private val secureRandom = SecureRandom()

    actual fun generateKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, secureRandom)
        return keyGen.generateKey().encoded
    }

    actual fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val cipherText = cipher.doFinal(data)
        return iv + cipherText
    }

    actual fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = blob.copyOfRange(0, 12)
        val cipherText = blob.copyOfRange(12, blob.size)
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(cipherText)
    }

    actual fun hmacSign(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    actual fun hmacVerify(data: ByteArray, tag: ByteArray, key: ByteArray): Boolean {
        val expected = hmacSign(data, key)
        return MessageDigest.isEqual(expected, tag)
    }
}
