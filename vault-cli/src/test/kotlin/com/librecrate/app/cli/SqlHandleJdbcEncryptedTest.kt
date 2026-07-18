package com.librecrate.app.cli

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.sql.SQLException

class SqlHandleJdbcEncryptedTest {

    private val masterKey = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun encryptedRoundtrip() {
        val path = File.createTempFile("encrypted", ".db").absolutePath
        SqlHandleJdbc.openEncrypted(path, masterKey).use { handle ->
            handle.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, val TEXT)")
            handle.execSQL("INSERT INTO t VALUES (?, ?)", arrayOf(1, "sqlcipher works"))
        }

        SqlHandleJdbc.openEncrypted(path, masterKey).use { handle ->
            handle.query("SELECT val FROM t WHERE id = 1").use { cursor ->
                cursor.moveToNext()
                assertEquals("sqlcipher works", cursor.getString(0))
            }
        }

        File(path).delete()
    }

    @Test
    fun wrongKeyFails() {
        val key1 = ByteArray(32) { 0x01 }
        val key2 = ByteArray(32) { 0x02 }
        val path = File.createTempFile("encrypted", ".db").absolutePath

        SqlHandleJdbc.openEncrypted(path, key1).use { handle ->
            handle.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY)")
            handle.execSQL("INSERT INTO t VALUES (1)")
        }

        assertThrows(SQLException::class.java) {
            SqlHandleJdbc.openEncrypted(path, key2).use { handle ->
                handle.query("SELECT COUNT(*) FROM t").use { }
            }
        }

        File(path).delete()
    }

    @Test
    fun plainOpenFailsOnEncryptedDb() {
        val path = File.createTempFile("encrypted", ".db").absolutePath
        SqlHandleJdbc.openEncrypted(path, masterKey).use { handle ->
            handle.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        }

        assertThrows(SQLException::class.java) {
            SqlHandleJdbc.open(path).use { handle ->
                handle.query("SELECT COUNT(*) FROM t").use { }
            }
        }

        File(path).delete()
    }

    @Test
    fun encryptedInMemoryWorks() {
        val path = File.createTempFile("encrypted", ".db").absolutePath
        SqlHandleJdbc.openEncrypted(path, masterKey).use { handle ->
            handle.execSQL("CREATE TABLE mem (id INTEGER PRIMARY KEY, data BLOB)")
            handle.execSQL("INSERT INTO mem VALUES (?, ?)", arrayOf(1, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
        }

        SqlHandleJdbc.openEncrypted(path, masterKey).use { handle ->
            handle.query("SELECT data FROM mem WHERE id = 1").use { cursor ->
                cursor.moveToNext()
                assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), cursor.getBlob(0))
            }
        }

        File(path).delete()
    }
}
