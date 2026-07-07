package com.docwallet.data.encryption

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import com.docwallet.vault.crypto.KeyStoreCryptographer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeyStoreCryptographer(@Suppress("UNUSED_PARAMETER") context: Context) : KeyStoreCryptographer {

    override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key = obtainKey()
            ?: throw SecurityException("AndroidKeyStore not available — cannot encrypt device key")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return Pair(cipher.iv, cipher.doFinal(plaintext))
        } catch (e: UserNotAuthenticatedException) {
            Log.w(TAG, "Key requires auth — deleting and recreating")
            deleteKey()
            val newKey = obtainKey() ?: throw SecurityException("Cannot recreate key")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, newKey)
            return Pair(cipher.iv, cipher.doFinal(plaintext))
        } catch (e: Exception) {
            throw SecurityException("KeyStore encryption failed", e)
        }
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = obtainKey()
            ?: throw SecurityException("AndroidKeyStore not available — cannot decrypt device key")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        } catch (e: UserNotAuthenticatedException) {
            Log.w(TAG, "Key requires auth — deleting and recreating")
            deleteKey()
            val newKey = obtainKey() ?: throw SecurityException("Cannot recreate key")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, newKey, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SecurityException("KeyStore decryption failed", e)
        }
    }

    override fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(KEYSTORE_DEVICE_KEY_ALIAS)) {
                keyStore.deleteEntry(KEYSTORE_DEVICE_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete KeyStore key", e)
        }
    }

    private fun obtainKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (!keyStore.containsAlias(KEYSTORE_DEVICE_KEY_ALIAS)) {
                val keyGen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
                val spec = KeyGenParameterSpec.Builder(
                        KEYSTORE_DEVICE_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            (keyStore.getEntry(KEYSTORE_DEVICE_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } catch (e: Exception) {
            Log.w(TAG, "Failed to access AndroidKeyStore", e)
            null
        }
    }

    companion object {
        private const val TAG = "AndroidKeyStoreCryptographer"
        private const val KEYSTORE_DEVICE_KEY_ALIAS = "docwallet_device_key"
    }
}
