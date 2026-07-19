package com.librecrate.app.vault.database

import android.content.Context
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteDatabaseHook
import java.io.File

class SqlCipherOpener(
    private val context: Context,
    private val password: ByteArray,
) : SqlHandleOpener {

    companion object {
        /** PRAGMAs matching vault-native's db/schema.rs::open_encrypted (SQLCipher V4, raw key). */
        val CIPHER_HOOK = SQLiteDatabaseHook { db ->
            db.rawExecSQL("PRAGMA cipher_compatibility = 4")
            db.rawExecSQL("PRAGMA cipher_kdf_algorithm = sha512")
            db.rawExecSQL("PRAGMA cipher_hmac_algorithm = sha512")
            db.rawExecSQL("PRAGMA kdf_iter = 256000")
            db.rawExecSQL("PRAGMA cipher_page_size = 4096")
            // cipher_default_kdf_iter is set only when creating; raw key mode ignores it
        }
    }

    override fun open(path: String): SqlHandle {
        SQLiteDatabase.loadLibs(context)
        val db = SQLiteDatabase.openOrCreateDatabase(path, password, null, CIPHER_HOOK)
        return SqlHandleAndroid(db)
    }

    override fun openInMemory(): SqlHandle {
        SQLiteDatabase.loadLibs(context)
        val db = SQLiteDatabase.openOrCreateDatabase(":memory:", password, null, CIPHER_HOOK)
        return SqlHandleAndroid(db)
    }

    override fun delete(path: String) {
        File(path).delete()
    }
}
