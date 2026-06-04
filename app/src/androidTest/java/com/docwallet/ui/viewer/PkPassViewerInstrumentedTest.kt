package com.docwallet.ui.viewer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.docwallet.DocWalletApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PkPassViewerInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<DocWalletApplication>()
    }

    @Test
    fun rendersOrganizationAndDescription() {
        val file = createComprehensivePkPass()

        composeTestRule.setContent {
            PkPassViewer(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Test Org").assertExists()
        composeTestRule.onNodeWithText("Test Description").assertExists()
    }

    @Test
    fun rendersBarcode() {
        val file = createComprehensivePkPass()

        composeTestRule.setContent {
            PkPassViewer(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Barcode (PKBarcodeFormatQR)").assertExists()
        composeTestRule.onNodeWithText("QR-message").assertExists()
    }

    @Test
    fun rendersFieldLabelsAndValues() {
        val file = createComprehensivePkPass()

        composeTestRule.setContent {
            PkPassViewer(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Header Field").assertExists()
        composeTestRule.onNodeWithText("Header Value 1").assertExists()
        composeTestRule.onNodeWithText("Primary Field").assertExists()
        composeTestRule.onNodeWithText("Primary Value 1").assertExists()
        composeTestRule.onNodeWithText("Secondary Field").assertExists()
        composeTestRule.onNodeWithText("Secondary Value 1").assertExists()
        composeTestRule.onNodeWithText("Auxiliary Field").assertExists()
        composeTestRule.onNodeWithText("Auxiliary Value 1").assertExists()
    }

    @Test
    fun rendersBackFields() {
        val file = createComprehensivePkPass()

        composeTestRule.setContent {
            PkPassViewer(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Additional Information").assertExists()
        composeTestRule.onNodeWithText("Back Key 1").assertExists()
        composeTestRule.onNodeWithText("Back Value 1").assertExists()
    }

    @Test
    fun rendersBarcodesArray() {
        val file = createComprehensivePkPass()

        composeTestRule.setContent {
            PkPassViewer(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Barcode (PKBarcodeFormatPDF417)").assertExists()
        composeTestRule.onNodeWithText("PDF417-message").assertExists()
    }

    @Test
    fun rendersDates() {
        val file = createComprehensivePkPass()

        composeTestRule.setContent {
            PkPassViewer(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Relevant date: 2026-01-01").assertExists()
        composeTestRule.onNodeWithText("Expires: 2027-01-01").assertExists()
    }

    @Test
    fun doesNotShowErrorText() {
        val file = createComprehensivePkPass()

        composeTestRule.setContent {
            PkPassViewer(file = file)
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Unable to parse pass").assertDoesNotExist()
    }

    private fun createComprehensivePkPass(): File {
        val file = File(context.cacheDir, "inst_test_${System.nanoTime()}.pkpass")

        val passJson = org.json.JSONObject().apply {
            put("formatVersion", 1)
            put("passTypeIdentifier", "pass.com.docwallet.test")
            put("serialNumber", "TEST001")
            put("teamIdentifier", "TEAMTEST")
            put("organizationName", "Test Org")
            put("description", "Test Description")
            put("foregroundColor", "rgb(0, 0, 0)")
            put("backgroundColor", "rgb(255, 255, 255)")
            put("labelColor", "rgb(100, 100, 100)")
            put("relevantDate", "2026-01-01")
            put("expirationDate", "2027-01-01")

            put("headerFields", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("key", "header1")
                    put("label", "Header Field")
                    put("value", "Header Value 1")
                })
            })

            put("primaryFields", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("key", "primary1")
                    put("label", "Primary Field")
                    put("value", "Primary Value 1")
                })
            })

            put("secondaryFields", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("key", "secondary1")
                    put("label", "Secondary Field")
                    put("value", "Secondary Value 1")
                })
            })

            put("auxiliaryFields", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("key", "aux1")
                    put("label", "Auxiliary Field")
                    put("value", "Auxiliary Value 1")
                })
            })

            put("backFields", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("key", "Back Key 1")
                    put("label", "")
                    put("value", "Back Value 1")
                })
            })

            put("barcode", org.json.JSONObject().apply {
                put("format", "PKBarcodeFormatQR")
                put("message", "QR-message")
                put("messageEncoding", "iso-8859-1")
            })

            put("barcodes", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("format", "PKBarcodeFormatPDF417")
                    put("message", "PDF417-message")
                    put("altText", "PDF417 alt text")
                })
            })
        }

        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("pass.json"))
            zos.write(passJson.toString(2).toByteArray())
            zos.closeEntry()
        }

        return file
    }
}
