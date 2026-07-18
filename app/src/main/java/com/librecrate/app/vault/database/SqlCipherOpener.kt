package com.librecrate.app.vault.database

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import java.io.File

class SqlCipherOpener(
    private val context: Context,
    private val password: ByteArray,
) : SqlHandleOpener {
    override fun open(path: String): SqlHandle {
        SQLiteDatabase.loadLibs(context)
        val db = SQLiteDatabase.openOrCreateDatabase(path, password, null)
        return SqlHandleAndroid(db)
    }

    override fun openInMemory(): SqlHandle {
        SQLiteDatabase.loadLibs(context)
        val db = SQLiteDatabase.openOrCreateDatabase(":memory:", password, null)
        return SqlHandleAndroid(db)
    }

    override fun delete(path: String) {
        File(path).delete()
    }
}
