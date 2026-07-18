package com.librecrate.app.data.import

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.librecrate.app.data.db.DocumentDao
import com.librecrate.app.data.encryption.EncryptionManager
import com.librecrate.app.vault.crypto.FileEncryptor
import com.librecrate.app.data.model.Document
import com.librecrate.app.reader.pdf.PdfDocumentProcessor
import com.librecrate.app.reader.epub.EpubDocumentProcessor
import com.librecrate.app.vault.model.DocumentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class DocumentImporter(
    private val context: Context,
    private val documentDao: DocumentDao,
    private val fileEncryptor: FileEncryptor,
    private val encryptionManager: EncryptionManager,
    private val pdfProcessor: com.librecrate.app.vault.reader.DocumentProcessor = PdfDocumentProcessor(),
    private val epubProcessor: com.librecrate.app.vault.reader.DocumentProcessor = EpubDocumentProcessor(),
) {
    companion object {
        private const val TAG = "DocumentImporter"
    }

    suspend fun importDocument(uri: Uri, mimeType: String): Document? = withContext(Dispatchers.IO) {
        val tempFile = try {
            copyUriToTempFile(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy temp file", e)
            return@withContext null
        }
        try {
            val fileName = getFileName(uri) ?: "unknown"
            val fileSize = tempFile.length()

            val documentType = DocumentType.fromMimeType(mimeType)
            val masterKey = encryptionManager.getMasterKeyForSession()
                ?: return@withContext null.also { Log.e(TAG, "Master key not available") }

            val filesDir = File(context.filesDir, "files").also { it.mkdirs() }
            val encryptedFile = File(filesDir, "${UUID.randomUUID()}.enc")
            val iv = fileEncryptor.encrypt(tempFile, encryptedFile, masterKey)

            val (title, author, pageCount, textContent, thumbnailPath) = processDocument(
                tempFile, mimeType, documentType, masterKey
            )

            val document = Document(
                id = UUID.randomUUID().toString(),
                title = title ?: fileName,
                fileName = fileName,
                mimeType = mimeType,
                filePath = encryptedFile.absolutePath,
                fileSize = fileSize,
                pageCount = pageCount,
                author = author ?: "",
                description = textContent?.take(200) ?: "",
                thumbnailPath = thumbnailPath,
                encryptionIv = iv,
                textContent = textContent,
            )

            documentDao.insert(document)
            document
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import document", e)
            null
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private suspend fun processDocument(
        file: File, mimeType: String, documentType: DocumentType, masterKey: ByteArray,
    ): ProcessedDocument {
        return when (documentType) {
            DocumentType.PDF -> {
                val result = pdfProcessor.process(file, mimeType)
                val thumbPath = result.thumbnailData?.let { saveThumbnailBytes(it, masterKey) }
                ProcessedDocument(result.title, result.author, result.pageCount, result.textContent, thumbPath)
            }
            DocumentType.EPUB -> {
                val result = epubProcessor.process(file, mimeType)
                val thumbPath = result.thumbnailData?.let { saveThumbnailBytes(it, masterKey) }
                ProcessedDocument(result.title, result.author, result.pageCount, result.textContent, thumbPath)
            }
            else -> {
                val processor = getLegacyProcessor(documentType)
                val result = processor?.process(file, mimeType)
                val thumbPath = result?.thumbnailBitmap?.let { saveThumbnail(it, masterKey) }
                ProcessedDocument(
                    result?.title, result?.author, result?.pageCount ?: 0,
                    result?.textContent, thumbPath,
                )
            }
        }
    }

    private data class ProcessedDocument(
        val title: String?,
        val author: String?,
        val pageCount: Int,
        val textContent: String?,
        val thumbnailPath: String?,
    )

    private fun saveThumbnailBytes(data: ByteArray, masterKey: ByteArray): String {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }
        val thumbFile = File(filesDir, "${UUID.randomUUID()}_thumb")
        val (iv, encrypted) = fileEncryptor.encryptBytes(data, masterKey)
        thumbFile.writeBytes(iv + encrypted)
        return thumbFile.absolutePath
    }

    suspend fun importNote(title: String, content: String): Document? = withContext(Dispatchers.IO) {
        val safeName = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val fileName = "$safeName.md"
        val tempFile = File(context.cacheDir, fileName)
        try {
            tempFile.writeText(content)

            val masterKey = encryptionManager.getMasterKeyForSession()
                ?: return@withContext null.also { Log.e(TAG, "Master key not available") }

            val filesDir = File(context.filesDir, "files").also { it.mkdirs() }
            val encryptedFile = File(filesDir, "${UUID.randomUUID()}.enc")
            val iv = fileEncryptor.encrypt(tempFile, encryptedFile, masterKey)

            val document = Document(
                id = UUID.randomUUID().toString(),
                title = title,
                fileName = fileName,
                mimeType = "text/markdown",
                filePath = encryptedFile.absolutePath,
                fileSize = tempFile.length(),
                pageCount = 0,
                author = "",
                description = content.take(200),
                thumbnailPath = null,
                encryptionIv = iv,
                textContent = content,
            )

            documentDao.insert(document)
            document
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import note", e)
            null
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun getLegacyProcessor(documentType: DocumentType): DocumentProcessor? {
        return when (documentType) {
            DocumentType.PKPASS -> PkPassProcessor()
            DocumentType.CBZ, DocumentType.CBR -> ComicProcessor()
            DocumentType.IMAGE -> ImageProcessor()
            DocumentType.PDF, DocumentType.EPUB, DocumentType.NOTE, DocumentType.UNKNOWN -> null
        }
    }

    private fun copyUriToTempFile(uri: Uri): File {
        val tempFile = File(context.cacheDir, "import_${UUID.randomUUID()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Cannot open input stream for $uri")
        return tempFile
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return name
    }

    private fun saveThumbnail(bitmap: Bitmap, masterKey: ByteArray): String {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }
        val thumbFile = File(filesDir, "${UUID.randomUUID()}_thumb")
        val jpegBytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            stream.toByteArray()
        }
        val (iv, encrypted) = fileEncryptor.encryptBytes(jpegBytes, masterKey)
        thumbFile.writeBytes(iv + encrypted)
        return thumbFile.absolutePath
    }
}
