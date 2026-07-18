package com.librecrate.app.vault.reader

import com.librecrate.app.vault.reader.models.DocumentMetadata
import com.librecrate.app.vault.reader.models.ReaderLocation
import java.io.Closeable

data class RenderConfig(
    val width: Int,
    val height: Int,
    val nightMode: Boolean = false,
)

data class RenderedPage(
    val width: Int,
    val height: Int,
    val pixelData: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenderedPage) return false
        return width == other.width && height == other.height && pixelData.contentEquals(other.pixelData)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixelData.contentHashCode()
        return result
    }
}

interface DocumentReader : Closeable {
    val metadata: DocumentMetadata
    val pageCount: Int
    fun currentLocation(): ReaderLocation
    suspend fun renderPage(pageIndex: Int, config: RenderConfig): RenderedPage
    fun extractText(): String?
}
