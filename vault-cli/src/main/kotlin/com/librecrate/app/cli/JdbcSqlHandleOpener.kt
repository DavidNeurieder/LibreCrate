package com.librecrate.app.cli

import com.librecrate.app.vault.database.SqlHandleOpener
import java.io.File

class JdbcSqlHandleOpener : SqlHandleOpener {
    override fun open(path: String): SqlHandleJdbc {
        return SqlHandleJdbc.open(path)
    }

    override fun openInMemory(): SqlHandleJdbc {
        return SqlHandleJdbc.openInMemory()
    }

    override fun delete(path: String) {
        File(path).delete()
    }
}
