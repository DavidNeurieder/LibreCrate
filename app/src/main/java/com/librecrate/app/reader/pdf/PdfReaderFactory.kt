package com.librecrate.app.reader.pdf

import com.librecrate.app.vault.reader.DocumentProcessor
import com.librecrate.app.vault.reader.DocumentReader
import com.librecrate.app.vault.reader.ReaderDocument
import com.librecrate.app.vault.reader.ReaderFactory

class PdfReaderFactory : ReaderFactory {

    override fun createReader(document: ReaderDocument): DocumentReader? {
        return if (isPdf(document.filePath)) {
            PdfDocumentReader(document.filePath)
        } else null
    }

    override fun getProcessor(mimeType: String): DocumentProcessor? {
        return if (mimeType == "application/pdf" || mimeType.startsWith("application/pdf")) {
            PdfDocumentProcessor()
        } else null
    }

    private fun isPdf(filePath: String): Boolean {
        return filePath.endsWith(".pdf", ignoreCase = true)
    }
}
