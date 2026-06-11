package com.meshchat.core

expect class PlatformCrypto() {
    fun generateKey(): ByteArray
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray
    fun decrypt(blob: ByteArray, key: ByteArray): ByteArray
    fun hmacSign(data: ByteArray, key: ByteArray): ByteArray
    fun hmacVerify(data: ByteArray, tag: ByteArray, key: ByteArray): Boolean
}
