package com.librecrate.app.reader.epub

import com.librecrate.app.vault.reader.DocumentReader
import com.librecrate.app.vault.reader.RenderConfig
import com.librecrate.app.vault.reader.RenderedPage
import com.librecrate.app.vault.reader.models.DocumentMetadata
import com.librecrate.app.vault.reader.models.ReaderLocation
import java.io.File

class EpubDocumentReader(filePath: String) : DocumentReader {

    private val file = File(filePath)
    private val parsed = EpubParser.parse(file)

    override val pageCount: Int get() = parsed.spineItems.size

    override val metadata: DocumentMetadata by lazy {
        DocumentMetadata(
            title = parsed.title.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            author = parsed.author,
            pageCount = pageCount,
        )
    }

    private var currentPageIndex: Int = 0
    private var currentChapterUri: String? = null
    private var currentProgression: Double = 0.0
    private var closed = false

    override fun currentLocation(): ReaderLocation {
        return ReaderLocation(
            pageIndex = currentPageIndex,
            chapterUri = currentChapterUri,
            progression = currentProgression,
        )
    }

    fun updateLocation(pageIndex: Int, chapterUri: String?, progression: Double) {
        currentPageIndex = pageIndex
        currentChapterUri = chapterUri
        currentProgression = progression
    }

    override suspend fun renderPage(pageIndex: Int, config: RenderConfig): RenderedPage {
        throw UnsupportedOperationException("EPUB does not support page-at-a-time rendering")
    }

    override fun extractText(): String? {
        return EpubParser.readSpineContent(file, EpubParser.resolveOpfDir(parsed.opfPath), parsed.spineItems)
    }

    override fun close() {
        closed = true
    }
}
