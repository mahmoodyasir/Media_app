package com.mediaapp.data.vault

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasscodeHasher {
    const val ITERATIONS = 120_000

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(secret: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun verify(secret: String, salt: ByteArray, expected: ByteArray, iterations: Int = ITERATIONS): Boolean {
        val actual = hash(secret, salt, iterations)
        return MessageDigest.isEqual(actual, expected)
    }
}
