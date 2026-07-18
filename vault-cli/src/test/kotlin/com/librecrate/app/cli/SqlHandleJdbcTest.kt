package com.librecrate.app.cli

import com.librecrate.app.vault.database.SqlHandleOpener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SqlHandleJdbcTest {
    private lateinit var dbPath: String
    private lateinit var sut: SqlHandleJdbc

    @Before
    fun setUp() {
        dbPath = File.createTempFile("test", ".db").absolutePath
        sut = SqlHandleJdbc.open(dbPath)
    }

    @After
    fun tearDown() {
        sut.close()
        File(dbPath).delete()
    }

    @Test
    fun `execSQL creates table and insert works`() {
        sut.execSQL("CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)")
        sut.execSQL("INSERT INTO test VALUES (1, 'hello')")
        sut.query("SELECT name FROM test WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals("hello", cursor.getString(0))
        }
    }

    @Test
    fun `query with bindArgs`() {
        sut.execSQL("CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)")
        sut.execSQL("INSERT INTO test VALUES (1, 'alice')")
        sut.execSQL("INSERT INTO test VALUES (2, 'bob')")
        sut.query("SELECT name FROM test WHERE id = ?", arrayOf(2)).use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals("bob", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun `transaction commits successfully`() {
        sut.execSQL("CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)")
        sut.beginTransaction()
        sut.execSQL("INSERT INTO test VALUES (1, 'tx-data')")
        sut.setTransactionSuccessful()
        sut.endTransaction()
        sut.query("SELECT count(*) FROM test").use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun `transaction rollback on failure`() {
        sut.execSQL("CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)")
        sut.beginTransaction()
        sut.execSQL("INSERT INTO test VALUES (1, 'will-rollback')")
        sut.endTransaction()
        sut.query("SELECT count(*) FROM test").use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun `cursor column operations`() {
        sut.execSQL("CREATE TABLE test (id INTEGER, val TEXT, data BLOB)")
        sut.execSQL("INSERT INTO test VALUES (42, 'foo', x'deadbeef')")
        sut.query("SELECT * FROM test").use { cursor ->
            assertTrue(cursor.moveToNext())
            assertEquals(3, cursor.columnCount)
            assertEquals("id", cursor.columnName(0))
            assertEquals(0, cursor.columnIndex("id"))
            assertEquals(1, cursor.columnIndex("val"))
            assertEquals(42, cursor.getInt(0))
            assertEquals(42L, cursor.getLong(0))
            assertEquals("foo", cursor.getString(1))
            assertArrayEquals(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()), cursor.getBlob(2))
        }
    }

    @Test
    fun `in-memory database works`() {
        SqlHandleJdbc.openInMemory().use { mem ->
            mem.execSQL("CREATE TABLE t (x TEXT)")
            mem.execSQL("INSERT INTO t VALUES ('mem')")
            mem.query("SELECT x FROM t").use { c ->
                assertTrue(c.moveToNext())
                assertEquals("mem", c.getString(0))
            }
        }
    }
}

class JdbcSqlHandleOpenerTest {
    @Test
    fun `opener creates and deletes database`() {
        val path = File.createTempFile("opener", ".db").absolutePath
        val opener: SqlHandleOpener = JdbcSqlHandleOpener()
        opener.open(path).use { db ->
            db.execSQL("CREATE TABLE t (x TEXT)")
            db.execSQL("INSERT INTO t VALUES ('opened')")
        }
        assertTrue(File(path).exists())
        opener.delete(path)
        assertFalse(File(path).exists())
    }

    @Test
    fun `openInMemory returns working handle`() {
        val opener: SqlHandleOpener = JdbcSqlHandleOpener()
        opener.openInMemory().use { db ->
            db.execSQL("CREATE TABLE t (x TEXT)")
            db.execSQL("INSERT INTO t VALUES ('mem')")
            db.query("SELECT x FROM t").use { c ->
                assertTrue(c.moveToNext())
                assertEquals("mem", c.getString(0))
            }
        }
    }

    @Test
    fun `isNull on nullable column`() {
        val opener: SqlHandleOpener = JdbcSqlHandleOpener()
        opener.openInMemory().use { db ->
            db.execSQL("CREATE TABLE t (id INT, val TEXT)")
            db.execSQL("INSERT INTO t VALUES (1, NULL)")
            db.query("SELECT val FROM t WHERE id = 1").use { c ->
                assertTrue(c.moveToNext())
                assertTrue(c.isNull(0))
                assertNull(c.getString(0))
                assertNull(c.getBlob(0))
            }
        }
    }
}
