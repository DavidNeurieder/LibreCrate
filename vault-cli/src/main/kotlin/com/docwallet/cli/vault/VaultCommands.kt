package com.docwallet.cli.vault

import com.docwallet.vault.crypto.Argon2HasherImpl
import com.docwallet.cli.DirectoryStorage
import com.docwallet.cli.JdbcSqlHandleOpener
import com.docwallet.vault.backup.VaultExporter
import com.docwallet.vault.backup.VaultImporter
import com.docwallet.vault.crypto.KeyDerivation
import com.docwallet.vault.database.VaultDatabase
import com.docwallet.vault.database.columnIndexOrThrow
import com.docwallet.vault.database.getStringOrNull
import com.docwallet.vault.format.VaultPackage
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.File

fun vaultCommand() = VaultCommands().subcommands(
    VaultCreate(), VaultExport(),
    VaultInspect(),
    VaultExtract(), VaultImport(),
    VaultMerge(),
    VaultRoundtrip(),
    VaultConflicts(),
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

        val keyDerivation = KeyDerivation(Argon2HasherImpl())
        val vaultBytes = VaultExporter(keyDerivation).export(files, dbData, password)
        File(output).writeBytes(vaultBytes)

        echo("Created vault: $output (${vaultBytes.size} bytes, ${files.size} files)")
    }
}

class VaultExport : CliktCommand(name = "export", help = "Export a directory as a .vault backup file") {
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

        val keyDerivation = KeyDerivation(Argon2HasherImpl())
        val vaultBytes = VaultExporter(keyDerivation).export(files, dbData, password)
        File(output).writeBytes(vaultBytes)

        echo("Exported vault: $output (${vaultBytes.size} bytes, ${files.size} files)")
        if (dbData != null) echo("  Database included: ${dbData.size} bytes")
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
        val keyDerivation = KeyDerivation(Argon2HasherImpl())
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

class VaultImport : CliktCommand(name = "import", help = "Import a .vault backup file into a directory") {
    private val password by option("--password", "-p").required()
    private val input by option("--input", "-i").required()
    private val dir by option("--dir", "-d").required()

    override fun run() {
        val keyDerivation = KeyDerivation(Argon2HasherImpl())
        val vaultBytes = File(input).readBytes()
        val contents = VaultImporter(keyDerivation).`import`(vaultBytes, password)
            ?: throw IllegalStateException("Failed to import vault (wrong password or corrupt file)")

        val outputDir = File(dir).also { it.mkdirs() }

        var keyCount = 0
        for ((name, data) in contents.keys) {
            File(outputDir, "keys/$name").apply {
                parentFile?.mkdirs()
                writeBytes(data)
            }
            keyCount++
        }

        var dbSize = 0L
        contents.dbFile?.let { data ->
            File(outputDir, "docwallet.db").writeBytes(data)
            dbSize = data.size.toLong()
        }

        var fileCount = 0
        for ((name, data) in contents.files) {
            File(outputDir, name).apply {
                parentFile?.mkdirs()
                writeBytes(data)
            }
            fileCount++
        }

        echo("Imported vault: $input")
        echo("  Keys: $keyCount")
        echo("  Database: ${if (dbSize > 0) "$dbSize bytes" else "none"}")
        echo("  Files: $fileCount")
        echo("  Output: $dir")
    }
}

class VaultMerge : CliktCommand(name = "merge", help = "Merge a .vault backup into an existing vault database") {
    private val password by option("--password", "-p").required()
    private val input by option("--input", "-i").required()
    private val db by option("--db", "-d").required()

    override fun run() {
        val vaultBytes = File(input).readBytes()
        val keyDerivation = KeyDerivation(Argon2HasherImpl())
        val contents = VaultImporter(keyDerivation).`import`(vaultBytes, password)
            ?: throw IllegalStateException("Failed to import vault (wrong password or corrupt file)")

        val dbFile = contents.dbFile
            ?: throw IllegalStateException("Vault does not contain a database")

        val opener = JdbcSqlHandleOpener()
        VaultDatabase(opener.open(db)).use { vault ->
            vault.initialize()
            // import into a temp in-memory db, then merge
            val tempDb = opener.openInMemory()
            val tempFile = File.createTempFile("vault-merge-", ".db").apply { deleteOnExit() }
            try {
                tempFile.writeBytes(dbFile)
                tempDb.execSQL("ATTACH DATABASE ? AS backup", arrayOf(tempFile.absolutePath))
                tempDb.execSQL("CREATE TABLE merged_docs AS SELECT * FROM backup.documents")
                tempDb.execSQL("DETACH DATABASE backup")

                val result = vault.mergeFrom(tempDb)

                // store files from vault
                if (contents.files.isNotEmpty()) {
                    val storage = DirectoryStorage(File(db).parentFile ?: File("."))
                    for ((name, data) in contents.files) {
                        storage.save("files/$name", data)
                    }
                    echo("  Stored ${contents.files.size} file(s)")
                }

                echo("Merge complete:")
                echo("  Added: ${result.documentsAdded}")
                echo("  Updated: ${result.documentsUpdated}")
                echo("  Conflicts: ${result.documentsConflicted}")
                echo("  Skipped: ${result.documentsSkipped}")
                if (result.hasConflicts) {
                    echo("  WARNING: ${result.documentsConflicted} conflict(s) detected — use 'vault conflicts' to view")
                }
            } finally {
                tempDb.close()
                tempFile.delete()
            }
        }
    }
}

class VaultConflicts : CliktCommand(name = "conflicts", help = "List conflicting documents in a vault database") {
    private val db by option("--db", "-d").required()

    override fun run() {
        val opener = JdbcSqlHandleOpener()
        VaultDatabase(opener.open(db)).use { vault ->
            vault.initialize()
            vault.handle.query(
                "SELECT id, title, file_name, conflict_with FROM documents WHERE is_conflict = 1"
            ).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    val id = cursor.getString(cursor.columnIndexOrThrow("id")) ?: ""
                    val title = cursor.getString(cursor.columnIndexOrThrow("title")) ?: ""
                    val fileName = cursor.getString(cursor.columnIndexOrThrow("file_name")) ?: ""
                    val conflictWith = cursor.getStringOrNull("conflict_with") ?: "unknown"
                    echo("  $id  $title ($fileName)")
                    echo("       conflict with: $conflictWith")
                    count++
                }
                if (count == 0) {
                    echo("No conflicts found.")
                } else {
                    echo("Total conflicts: $count")
                }
            }
        }
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

        val keyDerivation = KeyDerivation(Argon2HasherImpl())
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
