package com.docwallet.data.encryption

import com.docwallet.vault.crypto.KeyStore
import java.io.File

class FileKeyStore(private val dir: File) : KeyStore {
    override fun read(name: String): ByteArray? {
        val file = File(dir, name)
        return if (file.exists()) file.readBytes() else null
    }

    override fun write(name: String, data: ByteArray) {
        dir.mkdirs()
        File(dir, name).writeBytes(data)
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    override fun exists(name: String): Boolean {
        return File(dir, name).exists()
    }
}
