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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
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
        private const val TAG = "EpubReaderActivity"

        fun start(context: Context, filePath: String) {
            val intent = Intent(context, EpubReaderActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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

        val containerId = View.generateViewId()
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

            val navigatorFactory = EpubNavigatorFactory(publication)
            supportFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(initialLocator = null)

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
