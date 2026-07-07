package com.docwallet.cli.vault

import com.docwallet.cli.Argon2HasherJvm
import com.docwallet.vault.backup.VaultExporter
import com.docwallet.vault.backup.VaultImporter
import com.docwallet.vault.crypto.KeyDerivation
import com.docwallet.vault.format.VaultPackage
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.File

fun vaultCommand() = VaultCommands().subcommands(
    VaultCreate(), VaultInspect(), VaultExtract(), VaultRoundtrip()
)

class VaultCommands : CliktCommand(name = "vault", help = "Vault file operations") {
    override fun run() = Unit
}

class VaultCreate : CliktCommand(name = "create", help = "Create a .vault file from a directory") {
    private val password by option("--password", "-p").required()
    private val dir by option("--dir", "-d").required()
    private val output by option("--output", "-o").required()
    private val db by option("--db").default("")

    override fun run() {
        val sourceDir = File(dir)
        require(sourceDir.isDirectory) { "Not a directory: $dir" }

        val files = mutableMapOf<String, ByteArray>()
        sourceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                files[file.relativeTo(sourceDir).path] = file.readBytes()
            }
        }

        val dbData = if (db.isNotEmpty()) File(db).takeIf { it.exists() }?.readBytes() else null

        val keyDerivation = KeyDerivation(Argon2HasherJvm())
        val vaultBytes = VaultExporter(keyDerivation).export(files, dbData, password)
        File(output).writeBytes(vaultBytes)

        echo("Created vault: $output (${vaultBytes.size} bytes, ${files.size} files)")
    }
}

class VaultInspect : CliktCommand(name = "inspect", help = "Inspect a .vault file") {
    private val input by option("--input", "-i").required()

    override fun run() {
        val data = File(input).readBytes()
        val vaultData = VaultPackage.read(data)
        val manifest = vaultData.manifest

        echo("Vault file: $input")
        echo("  Version: ${manifest.version}")
        echo("  KDF: ${manifest.kdf}")
        echo("  Salt: ${manifest.salt}")
        echo("  Argon2: memory=${manifest.argon2Memory}, iterations=${manifest.argon2Iterations}, parallelism=${manifest.argon2Parallelism}")
        echo("  Document count: ${manifest.documentCount}")
        echo("  Encrypted blob: ${vaultData.encryptedBlob.size} bytes")
    }
}

class VaultExtract : CliktCommand(name = "extract", help = "Extract a .vault file to a directory") {
    private val password by option("--password", "-p").required()
    private val input by option("--input", "-i").required()
    private val dir by option("--dir", "-d").required()

    override fun run() {
        val keyDerivation = KeyDerivation(Argon2HasherJvm())
        val vaultBytes = File(input).readBytes()
        val contents = VaultImporter(keyDerivation).`import`(vaultBytes, password)
            ?: throw IllegalStateException("Failed to import vault (wrong password or corrupt file)")

        val outputDir = File(dir).also { it.mkdirs() }

        for ((name, data) in contents.keys) {
            File(outputDir, "keys/$name").apply {
                parentFile?.mkdirs()
                writeBytes(data)
            }
        }

        contents.dbFile?.let { data ->
            File(outputDir, "docwallet.db").writeBytes(data)
        }

        for ((name, data) in contents.files) {
            File(outputDir, name).apply {
                parentFile?.mkdirs()
                writeBytes(data)
            }
        }

        var totalFiles = contents.files.size
        if (contents.dbFile != null) totalFiles++
        echo("Extracted $totalFiles entries to $dir")
    }
}

class VaultRoundtrip : CliktCommand(name = "roundtrip", help = "Create vault and verify extraction matches source") {
    private val password by option("--password", "-p").required()
    private val dir by option("--dir", "-d").required()

    override fun run() {
        val sourceDir = File(dir)
        require(sourceDir.isDirectory) { "Not a directory: $dir" }

        val files = mutableMapOf<String, ByteArray>()
        sourceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                files[file.relativeTo(sourceDir).path] = file.readBytes()
            }
        }

        val keyDerivation = KeyDerivation(Argon2HasherJvm())
        val vaultBytes = VaultExporter(keyDerivation).export(files, null, password)

        val contents = VaultImporter(keyDerivation).`import`(vaultBytes, password)
            ?: throw IllegalStateException("Roundtrip failed: could not import created vault")

        var allMatch = true
        for ((name, originalData) in files) {
            val extractedData = contents.files[name]
            if (extractedData == null) {
                echo("MISSING: $name")
                allMatch = false
            } else if (!originalData.contentEquals(extractedData)) {
                echo("CORRUPT: $name (size mismatch: original=${originalData.size}, extracted=${extractedData.size})")
                allMatch = false
            }
        }

        if (allMatch && files.size == contents.files.size) {
            echo("Roundtrip: PASS (${files.size} files, ${vaultBytes.size} bytes vault)")
        } else {
            echo("Roundtrip: FAIL")
            if (files.size != contents.files.size) {
                echo("  File count mismatch: original=${files.size}, extracted=${contents.files.size}")
            }
        }
    }
}
