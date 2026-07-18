package com.librecrate.app.ui.export

import android.net.Uri
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.db.DocumentDao
import com.librecrate.app.data.encryption.EncryptionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportViewModelTest {

    private val mockDao = mockk<DocumentDao>(relaxed = true)
    private val mockEncryptionManager = mockk<EncryptionManager>(relaxed = true)
    private val mockApp = mockk<LibreCrateApplication>(relaxed = true)
    private lateinit var viewModel: ExportViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { mockApp.documentDao } returns mockDao
        every { mockApp.encryptionManager } returns mockEncryptionManager
        coEvery { mockDao.getAllDocumentsOnce() } returns emptyList()
        viewModel = ExportViewModel(mockApp)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `searchQuery starts empty`() {
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `isExporting starts false`() {
        assertFalse(viewModel.isExporting.value)
    }

    @Test
    fun `exportProgress starts null`() {
        assertNull(viewModel.exportProgress.value)
    }

    @Test
    fun `message starts null`() {
        assertNull(viewModel.message.value)
    }

    @Test
    fun `filteredDocuments starts empty`() {
        assertTrue(viewModel.filteredDocuments.value.isEmpty())
    }

    @Test
    fun `selectedDocIds starts empty`() {
        assertTrue(viewModel.selectedDocIds.value.isEmpty())
    }

    @Test
    fun `search updates searchQuery`() {
        viewModel.search("report")
        assertEquals("report", viewModel.searchQuery.value)
    }

    @Test
    fun `toggleDocumentSelection adds id`() {
        viewModel.toggleDocumentSelection("1")
        assertTrue(viewModel.selectedDocIds.value.contains("1"))
        assertEquals(1, viewModel.selectedDocIds.value.size)
    }

    @Test
    fun `toggleDocumentSelection toggles id off`() {
        viewModel.toggleDocumentSelection("1")
        viewModel.toggleDocumentSelection("1")
        assertFalse(viewModel.selectedDocIds.value.contains("1"))
        assertTrue(viewModel.selectedDocIds.value.isEmpty())
    }

    @Test
    fun `toggleDocumentSelection handles multiple ids`() {
        viewModel.toggleDocumentSelection("1")
        viewModel.toggleDocumentSelection("2")
        assertEquals(2, viewModel.selectedDocIds.value.size)
        viewModel.toggleDocumentSelection("1")
        assertEquals(1, viewModel.selectedDocIds.value.size)
        assertTrue(viewModel.selectedDocIds.value.contains("2"))
    }

    @Test
    fun `selectAll with empty filtered list selects nothing`() {
        viewModel.selectAll()
        assertTrue(viewModel.selectedDocIds.value.isEmpty())
    }

    @Test
    fun `deselectAll clears selection`() {
        viewModel.toggleDocumentSelection("1")
        viewModel.toggleDocumentSelection("2")
        assertFalse(viewModel.selectedDocIds.value.isEmpty())
        viewModel.deselectAll()
        assertTrue(viewModel.selectedDocIds.value.isEmpty())
    }

    @Test
    fun `onExportDocumentsConfirmed with empty selection sets error`() {
        viewModel.onExportDocumentsConfirmed(mockk<Uri>())
        assertEquals("No documents selected", viewModel.message.value)
    }

    @Test
    fun `onExportDocumentsConfirmed with empty selection does not call encryption`() {
        viewModel.onExportDocumentsConfirmed(mockk<Uri>())
        verify(exactly = 0) { mockEncryptionManager.getMasterKeyForSession() }
    }

    @Test
    fun `clearMessage resets message`() {
        viewModel.onExportDocumentsConfirmed(mockk<Uri>())
        assertNotNull(viewModel.message.value)
        viewModel.clearMessage()
        assertNull(viewModel.message.value)
    }

    @Test
    fun `isExporting set synchronously when export starts with selection`() {
        viewModel.toggleDocumentSelection("1")
        every { mockEncryptionManager.getMasterKeyForSession() } returns byteArrayOf(1, 2, 3)
        every { mockApp.cacheDir } returns java.io.File(System.getProperty("java.io.tmpdir"))
        every { mockApp.contentResolver } returns mockk(relaxed = true)

        viewModel.onExportDocumentsConfirmed(mockk<Uri>(relaxed = true))
        assertTrue(viewModel.isExporting.value)
    }
}
