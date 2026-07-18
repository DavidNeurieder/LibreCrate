package com.librecrate.app.vault.crypto

import java.io.File
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FileEncryptor {

    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        const val IV_LENGTH = 12
    }

    fun encrypt(input: File, output: File, key: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv

        output.outputStream().use { outputStream ->
            outputStream.write(iv)
            input.inputStream().use { inputStream ->
                CipherOutputStream(outputStream, cipher).use { cos ->
                    inputStream.copyTo(cos)
                }
            }
        }

        return iv
    }

    fun encryptBytes(data: ByteArray, key: ByteArray): Pair<ByteArray, ByteArray> {
        val secretKey = SecretKeySpec(key, ALGORITHM)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        return Pair(iv, encrypted)
    }

    fun decrypt(input: File, output: File, key: ByteArray, iv: ByteArray) {
        val secretKey = SecretKeySpec(key, ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        output.outputStream().use { outputStream ->
            input.inputStream().use { inputStream ->
                inputStream.skip(iv.size.toLong())
                CipherInputStream(inputStream, cipher).use { cis ->
                    cis.copyTo(outputStream)
                }
            }
        }
    }

    fun decryptBytes(encrypted: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encrypted)
    }

    fun generateKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(256)
        return keyGen.generateKey().encoded
    }
}
