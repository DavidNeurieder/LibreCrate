package com.librecrate.app.reader.epub

import com.librecrate.app.vault.reader.DocumentProcessor
import com.librecrate.app.vault.reader.DocumentReader
import com.librecrate.app.vault.reader.ReaderDocument
import com.librecrate.app.vault.reader.ReaderFactory

class EpubReaderFactory : ReaderFactory {

    override fun createReader(document: ReaderDocument): DocumentReader? {
        return if (isEpub(document.filePath)) {
            EpubDocumentReader(document.filePath)
        } else null
    }

    override fun getProcessor(mimeType: String): DocumentProcessor? {
        return if (mimeType == "application/epub+zip") {
            EpubDocumentProcessor()
        } else null
    }

    private fun isEpub(filePath: String): Boolean {
        return filePath.endsWith(".epub", ignoreCase = true)
    }
}
