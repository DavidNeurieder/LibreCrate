package com.librecrate.app.cli.document

import com.librecrate.app.cli.DirectoryStorage
import com.librecrate.app.cli.JdbcSqlHandleOpener
import com.librecrate.app.vault.database.DocumentManager
import com.librecrate.app.vault.database.VaultDatabase
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.File

fun documentCommand() = DocumentCommands().subcommands(
    DocumentAdd(), DocumentList(), DocumentGet(), DocumentDelete()
)

class DocumentCommands : CliktCommand(name = "document", help = "Document management operations") {
    override fun run() = Unit
}

class DocumentAdd : CliktCommand(name = "add", help = "Import a document into the vault database") {
    private val db by option("--db", "-d").required()
    private val id by option("--id", "-i").required()
    private val title by option("--title", "-t").required()
    private val file by option("--file", "-f").required()
    private val author by option("--author", "-a").default("")
    private val description by option("--description", "-desc").default("")
    private val mime by option("--mime", "-m").default("")

    override fun run() {
        val fileBytes = File(file).readBytes()
        val mimeType = if (mime.isNotEmpty()) mime else guessMimeType(file)
        val opener = JdbcSqlHandleOpener()
        VaultDatabase(opener.open(db)).use { vault ->
            vault.initialize()
            val storage = DirectoryStorage(File(db).parentFile ?: File("."))
            val mgr = DocumentManager(vault.handle, storage)
            val doc = mgr.importDocument(
                id = id, title = title, file = fileBytes,
                mimeType = mimeType, author = author, description = description,
            )
            echo("Imported document: ${doc.id} - ${doc.title} (${doc.mimeType}, ${doc.fileSize} bytes)")
        }
    }

    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "png", "jpg", "jpeg", "gif", "webp", "bmp" -> "image/$ext"
            "md" -> "text/markdown"
            "txt" -> "text/plain"
            "cbz" -> "application/vnd.comicbook+zip"
            "cbr" -> "application/x-cbr"
            "pkpass" -> "application/vnd.apple.pkpass"
            else -> "application/octet-stream"
        }
    }
}

class DocumentList : CliktCommand(name = "list", help = "List documents in the vault database") {
    private val db by option("--db", "-d").required()

    override fun run() {
        val opener = JdbcSqlHandleOpener()
        VaultDatabase(opener.open(db)).use { vault ->
            vault.initialize()
            val storage = DirectoryStorage(File(db).parentFile ?: File("."))
            val mgr = DocumentManager(vault.handle, storage)
            val docs = mgr.listDocuments()
            if (docs.isEmpty()) {
                echo("No documents found.")
            } else {
                echo("Documents (${docs.size}):")
                for (doc in docs) {
                    echo("  ${doc.id}  ${doc.title}  (${doc.mimeType}, ${doc.fileSize} bytes)")
                }
            }
        }
    }
}

class DocumentGet : CliktCommand(name = "get", help = "Show document details or export file") {
    private val db by option("--db", "-d").required()
    private val id by option("--id", "-i").required()
    private val export by option("--export", "-e").default("")

    override fun run() {
        val opener = JdbcSqlHandleOpener()
        VaultDatabase(opener.open(db)).use { vault ->
            vault.initialize()
            val storage = DirectoryStorage(File(db).parentFile ?: File("."))
            val mgr = DocumentManager(vault.handle, storage)
            val doc = mgr.getDocument(id)
            if (doc == null) {
                echo("Document not found: $id")
                return@use
            }
            echo("ID:          ${doc.id}")
            echo("Title:       ${doc.title}")
            echo("File name:   ${doc.fileName}")
            echo("MIME type:   ${doc.mimeType}")
            echo("File size:   ${doc.fileSize} bytes")
            echo("Author:      ${doc.author}")
            echo("Description: ${doc.description}")
            echo("Imported:    ${doc.importedAt}")

            if (export.isNotEmpty()) {
                val data = mgr.loadDocumentFile(id)
                if (data != null) {
                    File(export).writeBytes(data)
                    echo("Exported to: $export")
                } else {
                    echo("File data not found in storage")
                }
            }
        }
    }
}

class DocumentDelete : CliktCommand(name = "delete", help = "Delete a document from the vault database") {
    private val db by option("--db", "-d").required()
    private val id by option("--id", "-i").required()

    override fun run() {
        val opener = JdbcSqlHandleOpener()
        VaultDatabase(opener.open(db)).use { vault ->
            vault.initialize()
            val storage = DirectoryStorage(File(db).parentFile ?: File("."))
            val mgr = DocumentManager(vault.handle, storage)
            mgr.deleteDocument(id)
            echo("Deleted document: $id")
        }
    }
}
