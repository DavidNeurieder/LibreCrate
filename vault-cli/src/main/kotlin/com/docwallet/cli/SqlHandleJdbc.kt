package com.docwallet.cli

import com.docwallet.vault.database.SqlCursor
import com.docwallet.vault.database.SqlHandle
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.util.Properties

class SqlHandleJdbc private constructor(private val conn: Connection) : SqlHandle {
    private var transactionSuccessful = false

    companion object {
        fun open(path: String): SqlHandleJdbc {
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:$path")
            return SqlHandleJdbc(conn)
        }

        fun openInMemory(): SqlHandleJdbc {
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection("jdbc:sqlite:")
            return SqlHandleJdbc(conn)
        }
    }

    override fun execSQL(sql: String, bindArgs: Array<Any?>) {
        if (bindArgs.isEmpty()) {
            conn.createStatement().use { stmt -> stmt.execute(sql) }
        } else {
            conn.prepareStatement(sql).use { stmt ->
                setBindArgs(stmt, bindArgs)
                stmt.execute()
            }
        }
    }

    override fun query(sql: String, bindArgs: Array<Any?>): SqlCursor {
        return if (bindArgs.isEmpty()) {
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery(sql)
            JdbcSqlCursor(rs, stmt)
        } else {
            val stmt = conn.prepareStatement(sql)
            setBindArgs(stmt, bindArgs)
            val rs = stmt.executeQuery()
            JdbcSqlCursor(rs, stmt)
        }
    }

    override fun beginTransaction() {
        conn.autoCommit = false
        transactionSuccessful = false
    }

    override fun setTransactionSuccessful() {
        transactionSuccessful = true
    }

    override fun endTransaction() {
        try {
            if (transactionSuccessful) conn.commit()
            else conn.rollback()
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() {
        conn.close()
    }

    private fun setBindArgs(stmt: java.sql.PreparedStatement, bindArgs: Array<Any?>) {
        for (i in bindArgs.indices) {
            when (val arg = bindArgs[i]) {
                null -> stmt.setNull(i + 1, java.sql.Types.NULL)
                is String -> stmt.setString(i + 1, arg)
                is Long -> stmt.setLong(i + 1, arg)
                is Int -> stmt.setInt(i + 1, arg)
                is ByteArray -> stmt.setBytes(i + 1, arg)
                is Boolean -> stmt.setInt(i + 1, if (arg) 1 else 0)
                else -> stmt.setObject(i + 1, arg)
            }
        }
    }
}

private class JdbcSqlCursor(
    private val rs: ResultSet,
    private val stmt: AutoCloseable,
) : SqlCursor {
    private val meta = rs.metaData

    override val columnCount: Int get() = meta.columnCount

    override fun columnName(index: Int): String = meta.getColumnName(index + 1)

    override fun columnIndex(name: String): Int {
        for (i in 1..meta.columnCount) {
            if (meta.getColumnName(i).equals(name, ignoreCase = true)) return i - 1
        }
        return -1
    }

    override fun getString(index: Int): String? = rs.getString(index + 1)

    override fun getLong(index: Int): Long = rs.getLong(index + 1)

    override fun getInt(index: Int): Int = rs.getInt(index + 1)

    override fun getBlob(index: Int): ByteArray? = rs.getBytes(index + 1)

    override fun isNull(index: Int): Boolean = rs.getObject(index + 1) == null

    override fun moveToNext(): Boolean = rs.next()

    override fun close() {
        rs.close()
        stmt.close()
    }
}
