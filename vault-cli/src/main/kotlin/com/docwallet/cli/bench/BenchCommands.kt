package com.docwallet.cli.bench

import com.docwallet.vault.crypto.Argon2HasherImpl
import com.docwallet.vault.crypto.FileEncryptor
import com.docwallet.vault.crypto.KdfParams
import com.docwallet.vault.crypto.KeyDerivation
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

fun benchCommand() = BenchCommands().subcommands(
    BenchKdf(), BenchEncrypt()
)

class BenchCommands : CliktCommand(name = "bench", help = "Performance benchmarks") {
    override fun run() = Unit
}

class BenchKdf : CliktCommand(name = "kdf", help = "Benchmark Argon2id key derivation") {
    private val memory by option("--memory").int().default(65536)
    private val iterations by option("--iterations").int().default(3)
    private val parallelism by option("--parallelism").int().default(4)
    private val count by option("--count").int().default(5)

    override fun run() {
        val params = KdfParams(memoryCost = memory, iterations = iterations, parallelism = parallelism)
        val derivation = KeyDerivation(Argon2HasherImpl())
        val salt = derivation.generateSalt()
        echo("Benchmarking KDF (memory=$memory, iterations=$iterations, parallelism=$parallelism, count=$count)...")

        val times = mutableListOf<Long>()
        for (i in 1..count) {
            val start = System.nanoTime()
            val key = derivation.deriveAndZero("test-password-$i", salt, params)
            key.fill(0)
            val elapsed = System.nanoTime() - start
            times.add(elapsed)
            echo("  Run $i: ${elapsed / 1_000_000} ms")
        }

        val avg = times.average() / 1_000_000
        echo("Average: $avg ms")
    }
}

class BenchEncrypt : CliktCommand(name = "encrypt", help = "Benchmark AES-256-GCM encryption") {
    private val size by option("--size", "-s").int().default(1048576)
    private val count by option("--count").int().default(5)

    override fun run() {
        val encryptor = FileEncryptor()
        val key = ByteArray(32) { 0x42 }
        val plaintext = ByteArray(size) { it.toByte() }

        echo("Benchmarking AES-256-GCM (size=$size bytes, count=$count)...")

        val encTimes = mutableListOf<Long>()
        val decTimes = mutableListOf<Long>()

        for (i in 1..count) {
            val encStart = System.nanoTime()
            val (iv, ciphertext) = encryptor.encryptBytes(plaintext, key)
            encTimes.add(System.nanoTime() - encStart)

            val decStart = System.nanoTime()
            val decrypted = encryptor.decryptBytes(ciphertext, key, iv)
            decTimes.add(System.nanoTime() - decStart)

            val ok = plaintext.contentEquals(decrypted)
            echo("  Run $i: encrypt=${encTimes.last() / 1_000_000} ms, decrypt=${decTimes.last() / 1_000_000} ms, verify=$ok")
        }

        val encAvg = encTimes.average() / 1_000_000
        val decAvg = decTimes.average() / 1_000_000
        echo("Average: encrypt=$encAvg ms, decrypt=$decAvg ms")
        key.fill(0)
    }
}
