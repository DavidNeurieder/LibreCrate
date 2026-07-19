package com.librecrate.app.data.storage

import android.content.Context
import java.io.File

class AndroidFileStorage(
    private val context: Context,
    private val subdir: String = "files",
) {
    private val storageDir: File
        get() = File(context.filesDir, subdir).also { it.mkdirs() }

    fun save(id: String, data: ByteArray) { File(storageDir, id).writeBytes(data) }
    fun load(id: String): ByteArray = File(storageDir, id).readBytes()
    fun delete(id: String) { File(storageDir, id).delete() }
    fun exists(id: String): Boolean = File(storageDir, id).exists()
}
