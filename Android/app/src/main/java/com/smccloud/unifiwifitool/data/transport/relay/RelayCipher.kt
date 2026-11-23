package com.smccloud.unifiwifitool.data.transport.relay

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for relay frames.
 *
 * The relay only sees frame sizes and timing: the method, path, bearer token and
 * request/response bodies all live inside the sealed payload. The session key is
 * established during pairing and stored in the Android Keystore.
 */
interface RelayCipher {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(ciphertextAndIv: ByteArray): ByteArray
}

class AesGcmRelayCipher(
    key: ByteArray,
    private val random: SecureRandom = SecureRandom()
) : RelayCipher {

    private val keySpec = SecretKeySpec(key, "AES")

    init {
        require(key.size == 32) { "Relay session key must be 32 bytes (AES-256)" }
    }

    override fun seal(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun open(ciphertextAndIv: ByteArray): ByteArray {
        require(ciphertextAndIv.size > IV_LENGTH_BYTES) { "Relay frame is too short" }
        val iv = ciphertextAndIv.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = ciphertextAndIv.copyOfRange(IV_LENGTH_BYTES, ciphertextAndIv.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
