package com.librecrate.app.data.storage

import android.content.Context
import com.librecrate.app.vault.storage.Storage
import java.io.File

class AndroidFileStorage(
    private val context: Context,
    private val subdir: String = "files",
) : Storage {

    private val storageDir: File
        get() = File(context.filesDir, subdir).also { it.mkdirs() }

    override fun save(id: String, data: ByteArray) {
        File(storageDir, id).writeBytes(data)
    }

    override fun load(id: String): ByteArray {
        return File(storageDir, id).readBytes()
    }

    override fun delete(id: String) {
        File(storageDir, id).delete()
    }

    override fun exists(id: String): Boolean {
        return File(storageDir, id).exists()
    }
}
