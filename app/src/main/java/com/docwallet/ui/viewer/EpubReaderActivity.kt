package com.docwallet.ui.viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import com.docwallet.DocWalletApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

class EpubReaderActivity : FragmentActivity() {

    companion object {
        private const val EXTRA_FILE_PATH = "epub_file_path"
        private const val EXTRA_DOCUMENT_ID = "document_id"
        private const val TAG = "EpubReaderActivity"

        fun start(context: Context, filePath: String, documentId: String) {
            val intent = Intent(context, EpubReaderActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_DOCUMENT_ID, documentId)
            }
            context.startActivity(intent)
        }
    }

    private var containerId: Int = View.generateViewId()
    private var documentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        documentId = intent?.getStringExtra(EXTRA_DOCUMENT_ID)
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: run {
            finish()
            return
        }
        val file = File(filePath).takeIf { it.exists() } ?: run {
            finish()
            return
        }

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        containerId = View.generateViewId()
        val container = FragmentContainerView(this).apply {
            id = containerId
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(container)

        try {
            val publication = runBlocking(Dispatchers.IO) {
                openPublication(file)
            }

            val initialLocator = loadLocator()
            val navigatorFactory = EpubNavigatorFactory(publication)
            supportFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(initialLocator = initialLocator)

            if (savedInstanceState == null) {
                supportFragmentManager.commit {
                    setReorderingAllowed(true)
                    add(containerId, EpubNavigatorFragment::class.java, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open EPUB", e)
            Toast.makeText(
                this,
                "Failed to open EPUB: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentLocator()
    }

    private fun loadLocator(): Locator? {
        val docId = documentId ?: return null
        return runBlocking(Dispatchers.IO) {
            val app = application as DocWalletApplication
            val doc = app.documentDao.getDocumentById(docId) ?: return@runBlocking null
            val json = doc.readingPosition ?: return@runBlocking null
            try {
                Locator.fromJSON(JSONObject(json))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun saveCurrentLocator() {
        val docId = documentId ?: return
        val fragment = supportFragmentManager.findFragmentById(containerId) as? EpubNavigatorFragment
            ?: return
        val locator = fragment.currentLocator.value
        runBlocking(Dispatchers.IO) {
            val app = application as DocWalletApplication
            val doc = app.documentDao.getDocumentById(docId) ?: return@runBlocking
            app.documentDao.update(
                doc.copy(
                    readingPosition = locator.toJSON().toString(),
                    lastOpenedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun openPublication(file: File): Publication {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(contentResolver, httpClient)
        val parser = DefaultPublicationParser(
            this,
            httpClient,
            assetRetriever,
            pdfFactory = null
        )
        val opener = PublicationOpener(parser)

        val asset = when (val result = assetRetriever.retrieve(file)) {
            is Try.Success -> result.value
            is Try.Failure -> throw RuntimeException(
                "Asset retrieval failed: ${result.value.message}"
            )
        }

        return when (val result = opener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> result.value
            is Try.Failure -> throw RuntimeException(
                "Publication open failed: ${result.value.message}"
            )
        }
    }
}
