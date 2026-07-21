package com.librecrate.app.data.import
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.librecrate.app.data.import.ComicProcessor
import com.librecrate.app.util.ErrorLogger
import com.librecrate.app.data.import.ImageProcessor
import com.librecrate.app.data.import.PkPassProcessor
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.vault.VaultRepository
import com.librecrate.app.reader.epub.EpubDocumentProcessor
import com.librecrate.app.reader.pdf.PdfDocumentProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    sealed class ImportResult {
        data class Success(val document: Document) : ImportResult()
        data class Duplicate(val document: Document) : ImportResult()
    }
    private data class DocumentContent(
        val textContent: String?,
        val thumbnailData: ByteArray?,
    )
    suspend fun importDocument(uri: Uri, mimeType: String): ImportResult? = withContext(Dispatchers.IO) {
        val tempFile = try {
            copyUriToTempFile(uri)
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "Failed to copy temp file", e); return@withContext null
        }
        try {
            val fileName = getFileName(uri) ?: "unknown"
            val fileData = tempFile.readBytes()
            val content = processDocument(tempFile, mimeType)
            val docId = UUID.randomUUID().toString()
            val resultId = vaultRepository.importDocument(
                id = docId, title = fileName, fileData = fileData,
                mimeType = mimeType, author = "",
                description = "", textContent = content.textContent,
            )
            if (resultId == null) { ErrorLogger.logWarning(context, TAG, "importDocument returned null"); return@withContext null }
            val actualId = resultId
            val isDuplicate = actualId != docId
            if (!isDuplicate) {
                content.thumbnailData?.let { thumbData ->
                    vaultRepository.storeThumbnail(actualId, thumbData)
                }
            }
            val doc = vaultRepository.getDocument(actualId)
            if (doc == null) { ErrorLogger.logWarning(context, TAG, "importDocument: getDocument returned null for $actualId"); return@withContext null }
            if (isDuplicate) ImportResult.Duplicate(doc) else ImportResult.Success(doc)
        } catch (e: Exception) {
            ErrorLogger.logException(context, TAG, "Failed to import document", e); null

        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }
    suspend fun importNote(title: String, content: String): ImportResult? = withContext(Dispatchers.IO) {
        val safeName = title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val fileName = "$safeName.md"
        val docId = UUID.randomUUID().toString()
        val resultId = vaultRepository.importDocument(
            id = docId, title = title, fileData = content.encodeToByteArray(),
            mimeType = "text/markdown", author = "",
            description = content.take(200), textContent = content,
        )
        if (resultId == null) { ErrorLogger.logWarning(context, TAG, "importNote returned null"); return@withContext null }
        val actualId = resultId
        val isDuplicate = actualId != docId
        val doc = vaultRepository.getDocument(actualId)
        if (doc == null) { ErrorLogger.logWarning(context, TAG, "importNote: getDocument returned null for $actualId"); return@withContext null }
        if (isDuplicate) ImportResult.Duplicate(doc) else ImportResult.Success(doc)
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
    private suspend fun processDocument(file: File, mimeType: String): DocumentContent {
        return try {
            when {
                mimeType == "application/pdf" -> {
                    val result = PdfDocumentProcessor().process(file, mimeType)
                    DocumentContent(result.textContent, result.thumbnailData)
                }
                mimeType == "application/epub+zip" -> {
                    val result = EpubDocumentProcessor().process(file, mimeType)
                    DocumentContent(result.textContent, result.thumbnailData)
                }
                mimeType.startsWith("image/") -> {
                    val result = ImageProcessor().process(file, mimeType)
                    DocumentContent(null, result.thumbnailBitmap?.toPngBytes())
                }
                mimeType == "application/vnd.comicbook+zip" || mimeType == "application/x-cbr" -> {
                    val result = ComicProcessor().process(file, mimeType)
                    DocumentContent(null, result.thumbnailBitmap?.toPngBytes())
                }
                mimeType == "application/vnd.apple.pkpass" -> {
                    val result = PkPassProcessor().process(file, mimeType)
                    DocumentContent(null, result.thumbnailBitmap?.toPngBytes())
                }
                else -> DocumentContent(null, null)
            }
        } catch (e: Exception) {
            ErrorLogger.logWarning(context, TAG, "Failed to process document", e)
            DocumentContent(null, null)
        }
    }
    private fun Bitmap.toPngBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 90, out)
        return out.toByteArray()
    }
}
