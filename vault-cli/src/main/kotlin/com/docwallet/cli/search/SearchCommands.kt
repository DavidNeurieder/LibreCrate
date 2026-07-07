package com.docwallet.cli.search

import com.docwallet.cli.SqlHandleJdbc
import com.docwallet.vault.database.DatabaseSchema
import com.docwallet.vault.database.VaultFtsIndexer
import com.docwallet.vault.database.VaultSearchEngine
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

fun searchCommand() = SearchCommands().subcommands(
    SearchInit(), SearchAdd(), SearchQuery()
)

class SearchCommands : CliktCommand(name = "search", help = "Full-text search operations") {
    override fun run() = Unit
}

class SearchInit : CliktCommand(name = "init", help = "Create a database with FTS5 schema") {
    private val db by option("--db", "-d").required()

    override fun run() {
        SqlHandleJdbc.open(db).use { handle ->
            DatabaseSchema.createAllTables(handle)
        }
        echo("Created database: $db")
    }
}

class SearchAdd : CliktCommand(name = "add", help = "Add a document to the database") {
    private val db by option("--db", "-d").required()
    private val id by option("--id", "-i").required()
    private val title by option("--title", "-t").required()
    private val author by option("--author", "-a").default("")
    private val description by option("--description", "-desc").default("")
    private val content by option("--content", "-c").default("")

    override fun run() {
        SqlHandleJdbc.open(db).use { handle ->
            handle.execSQL(
                """INSERT OR REPLACE INTO documents(id, title, file_name, mime_type, file_path,
                   file_size, page_count, author, description, thumbnail_path,
                   imported_at, last_opened_at, is_favorite, collection_id, encryption_iv,
                   text_content, barcode_format, barcode_value, current_page, reading_position)
                   VALUES (?, ?, '', '', '', 0, 0, ?, ?, NULL, ?, 0, 0, NULL, NULL, ?, NULL, NULL, 0, NULL)""".trimIndent(),
                arrayOf(id, title, author, description, System.currentTimeMillis(), content)
            )
            VaultFtsIndexer(handle).indexDocument(id, title, author, description, content)
        }
        echo("Added document: $id - $title")
    }
}

class SearchQuery : CliktCommand(name = "query", help = "Search documents using FTS5") {
    private val db by option("--db", "-d").required()
    private val query by option("--query", "-q").required()

    override fun run() {
        SqlHandleJdbc.open(db).use { handle ->
            val engine = VaultSearchEngine(handle)
            val results = engine.search(query)
            if (results.isEmpty()) {
                echo("No results for: $query")
            } else {
                echo("Results for '$query' (${results.size}):")
                for (doc in results) {
                    echo("  ${doc.id}: ${doc.title} (${doc.author})")
                }
            }
        }
    }
}
