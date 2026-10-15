package com.mediaapp.data.vault

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The streaming keyset lives in app-private prefs, wrapped by a non-exportable
 * Android Keystore master key. Clearing app data or uninstalling the app drops
 * the keyset, which makes locked files unrecoverable.
 */
@Singleton
class AndroidVaultCipher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var cached: VaultCrypto? = null

    fun crypto(): VaultCrypto {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: VaultCrypto(load()).also { cached = it }
        }
    }

    private fun load(): StreamingAead {
        StreamingAeadConfig.register()
        val handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return handle.getPrimitive(StreamingAead::class.java)
    }

    private companion object {
        const val PREF_FILE = "mediaapp_vault_keyset"
        const val KEYSET_NAME = "vault_streaming_v1"
        const val MASTER_KEY_URI = "android-keystore://mediaapp_vault_master_v1"
    }
}
