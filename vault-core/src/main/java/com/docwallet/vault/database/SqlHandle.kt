package com.docwallet.vault.database

interface SqlHandle : AutoCloseable {
    fun execSQL(sql: String, bindArgs: Array<Any?> = emptyArray())
    fun query(sql: String, bindArgs: Array<Any?> = emptyArray()): SqlCursor
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()
    override fun close()
}
