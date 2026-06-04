package com.docwallet.ui.viewer

import com.docwallet.DocWalletApplication
import com.docwallet.data.db.DocumentDao
import com.docwallet.data.encryption.EncryptionManager
import com.docwallet.data.encryption.FileEncryptor
import com.docwallet.data.model.Document
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerViewModelTest {

    private val testScheduler = StandardTestDispatcher().scheduler
    private val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    private val ioDispatcher = StandardTestDispatcher(testScheduler)
    private lateinit var context: android.content.Context
    private lateinit var mockApp: DocWalletApplication
    private lateinit var mockDao: DocumentDao
    private lateinit var mockEncryptionManager: EncryptionManager
    private lateinit var fileEncryptor: FileEncryptor
    private lateinit var viewModel: ViewerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        context = RuntimeEnvironment.getApplication().applicationContext

        mockDao = mockk(relaxed = true)
        mockEncryptionManager = mockk(relaxed = true)
        mockApp = mockk(relaxed = true)
        every { mockApp.documentDao } returns mockDao
        every { mockApp.encryptionManager } returns mockEncryptionManager
        every { mockApp.filesDir } returns context.filesDir
        every { mockApp.cacheDir } returns context.cacheDir

        fileEncryptor = FileEncryptor()

        viewModel = ViewerViewModel(mockApp, ioDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadDocument decrypts PDF successfully`() = runTest(testDispatcher) {
        val masterKey = createTestMasterKey()
        val originalContent = "Test PDF content for decryption".toByteArray()
        val encrypted = createEncryptedFile(originalContent, masterKey)

        val doc = Document(
            id = "test-pdf-id",
            title = "Test PDF",
            fileName = "test.pdf",
            mimeType = "application/pdf",
            filePath = encrypted.file.absolutePath,
            fileSize = originalContent.size.toLong(),
            encryptionIv = encrypted.iv,
            textContent = "Test PDF content for decryption",
        )

        coEvery { mockDao.getDocumentById("test-pdf-id") } returns doc
        every { mockEncryptionManager.getMasterKeyForSession() } returns masterKey

        viewModel.loadDocument("test-pdf-id")
        advanceUntilIdle()

        assertNull("There should be no error", viewModel.error.value)
        assertNotNull("Decrypted file should be set", viewModel.decryptedFile.value)
        assertEquals("Test PDF", viewModel.document.value?.title)
        assertEquals("application/pdf", viewModel.document.value?.mimeType)

        val decryptedFile = viewModel.decryptedFile.value!!
        assertTrue("Decrypted file should exist", decryptedFile.exists())
        assertEquals("Content should match", originalContent.size.toLong(), decryptedFile.length())
    }

    @Test
    fun `loadDocument handles document not found`() = runTest(testDispatcher) {
        coEvery { mockDao.getDocumentById("nonexistent") } returns null

        viewModel.loadDocument("nonexistent")
        advanceUntilIdle()

        assertEquals("Document not found", viewModel.error.value)
        assertNull(viewModel.decryptedFile.value)
        assertNull(viewModel.document.value)
    }

    @Test
    fun `loadDocument shows error when master key is missing`() = runTest(testDispatcher) {
        val doc = Document(
            id = "no-key-id",
            title = "No Key",
            fileName = "test.pdf",
            mimeType = "application/pdf",
            filePath = "/nonexistent/file.enc",
            encryptionIv = ByteArray(12),
        )

        coEvery { mockDao.getDocumentById("no-key-id") } returns doc
        every { mockEncryptionManager.getMasterKeyForSession() } returns null

        viewModel.loadDocument("no-key-id")
        advanceUntilIdle()

        assertNotNull("Error should be set", viewModel.error.value)
        assertNull(viewModel.decryptedFile.value)
    }

    @Test
    fun `loadDocument handles missing encryption IV`() = runTest(testDispatcher) {
        val masterKey = createTestMasterKey()
        val content = "Some content".toByteArray()
        val encrypted = createEncryptedFile(content, masterKey)

        val doc = Document(
            id = "no-iv-id",
            title = "No IV",
            fileName = "test.pdf",
            mimeType = "application/pdf",
            filePath = encrypted.file.absolutePath,
            encryptionIv = null,
        )

        coEvery { mockDao.getDocumentById("no-iv-id") } returns doc
        every { mockEncryptionManager.getMasterKeyForSession() } returns masterKey

        viewModel.loadDocument("no-iv-id")
        advanceUntilIdle()

        assertNotNull("Error should be set for missing IV", viewModel.error.value)
        assertNull(viewModel.decryptedFile.value)
    }

    @Test
    fun `loadDocument creates new note for UUID-pattern document ID`() = runTest(testDispatcher) {
        val noteId = "123e4567-e89b-12d3-a456-426614174000"
        val masterKey = createTestMasterKey()

        coEvery { mockDao.getDocumentById(noteId) } returns null
        every { mockEncryptionManager.getMasterKeyForSession() } returns masterKey

        viewModel.loadDocument(noteId)
        advanceUntilIdle()

        assertEquals("New Note", viewModel.document.value?.title)
        assertEquals("text/markdown", viewModel.document.value?.mimeType)
        assertNotNull("Decrypted file should exist", viewModel.decryptedFile.value)
        assertNull("No error expected", viewModel.error.value)
    }

    @Test
    fun `isLoading state transitions correctly during load`() = runTest(testDispatcher) {
        val masterKey = createTestMasterKey()
        val content = "Loading state test".toByteArray()
        val encrypted = createEncryptedFile(content, masterKey)

        val doc = Document(
            id = "loading-test-id",
            title = "Loading Test",
            fileName = "test.pdf",
            mimeType = "application/pdf",
            filePath = encrypted.file.absolutePath,
            encryptionIv = encrypted.iv,
        )

        coEvery { mockDao.getDocumentById("loading-test-id") } returns doc
        every { mockEncryptionManager.getMasterKeyForSession() } returns masterKey

        assertFalse("Should not be loading yet", viewModel.isLoading.value)

        viewModel.loadDocument("loading-test-id")

        assertTrue("Should be loading after loadDocument", viewModel.isLoading.value)

        advanceUntilIdle()

        assertFalse("Should finish loading", viewModel.isLoading.value)
        assertNull(viewModel.error.value)
    }

    private fun createTestMasterKey(): ByteArray {
        return ByteArray(32) { it.toByte() }
    }

    private data class EncryptedFileResult(val file: File, val iv: ByteArray)

    private fun createEncryptedFile(content: ByteArray, masterKey: ByteArray): EncryptedFileResult {
        val filesDir = File(context.filesDir, "files").also { it.mkdirs() }
        val encryptedFile = File(filesDir, "test_${System.nanoTime()}.enc")
        val (iv, encryptedData) = fileEncryptor.encryptBytes(content, masterKey)
        encryptedFile.writeBytes(iv + encryptedData)
        return EncryptedFileResult(encryptedFile, iv)
    }
}
