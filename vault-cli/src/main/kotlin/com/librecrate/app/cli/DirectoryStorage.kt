package com.librecrate.app.cli

import com.librecrate.app.vault.storage.Storage
import java.io.File

class DirectoryStorage(private val baseDir: File) : Storage {
    private fun file(id: String): File = File(baseDir, id).also {
        it.parentFile?.mkdirs()
    }

    override fun save(id: String, data: ByteArray) {
        file(id).writeBytes(data)
    }

    override fun load(id: String): ByteArray = file(id).readBytes()

    override fun delete(id: String) {
        file(id).delete()
    }

    override fun exists(id: String): Boolean = file(id).exists()
}
