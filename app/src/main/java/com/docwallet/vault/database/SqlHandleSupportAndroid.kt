package com.docwallet.vault.database

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase

class SqlHandleSupportAndroid(private val db: SupportSQLiteDatabase) : SqlHandle {
    override fun execSQL(sql: String, bindArgs: Array<Any?>) {
        db.execSQL(sql, bindArgs)
    }

    override fun query(sql: String, bindArgs: Array<Any?>): SqlCursor {
        val stringArgs = bindArgs.map { it?.toString() }.toTypedArray()
        return SupportCursorAdapter(db.query(sql, stringArgs))
    }

    override fun beginTransaction() {
        db.beginTransaction()
    }

    override fun setTransactionSuccessful() {
        db.setTransactionSuccessful()
    }

    override fun endTransaction() {
        db.endTransaction()
    }

    override fun close() {
        db.close()
    }
}

private class SupportCursorAdapter(private val cursor: Cursor) : SqlCursor {
    override val columnCount: Int get() = cursor.columnCount

    override fun columnName(index: Int): String = cursor.getColumnName(index)

    override fun columnIndex(name: String): Int = cursor.getColumnIndex(name)

    override fun getString(index: Int): String? = cursor.getString(index)

    override fun getLong(index: Int): Long = cursor.getLong(index)

    override fun getInt(index: Int): Int = cursor.getInt(index)

    override fun getBlob(index: Int): ByteArray? = cursor.getBlob(index)

    override fun isNull(index: Int): Boolean = cursor.isNull(index)

    override fun moveToNext(): Boolean = cursor.moveToNext()

    override fun close() = cursor.close()
}
