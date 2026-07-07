package com.docwallet.vault.database

interface SqlCursor : AutoCloseable {
    val columnCount: Int
    fun columnName(index: Int): String
    fun columnIndex(name: String): Int
    fun getString(index: Int): String?
    fun getLong(index: Int): Long
    fun getInt(index: Int): Int
    fun getBlob(index: Int): ByteArray?
    fun isNull(index: Int): Boolean
    fun moveToNext(): Boolean
    override fun close()
}

fun SqlCursor.columnIndexOrThrow(name: String): Int {
    val index = columnIndex(name)
    if (index == -1) throw IllegalArgumentException("Column '$name' not found in cursor")
    return index
}

fun SqlCursor.getStringOrNull(column: String): String? {
    val index = columnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

fun SqlCursor.getBlobOrNull(column: String): ByteArray? {
    val index = columnIndexOrThrow(column)
    return if (isNull(index)) null else getBlob(index)
}
