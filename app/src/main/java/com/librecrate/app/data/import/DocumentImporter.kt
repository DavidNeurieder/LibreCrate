package com.librecrate.app.data.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.model.DocumentType
import com.librecrate.app.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class DocumentImporter(
    private val context: Context,
    private val vaultRepository: VaultRepository,
) {
    companion object {
        private const val TAG = "DocumentImporter"
    }

    suspend fun importDocument(uri: Uri, mimeType: String): Document? = withContext(Dispatchers.IO) {
        val tempFile = try {
            copyUriToTempFile(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy temp file", e); return@withContext null
        }
        try {
            val fileName = getFileName(uri) ?: "unknown"
            val fileData = tempFile.readBytes()

            val docId = UUID.randomUUID().toString()
            val resultId = vaultRepository.importDocument(
                id = docId, title = fileName, fileData = fileData,
                mimeType = mimeType, author = "",
                description = "", textContent = null,
            )
            if (resultId == null) { Log.e(TAG, "importDocument returned null"); return@withContext null }
            vaultRepository.getDocument(docId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import document", e); null
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    suspend fun importNote(title: String, content: String): Document? = withContext(Dispatchers.IO) {
        val safeName = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val fileName = "$safeName.md"
        val docId = UUID.randomUUID().toString()
        val resultId = vaultRepository.importDocument(
            id = docId, title = title, fileData = content.encodeToByteArray(),
            mimeType = "text/markdown", author = "",
            description = content.take(200), textContent = content,
        )
        if (resultId == null) { Log.e(TAG, "importNote returned null"); return@withContext null }
        vaultRepository.getDocument(docId)
    }

    private fun copyUriToTempFile(uri: Uri): File {
        val tempFile = File(context.cacheDir, "import_${UUID.randomUUID()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        } ?: throw IOException("Cannot open input stream for $uri")
        return tempFile
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
