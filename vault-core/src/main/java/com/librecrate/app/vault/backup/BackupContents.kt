package com.librecrate.app.vault.backup

data class BackupContents(
    val keys: Map<String, ByteArray>,
    val dbFile: ByteArray?,
    val files: Map<String, ByteArray>,
)
