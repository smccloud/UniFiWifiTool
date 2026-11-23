package com.smccloud.unifiwifitool.data.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the per-device bearer token and the relay session key encrypted with a
 * non-exportable AES-256 key in the Android Keystore.
 *
 * Plaintext secrets never touch SharedPreferences, logcat, or disk. Only the
 * ciphertext and IV are persisted.
 */
class SecureTokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("unifi_secure", Context.MODE_PRIVATE)

    fun saveDeviceToken(token: String) = putSecret(KEY_DEVICE_TOKEN, token.toByteArray(Charsets.UTF_8))

    fun readDeviceToken(): String? =
        getSecret(KEY_DEVICE_TOKEN)?.toString(Charsets.UTF_8)

    fun saveRelaySessionKey(key: ByteArray) = putSecret(KEY_RELAY_SESSION_KEY, key)

    fun readRelaySessionKey(): ByteArray? = getSecret(KEY_RELAY_SESSION_KEY)

    fun clearAll() {
        prefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_RELAY_SESSION_KEY)
            .apply()
    }

    private fun putSecret(alias: String, plaintext: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext)
        prefs.edit()
            .putString("$alias.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$alias.ct", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    private fun getSecret(alias: String): ByteArray? {
        val ivString = prefs.getString("$alias.iv", null) ?: return null
        val ciphertextString = prefs.getString("$alias.ct", null) ?: return null
        return try {
            val iv = Base64.decode(ivString, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextString, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, iv)
            )
            cipher.doFinal(ciphertext)
        } catch (error: Exception) {
            // A keystore reset or corrupted blob behaves like an unpaired device.
            clearAll()
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "unifi_wifi_tool_device_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val KEY_DEVICE_TOKEN = "device_token_v1"
        const val KEY_RELAY_SESSION_KEY = "relay_session_key_v1"
    }
}
