package com.docwallet.cli.crypto

import com.docwallet.vault.crypto.Argon2HasherImpl
import com.docwallet.vault.crypto.AesKeyGenerator
import com.docwallet.vault.crypto.FileEncryptor
import com.docwallet.vault.crypto.KdfParams
import com.docwallet.vault.crypto.KeyDerivation
import com.docwallet.vault.crypto.KeyWrap
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int

fun cryptoCommand() = CryptoCommands().subcommands(
    KdfCommand(), EncryptCommand(), DecryptCommand(), KeyWrapCommand(), KeyUnwrapCommand()
)

class CryptoCommands : CliktCommand(name = "crypto", help = "Cryptographic operations") {
    override fun run() = Unit
}

class KdfCommand : CliktCommand(name = "kdf", help = "Derive key from password using Argon2id") {
    private val password by option("--password", "-p").required()
    private val saltHex by option("--salt", "-s").default("")
    private val memory by option("--memory").int().default(65536)
    private val iterations by option("--iterations").int().default(3)
    private val parallelism by option("--parallelism").int().default(4)

    override fun run() {
        val hasher = Argon2HasherImpl()
        val derivation = KeyDerivation(hasher)
        val salt = if (saltHex.isNotEmpty()) hexToBytes(saltHex)
        else derivation.generateSalt()
        val params = KdfParams(memoryCost = memory, iterations = iterations, parallelism = parallelism)
        val key = derivation.deriveAndZero(password, salt, params)
        echo("Salt (${salt.size} bytes): ${bytesToHex(salt)}")
        echo("KDF params: memory=$memory, iterations=$iterations, parallelism=$parallelism")
        echo("Derived key (${key.size} bytes): ${bytesToHex(key)}")
        key.fill(0)
    }
}

class EncryptCommand : CliktCommand(name = "encrypt", help = "Encrypt data with AES-256-GCM") {
    private val keyHex by option("--key", "-k").required()
    private val data by argument()

    override fun run() {
        val key = hexToBytes(keyHex)
        val plaintext = data.toByteArray()
        val (iv, ciphertext) = FileEncryptor().encryptBytes(plaintext, key)
        echo("IV (${iv.size} bytes): ${bytesToHex(iv)}")
        echo("Ciphertext (${ciphertext.size} bytes): ${bytesToHex(ciphertext)}")
        echo("Combined: ${bytesToHex(iv + ciphertext)}")
        key.fill(0)
    }
}

class DecryptCommand : CliktCommand(name = "decrypt", help = "Decrypt data with AES-256-GCM") {
    private val keyHex by option("--key", "-k").required()
    private val ivHex by option("--iv").required()
    private val data by argument()

    override fun run() {
        val key = hexToBytes(keyHex)
        val iv = hexToBytes(ivHex)
        val ciphertext = hexToBytes(data)
        val plaintext = FileEncryptor().decryptBytes(ciphertext, key, iv)
        echo("Plaintext (${plaintext.size} bytes): ${String(plaintext)}")
        key.fill(0)
    }
}

class KeyWrapCommand : CliktCommand(name = "keywrap", help = "Wrap a key using AES-KW (RFC 3394)") {
    private val masterKeyHex by option("--master-key", "-m").required()
    private val wrappingKeyHex by option("--wrapping-key", "-w").required()

    override fun run() {
        val masterKey = hexToBytes(masterKeyHex)
        val wrappingKey = hexToBytes(wrappingKeyHex)
        val wrapped = KeyWrap.wrap(masterKey, wrappingKey)
        echo("Wrapped key (${wrapped.size} bytes): ${bytesToHex(wrapped)}")
        masterKey.fill(0)
        wrappingKey.fill(0)
    }
}

class KeyUnwrapCommand : CliktCommand(name = "keyunwrap", help = "Unwrap a key using AES-KW (RFC 3394)") {
    private val wrappedHex by option("--wrapped", "-w").required()
    private val wrappingKeyHex by option("--wrapping-key", "-k").required()

    override fun run() {
        val wrapped = hexToBytes(wrappedHex)
        val wrappingKey = hexToBytes(wrappingKeyHex)
        val masterKey = KeyWrap.unwrap(wrapped, wrappingKey)
        echo("Unwrapped key (${masterKey.size} bytes): ${bytesToHex(masterKey)}")
        masterKey.fill(0)
        wrappingKey.fill(0)
    }
}

fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
