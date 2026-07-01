package com.docwallet.data.import

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.docwallet.data.db.DocumentDao
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.data.encryption.FileEncryptor
import com.docwallet.data.model.Document
import com.docwallet.data.model.DocumentType
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
    private val pdfProcessor: PdfProcessor = PdfProcessor(),
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
            val processor = getProcessor(documentType)
            val result = processor?.process(tempFile, mimeType)

            val masterKey = encryptionManager.getMasterKeyForSession()
                ?: return@withContext null.also { Log.e(TAG, "Master key not available") }

            val filesDir = File(context.filesDir, "files").also { it.mkdirs() }
            val encryptedFile = File(filesDir, "${UUID.randomUUID()}.enc")
            val iv = fileEncryptor.encrypt(tempFile, encryptedFile, masterKey)

            val thumbnailPath = result?.thumbnailBitmap?.let { saveThumbnail(it, masterKey) }

            val document = Document(
                id = UUID.randomUUID().toString(),
                title = result?.title?.takeIf { it.isNotBlank() } ?: fileName,
                fileName = fileName,
                mimeType = mimeType,
                filePath = encryptedFile.absolutePath,
                fileSize = fileSize,
                pageCount = result?.pageCount ?: 0,
                author = result?.author ?: "",
                description = result?.textContent?.take(200) ?: "",
                thumbnailPath = thumbnailPath,
                encryptionIv = iv,
                textContent = result?.textContent,
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

    private fun getProcessor(documentType: DocumentType): DocumentProcessor? {
        return when (documentType) {
            DocumentType.PDF -> pdfProcessor
            DocumentType.EPUB -> EpubProcessor()
            DocumentType.PKPASS -> PkPassProcessor()
            DocumentType.CBZ, DocumentType.CBR -> ComicProcessor()
            DocumentType.IMAGE -> ImageProcessor()
            DocumentType.NOTE -> null
            DocumentType.UNKNOWN -> null
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
