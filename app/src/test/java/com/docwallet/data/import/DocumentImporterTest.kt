package com.docwallet.data.import

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Color
import androidx.room.Room
import com.docwallet.data.db.DocWalletDatabase
import com.docwallet.data.db.DocumentDao
import com.docwallet.data.encryption.Argon2Hasher
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.data.encryption.FileEncryptor
import com.docwallet.data.model.Document
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentImporterTest {

    private lateinit var context: android.content.Context
    private lateinit var dao: DocumentDao
    private lateinit var db: DocWalletDatabase
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var mockHasher: Argon2Hasher
    private lateinit var fileEncryptor: FileEncryptor
    private lateinit var importer: DocumentImporter

    @Before
    fun setUp() {
        mockHasher = mockk()
        every { mockHasher.hash(
            password = any(),
            salt = any(),
            tCostInIterations = any(),
            mCostInKibibyte = any(),
            parallelism = any(),
            hashLengthInBytes = any(),
        ) } answers {
            val password = arg<ByteArray>(0)
            val salt = arg<ByteArray>(1)
            val hashLen = arg<Int>(5)
            ByteArray(hashLen) { i ->
                (password.getOrElse(i % password.size) { 0 } +
                 salt.getOrElse(i % salt.size) { 0 } + i).toByte()
            }
        }

        context = RuntimeEnvironment.getApplication().applicationContext

        PDFBoxResourceLoader.init(context)

        encryptionManager = EncryptionManager(context, mockHasher)
        encryptionManager.initializeDeviceKeyMode()

        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DocWalletDatabase::class.java,
        ).build()
        dao = db.documentDao()

        fileEncryptor = FileEncryptor()
        importer = DocumentImporter(context, dao, fileEncryptor, encryptionManager)
    }

    @After
    fun tearDown() {
        db.close()
        unmockkAll()
    }

    @Test
    fun `import PDF document`() = runTest {
        val pdfFile = createPdf(
            title = "Test PDF Document",
            author = "Test Author",
            body = "Hello from the PDF test document!",
        )
        val uri = android.net.Uri.fromFile(pdfFile)

        val document = importer.importDocument(uri, "application/pdf")

        assertNotNull("PDF import should return a document", document)
        val doc = document!!
        assertEquals("application/pdf", doc.mimeType)
        assertTrue("File should be encrypted (.enc)", doc.filePath.endsWith(".enc"))
        assertNotNull("Encryption IV must be present", doc.encryptionIv)
        assertEquals(12, doc.encryptionIv!!.size)
        assertTrue("File should exist on disk", File(doc.filePath).exists())
        assertTrue("File size should be > 0", doc.fileSize > 0)

        val fromDb = dao.getDocumentById(doc.id)
        assertNotNull("Document should be in database", fromDb)
        assertEquals("application/pdf", fromDb!!.mimeType)
    }

    @Test
    fun `import EPUB document`() = runTest {
        val epubFile = createEpub(
            title = "Test EPUB",
            author = "Test Author",
            body = "<p>Hello from the EPUB!</p>",
        )
        val uri = android.net.Uri.fromFile(epubFile)

        val document = importer.importDocument(uri, "application/epub+zip")

        assertNotNull("EPUB import should return a document", document)
        val doc = document!!
        assertEquals("application/epub+zip", doc.mimeType)
        assertTrue(doc.filePath.endsWith(".enc"))
        assertNotNull(doc.encryptionIv)

        val fromDb = dao.getDocumentById(doc.id)
        assertNotNull(fromDb)
        assertEquals("application/epub+zip", fromDb!!.mimeType)
    }

    @Test
    fun `import PKPass document`() = runTest {
        val pkpassFile = createPkPass(
            organizationName = "Test Corp",
            description = "A test pass",
        )
        val uri = android.net.Uri.fromFile(pkpassFile)

        val document = importer.importDocument(uri, "application/vnd.apple.pkpass")

        assertNotNull("PKPass import should return a document", document)
        val doc = document!!
        assertEquals("application/vnd.apple.pkpass", doc.mimeType)
        assertTrue(doc.filePath.endsWith(".enc"))
        assertNotNull(doc.encryptionIv)

        val fromDb = dao.getDocumentById(doc.id)
        assertNotNull(fromDb)
        assertEquals("application/vnd.apple.pkpass", fromDb!!.mimeType)
    }

    @Test
    fun `import CBZ document`() = runTest {
        val cbzFile = createCbz()
        val uri = android.net.Uri.fromFile(cbzFile)

        val document = importer.importDocument(uri, "application/vnd.comicbook+zip")

        assertNotNull("CBZ import should return a document", document)
        val doc = document!!
        assertEquals("application/vnd.comicbook+zip", doc.mimeType)
        assertTrue(doc.filePath.endsWith(".enc"))
        assertNotNull(doc.encryptionIv)

        val fromDb = dao.getDocumentById(doc.id)
        assertNotNull(fromDb)
        assertEquals("application/vnd.comicbook+zip", fromDb!!.mimeType)
    }

    @Test
    fun `import IMAGE document`() = runTest {
        val imageFile = createPngImage()
        val uri = android.net.Uri.fromFile(imageFile)

        val document = importer.importDocument(uri, "image/png")

        assertNotNull("Image import should return a document", document)
        val doc = document!!
        assertTrue("MIME should start with image/", doc.mimeType.startsWith("image/"))
        assertTrue(doc.filePath.endsWith(".enc"))
        assertNotNull(doc.encryptionIv)

        val fromDb = dao.getDocumentById(doc.id)
        assertNotNull(fromDb)
        assertTrue(fromDb!!.mimeType.startsWith("image/"))
    }

    @Test
    fun `import NOTE via importNote`() = runTest {
        val document = importer.importNote("My Test Note", "This is the note content!")

        assertNotNull("Note import should return a document", document)
        val doc = document!!
        assertEquals("text/markdown", doc.mimeType)
        assertTrue(doc.filePath.endsWith(".enc"))
        assertNotNull(doc.encryptionIv)
        assertEquals("My Test Note", doc.title)

        val fromDb = dao.getDocumentById(doc.id)
        assertNotNull(fromDb)
        assertEquals("My Test Note", fromDb!!.title)
        assertEquals("text/markdown", fromDb.mimeType)
    }

    @Test
    fun `encrypted file can be decrypted after import`() = runTest {
        val pdfFile = createPdf(
            title = "Round Trip",
            author = "Test",
            body = "Verify encrypt/decrypt round trip works.",
        )
        val uri = android.net.Uri.fromFile(pdfFile)

        val document = importer.importDocument(uri, "application/pdf")
        assertNotNull(document)
        val doc = document!!

        val masterKey = encryptionManager.getMasterKeyForSession()
        assertNotNull(masterKey)

        val decryptedFile = File(context.cacheDir, "roundtrip_test.pdf")
        fileEncryptor.decrypt(
            input = File(doc.filePath),
            output = decryptedFile,
            key = masterKey!!,
            iv = doc.encryptionIv!!,
        )

        assertTrue("Decrypted file should exist", decryptedFile.exists())
        assertTrue("Decrypted file should have content", decryptedFile.length() > 0)

        val decryptedPdf = PDDocument.load(decryptedFile)
        assertEquals("PDF should have 1 page", 1, decryptedPdf.numberOfPages)
        val extractedTitle = decryptedPdf.documentInformation.title
        assertEquals("Round Trip", extractedTitle)
        decryptedPdf.close()
    }

    @Test
    fun `decrypted PDF retains full body text after import and decrypt`() = runTest {
        val bodyText = "This is the body text that should survive encryption and decryption."
        val pdfFile = createPdf(
            title = "Body Text Test",
            author = "Test",
            body = bodyText,
        )
        val uri = android.net.Uri.fromFile(pdfFile)

        val document = importer.importDocument(uri, "application/pdf")
        assertNotNull(document)
        val doc = document!!

        val masterKey = encryptionManager.getMasterKeyForSession()
        assertNotNull(masterKey)

        val decryptedFile = File(context.cacheDir, "bodytext_test.pdf")
        fileEncryptor.decrypt(
            input = File(doc.filePath),
            output = decryptedFile,
            key = masterKey!!,
            iv = doc.encryptionIv!!,
        )

        assertTrue("Decrypted file should exist", decryptedFile.exists())
        assertTrue("Decrypted file should have content", decryptedFile.length() > 0)

        PDDocument.load(decryptedFile).use { pdf ->
            val stripper = PDFTextStripper()
            val extractedText = stripper.getText(pdf)
            assertTrue(
                "Decrypted PDF body text should contain the original body text",
                extractedText.contains(bodyText),
            )
        }
    }

    @Test
    fun `importing same file twice creates separate documents`() = runTest {
        val imageFile = createPngImage()
        val uri = android.net.Uri.fromFile(imageFile)

        val doc1 = importer.importDocument(uri, "image/png")
        val doc2 = importer.importDocument(uri, "image/png")

        assertNotNull(doc1)
        assertNotNull(doc2)
        assertTrue("IDs should differ", doc1!!.id != doc2!!.id)
        assertTrue("File paths should differ", doc1.filePath != doc2.filePath)

        val all = dao.getAllDocuments().first()
        assertEquals(2, all.size)
    }

    private fun createPdf(title: String, author: String, body: String): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            doc.documentInformation.title = title
            doc.documentInformation.author = author

            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(72f, 700f)
                cs.showText(body)
                cs.endText()
            }

            doc.save(file)
        }
        return file
    }

    private fun createEpub(title: String, author: String, body: String): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.epub")

        val opfContent = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
                <metadata>
                    <dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">$title</dc:title>
                    <dc:creator xmlns:dc="http://purl.org/dc/elements/1.1/">$author</dc:creator>
                </metadata>
                <manifest>
                    <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="chapter1"/>
                </spine>
            </package>
        """.trimIndent()

        val xhtmlContent = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>$title</title></head>
            <body>$body</body>
            </html>
        """.trimIndent()

        val containerXml = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
        """.trimIndent()

        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfContent.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/chapter1.xhtml"))
            zos.write(xhtmlContent.toByteArray())
            zos.closeEntry()
        }

        return file
    }

    private fun createPkPass(organizationName: String, description: String): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.pkpass")

        val passJson = JSONObject().apply {
            put("formatVersion", 1)
            put("passTypeIdentifier", "pass.com.docwallet.test")
            put("serialNumber", "123456")
            put("teamIdentifier", "TEAM123456")
            put("organizationName", organizationName)
            put("description", description)
            put("foregroundColor", "rgb(255, 255, 255)")
            put("backgroundColor", "rgb(0, 0, 0)")
            put("labelColor", "rgb(255, 255, 255)")
            put("barcode", JSONObject().apply {
                put("format", "PKBarcodeFormatQR")
                put("message", "Test message")
                put("messageEncoding", "iso-8859-1")
            })
        }

        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("pass.json"))
            zos.write(passJson.toString(2).toByteArray())
            zos.closeEntry()
        }

        return file
    }

    private fun createCbz(): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.cbz")
        val imageBytes = createMinimalPngBytes()

        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("page001.png"))
            zos.write(imageBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("page002.png"))
            zos.write(imageBytes)
            zos.closeEntry()
        }

        return file
    }

    private fun createPngImage(): File {
        val file = File(context.cacheDir, "test_${System.nanoTime()}.png")
        file.writeBytes(createMinimalPngBytes())
        return file
    }

    private fun createMinimalPngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val stream = ByteArrayOutputStream()
        bitmap.compress(CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }
}
