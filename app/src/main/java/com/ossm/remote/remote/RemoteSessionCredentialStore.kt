package com.ossm.remote.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteSessionCredentialStore @Inject constructor(@ApplicationContext context: Context) : RemoteSessionCredentialPersistence {
    private val preferences = context.getSharedPreferences("remote_session", Context.MODE_PRIVATE)

    override fun save(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        preferences.edit().putString("credential", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
    }

    override fun read(): String? = runCatching {
        val bytes = Base64.decode(preferences.getString("credential", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES))) }
        cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    override fun hasCredential(): Boolean = preferences.contains("credential")

    override fun clear() {
        preferences.edit().remove("credential").apply()
    }

    private fun key(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object { const val ANDROID_KEY_STORE = "AndroidKeyStore"; const val KEY_ALIAS = "ossm_remote_session_credential"; const val TRANSFORMATION = "AES/GCM/NoPadding"; const val IV_BYTES = 12; const val TAG_BITS = 128 }
}
