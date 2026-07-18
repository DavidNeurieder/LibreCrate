package com.librecrate.app.vault.database

interface SqlHandleOpener {
    fun open(path: String): SqlHandle
    fun openInMemory(): SqlHandle
    fun delete(path: String)
}
