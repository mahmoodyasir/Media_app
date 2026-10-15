package com.mediaapp.data.vault

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

enum class LockKind { Pin, Pattern }

@Singleton
class AppLock @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: AndroidVaultCipher,
) {
    @Volatile private var cached: Credential? = null

    fun hasCredential(): Boolean = credentialFile().exists() && credentialFile().length() > 0L

    fun kind(): LockKind? = read()?.kind

    fun biometricEnabled(): Boolean = read()?.biometric == true

    fun setPin(pin: String) = write(LockKind.Pin, pin, read()?.biometric == true)

    fun setPattern(pattern: List<Int>) = write(LockKind.Pattern, pattern.joinToString(","), read()?.biometric == true)

    fun setBiometric(enabled: Boolean) {
        val current = read() ?: return
        cached = current.copy(biometric = enabled)
        persist(cached!!)
    }

    fun verifyPin(pin: String): Boolean = verify(LockKind.Pin, pin)

    fun verifyPattern(pattern: List<Int>): Boolean = verify(LockKind.Pattern, pattern.joinToString(","))

    private fun verify(kind: LockKind, secret: String): Boolean {
        val credential = read() ?: return false
        if (credential.kind != kind) return false
        return PasscodeHasher.verify(secret, credential.salt, credential.hash, credential.iterations)
    }

    private fun write(kind: LockKind, secret: String, biometric: Boolean) {
        val salt = PasscodeHasher.newSalt()
        cached = Credential(
            kind = kind,
            salt = salt,
            hash = PasscodeHasher.hash(secret, salt),
            iterations = PasscodeHasher.ITERATIONS,
            biometric = biometric,
        )
        persist(cached!!)
    }

    private fun read(): Credential? {
        cached?.let { return it }
        val file = credentialFile()
        if (!file.exists() || file.length() == 0L) return null
        val json = cipher.crypto().decryptBytes(file.readBytes(), AAD).toString(Charsets.UTF_8)
        val parsed = JSONObject(json)
        return Credential(
            kind = LockKind.valueOf(parsed.getString("kind")),
            salt = Base64.decode(parsed.getString("salt"), Base64.NO_WRAP),
            hash = Base64.decode(parsed.getString("hash"), Base64.NO_WRAP),
            iterations = parsed.getInt("iterations"),
            biometric = parsed.optBoolean("biometric"),
        ).also { cached = it }
    }

    private fun persist(credential: Credential) {
        val json = JSONObject().apply {
            put("kind", credential.kind.name)
            put("salt", Base64.encodeToString(credential.salt, Base64.NO_WRAP))
            put("hash", Base64.encodeToString(credential.hash, Base64.NO_WRAP))
            put("iterations", credential.iterations)
            put("biometric", credential.biometric)
        }.toString()
        val file = credentialFile()
        file.parentFile?.mkdirs()
        file.writeBytes(cipher.crypto().encryptBytes(json.toByteArray(Charsets.UTF_8), AAD))
    }

    private fun credentialFile(): File = File(context.filesDir, "vault/credential.bin")

    private data class Credential(
        val kind: LockKind,
        val salt: ByteArray,
        val hash: ByteArray,
        val iterations: Int,
        val biometric: Boolean,
    )

    private companion object {
        const val AAD = "mediaapp.vault.credential.v1"
    }
}
