package com.librecrate.app.vault.database

class VaultDatabase(val handle: SqlHandle) : AutoCloseable {

    fun initialize() {
        DatabaseSchema.createAllTables(handle)
    }

    fun mergeFrom(backupDb: SqlHandle): MergeResult {
        return VaultDatabaseMerger().merge(backupDb, handle)
    }

    val searchEngine: VaultSearchEngine get() = VaultSearchEngine(handle)
    val ftsIndexer: VaultFtsIndexer get() = VaultFtsIndexer(handle)

    override fun close() {
        handle.close()
    }
}
