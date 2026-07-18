package com.librecrate.app.data.model

import com.librecrate.app.vault.model.DocumentType
import org.junit.Assert.*
import org.junit.Test

class DocumentTypeTest {

    @Test
    fun `fromMimeType matches PDF`() {
        assertEquals(DocumentType.PDF, DocumentType.fromMimeType("application/pdf"))
    }

    @Test
    fun `fromMimeType matches EPUB`() {
        assertEquals(DocumentType.EPUB, DocumentType.fromMimeType("application/epub+zip"))
    }

    @Test
    fun `fromMimeType matches PKPASS`() {
        assertEquals(DocumentType.PKPASS, DocumentType.fromMimeType("application/vnd.apple.pkpass"))
    }

    @Test
    fun `fromMimeType matches CBZ`() {
        assertEquals(DocumentType.CBZ, DocumentType.fromMimeType("application/vnd.comicbook+zip"))
    }

    @Test
    fun `fromMimeType matches CBR`() {
        assertEquals(DocumentType.CBR, DocumentType.fromMimeType("application/x-cbr"))
    }

    @Test
    fun `fromMimeType matches IMAGE for image-png`() {
        assertEquals(DocumentType.IMAGE, DocumentType.fromMimeType("image/png"))
    }

    @Test
    fun `fromMimeType matches IMAGE for image-jpeg`() {
        assertEquals(DocumentType.IMAGE, DocumentType.fromMimeType("image/jpeg"))
    }

    @Test
    fun `fromMimeType matches NOTE`() {
        assertEquals(DocumentType.NOTE, DocumentType.fromMimeType("text/markdown"))
    }

    @Test
    fun `fromMimeType returns UNKNOWN for unknown`() {
        assertEquals(DocumentType.UNKNOWN, DocumentType.fromMimeType("application/unknown"))
    }

    @Test
    fun `fromMimeType returns UNKNOWN for empty string`() {
        assertEquals(DocumentType.UNKNOWN, DocumentType.fromMimeType(""))
    }

    @Test
    fun `fromExtension matches pdf`() {
        assertEquals(DocumentType.PDF, DocumentType.fromExtension("pdf"))
    }

    @Test
    fun `fromExtension matches PDF uppercase`() {
        assertEquals(DocumentType.PDF, DocumentType.fromExtension("PDF"))
    }

    @Test
    fun `fromExtension matches jpg`() {
        assertEquals(DocumentType.IMAGE, DocumentType.fromExtension("jpg"))
    }

    @Test
    fun `fromExtension matches md`() {
        assertEquals(DocumentType.NOTE, DocumentType.fromExtension("md"))
    }

    @Test
    fun `fromExtension returns UNKNOWN for unknown`() {
        assertEquals(DocumentType.UNKNOWN, DocumentType.fromExtension("xyz"))
    }

    @Test
    fun `fromExtension returns UNKNOWN for empty string`() {
        assertEquals(DocumentType.UNKNOWN, DocumentType.fromExtension(""))
    }

    @Test
    fun `each DocumentType has correct mimeType`() {
        assertEquals("application/pdf", DocumentType.PDF.mimeType)
        assertEquals("application/epub+zip", DocumentType.EPUB.mimeType)
        assertEquals("application/vnd.apple.pkpass", DocumentType.PKPASS.mimeType)
        assertEquals("application/vnd.comicbook+zip", DocumentType.CBZ.mimeType)
        assertEquals("application/x-cbr", DocumentType.CBR.mimeType)
        assertEquals("image/*", DocumentType.IMAGE.mimeType)
        assertEquals("text/markdown", DocumentType.NOTE.mimeType)
        assertEquals("application/octet-stream", DocumentType.UNKNOWN.mimeType)
    }

    @Test
    fun `IMAGE extensions list contains all expected formats`() {
        val expected = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        assertEquals(expected, DocumentType.IMAGE.extensions)
    }
}
