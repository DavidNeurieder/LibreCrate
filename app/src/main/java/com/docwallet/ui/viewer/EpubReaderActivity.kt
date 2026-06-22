package com.docwallet.ui.viewer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import com.docwallet.DocWalletApplication
import com.docwallet.data.FontFamilyName
import com.docwallet.data.ReaderPreferences
import com.docwallet.data.ReaderPreferencesStore
import com.docwallet.data.SessionStore
import com.docwallet.data.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    @JvmField
    internal var containerId: Int = View.generateViewId()
    private var documentId: String? = null
    private var publication: Publication? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        documentId = intent?.getStringExtra(EXTRA_DOCUMENT_ID)
        documentId?.let { SessionStore.saveLastDocumentId(this, it) }
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

        val doc = try {
            runBlocking(Dispatchers.IO) {
                documentId?.let { id ->
                    (application as DocWalletApplication).documentDao.getDocumentById(id)
                }
            }
        } catch (_: Exception) {
            null
        }

        try {
            val pub = runBlocking(Dispatchers.IO) {
                openPublication(file)
            }
            publication = pub

            val initialLocator = loadLocator()

            val readerPrefs = ReaderPreferencesStore.load(this)
            val initialPreferences = EpubPreferences(
                fontSize = readerPrefs.fontSize.toDouble(),
                fontFamily = when (readerPrefs.fontFamilyName) {
                    FontFamilyName.SANS_SERIF.name -> FontFamily.SANS_SERIF
                    FontFamilyName.OPEN_DYSLEXIC.name -> FontFamily.OPEN_DYSLEXIC
                    else -> FontFamily.SERIF
                },
                lineHeight = readerPrefs.lineHeight.toDouble(),
                pageMargins = readerPrefs.pageMargins.toDouble(),
                publisherStyles = false,
            )

            val navigatorFactory = EpubNavigatorFactory(pub)
            supportFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = initialPreferences,
                )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open EPUB", e)
            Toast.makeText(
                this,
                "Failed to open EPUB: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EpubReaderScreen(
                        document = doc,
                        containerId = containerId,
                        publication = publication,
                        onBack = { finish() },
                        onToggleFavorite = { toggleFavorite() },
                    )
                }
            }
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

    private fun toggleFavorite() {
        val docId = documentId ?: return
        runBlocking(Dispatchers.IO) {
            val app = application as DocWalletApplication
            val doc = app.documentDao.getDocumentById(docId) ?: return@runBlocking
            app.documentDao.update(doc.copy(isFavorite = !doc.isFavorite))
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
@Composable
private fun EpubReaderScreen(
    document: Document?,
    containerId: Int,
    publication: Publication?,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var isFullscreen by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var isFavorite by remember { mutableStateOf(document?.isFavorite ?: false) }

    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val window = (activity ?: return).window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

    fun navigateForward() {
        (activity?.supportFragmentManager?.findFragmentById(containerId) as? EpubNavigatorFragment)?.goForward(true)
    }

    fun navigateBackward() {
        (activity?.supportFragmentManager?.findFragmentById(containerId) as? EpubNavigatorFragment)?.goBackward(true)
    }

    if (showInfoDialog && document != null) {
        val doc = document
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Document Info") },
            text = {
                Column {
                    InfoRow("Title", doc.title)
                    InfoRow("Type", doc.mimeType)
                    InfoRow("Size", formatFileSize(doc.fileSize))
                    InfoRow("Pages", doc.pageCount.toString())
                    InfoRow("Author", doc.author.ifEmpty { "\u2014" })
                    InfoRow("Imported", dateFormat.format(Date(doc.importedAt)))
                    if (doc.lastOpenedAt > 0) {
                        InfoRow("Last opened", dateFormat.format(Date(doc.lastOpenedAt)))
                    }
                    doc.description.ifEmpty { null }?.let {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("Description", style = MaterialTheme.typography.labelMedium)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showSettingsDialog) {
        val act = activity
        if (act != null) {
            ReaderSettingsDialog(
                activity = act,
                containerId = containerId,
                onDismiss = { showSettingsDialog = false },
            )
        }
    }

    if (showDeleteDialog && document != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete document") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    val doc = document
                    val app = (activity?.application as? DocWalletApplication) ?: return@TextButton
                    scope.launch(Dispatchers.IO) {
                        File(doc.filePath).delete()
                        doc.thumbnailPath?.let { File(it).delete() }
                        app.documentDao.deleteById(doc.id)
                        withContext(Dispatchers.Main) {
                            activity?.finish()
                        }
                    }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showTocSheet && publication != null) {
        val fragment = activity?.supportFragmentManager?.findFragmentById(containerId) as? EpubNavigatorFragment
        if (fragment != null) {
            ChapterTocSheet(
                publication = publication,
                navigatorFragment = fragment,
                onDismiss = { showTocSheet = false },
            )
        }
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        if (document != null) {
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete document",
                                )
                            }
                            IconButton(onClick = {
                                isFavorite = !isFavorite
                                onToggleFavorite()
                            }) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { showTocSheet = true }) {
                                Icon(
                                    imageVector = Icons.Filled.List,
                                    contentDescription = "Table of Contents",
                                )
                            }
                            IconButton(onClick = { showInfoDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = "More options",
                                )
                            }
                            IconButton(onClick = { showSettingsDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Reader settings",
                                )
                            }
                            IconButton(onClick = { toggleFullscreen() }) {
                                Icon(
                                    imageVector = Icons.Filled.Fullscreen,
                                    contentDescription = "Enter fullscreen",
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val marginPx = with(context.resources.displayMetrics) {
                            (ReaderPreferencesStore.load(context).topBottomMargin * density).toInt()
                        }
                        FrameLayout(context).apply {
                            setBackgroundColor(android.graphics.Color.WHITE)
                            addView(
                                FragmentContainerView(context).apply {
                                    id = containerId
                                    setPadding(0, marginPx, 0, marginPx)
                                },
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )

                            post {
                                val fm = (context as FragmentActivity).supportFragmentManager
                                if (fm.findFragmentById(containerId) == null) {
                                    fm.commit {
                                        add(containerId, EpubNavigatorFragment::class.java, null)
                                    }
                                }
                                fm.executePendingTransactions()
                                (fm.findFragmentById(containerId) as? EpubNavigatorFragment)
                                    ?.addInputListener(object : InputListener {
                                        override fun onTap(event: TapEvent): Boolean {
                                            val sw = context.resources.displayMetrics.widthPixels
                                            if (event.point.x < sw / 3f) {
                                                navigateBackward()
                                            } else {
                                                navigateForward()
                                            }
                                            return true
                                        }

                                        override fun onDrag(event: DragEvent): Boolean = false

                                        override fun onKey(event: org.readium.r2.navigator.input.KeyEvent): Boolean = false
                                    })
                            }
                        }
                    },
                )

                if (isFullscreen) {
                    IconButton(
                        onClick = { toggleFullscreen() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FullscreenExit,
                            contentDescription = "Exit fullscreen",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterTocSheet(
    publication: Publication,
    navigatorFragment: EpubNavigatorFragment,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tocLinks = remember { flattenToc(publication.tableOfContents) }
    val listState = rememberLazyListState()

    val currentLocator by navigatorFragment.currentLocator.collectAsState()
    val activeIndex = remember(currentLocator, tocLinks) {
        findActiveTocIndex(currentLocator, tocLinks)
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = "Table of Contents",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        if (tocLinks.isEmpty()) {
            Text(
                text = "No table of contents available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                itemsIndexed(tocLinks, key = { _, link -> link.first }) { index, (link, depth) ->
                    TocItem(
                        link = link,
                        depth = depth,
                        isActive = index == activeIndex,
                        onClick = {
                            navigatorFragment.go(link, true)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TocItem(
    link: Link,
    depth: Int,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val title = (link.title ?: "").ifBlank { link.href.toString() }
    val textColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = (24 + depth * 16).dp,
                end = 24.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isActive) "► " else "  ",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = textColor,
        )
    }
}

internal fun flattenToc(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> {
    val result = mutableListOf<Pair<Link, Int>>()
    for (link in links) {
        result.add(link to depth)
        if (link.children.isNotEmpty()) {
            result.addAll(flattenToc(link.children, depth + 1))
        }
    }
    return result
}

internal fun findActiveTocIndex(currentLocator: Locator?, tocLinks: List<Pair<Link, Int>>): Int {
    val locator = currentLocator ?: return -1
    val locatorHref = locator.href.toString()
    var bestIndex = -1
    var bestLength = 0
    for (i in tocLinks.indices) {
        val linkHref = tocLinks[i].first.href.toString()
        if (locatorHref.startsWith(linkHref) && linkHref.length > bestLength) {
            bestIndex = i
            bestLength = linkHref.length
        }
    }
    return bestIndex
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsDialog(
    activity: FragmentActivity,
    containerId: Int,
    onDismiss: () -> Unit,
) {
    val context = activity
    val prefs = remember { ReaderPreferencesStore.load(context) }
    var fontSize by remember { mutableStateOf(prefs.fontSize) }
    var fontFamilyName by remember { mutableStateOf(prefs.fontFamilyName) }
    var lineHeight by remember { mutableStateOf(prefs.lineHeight) }
    var pageMargins by remember { mutableStateOf(prefs.pageMargins) }
    var topBottomMargin by remember { mutableStateOf(prefs.topBottomMargin) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reader Settings") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Font Size", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${String.format(Locale.ROOT, "%.1f", fontSize)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 0.75f..2.0f,
                    steps = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                Text("Font Family", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                FontFamilyDropdown(
                    selected = FontFamilyName.entries.firstOrNull { it.name == fontFamilyName }
                        ?: FontFamilyName.SERIF,
                    onSelected = { selected ->
                        fontFamilyName = selected.name
                    },
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Line spacing", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${String.format(Locale.ROOT, "%.1f", lineHeight)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = lineHeight,
                    onValueChange = { lineHeight = it },
                    valueRange = 1.0f..2.5f,
                    steps = 5,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Page margins", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${String.format(Locale.ROOT, "%.1f", pageMargins)}x",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = pageMargins,
                    onValueChange = { pageMargins = it },
                    valueRange = 0.0f..3.0f,
                    steps = 5,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Top/bottom margin", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${String.format(Locale.ROOT, "%.0f", topBottomMargin)} dp",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = topBottomMargin,
                    onValueChange = { topBottomMargin = it },
                    valueRange = 0f..50f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val prefsToSave = ReaderPreferences(
                    fontSize = fontSize,
                    fontFamilyName = fontFamilyName,
                    lineHeight = lineHeight,
                    pageMargins = pageMargins,
                    topBottomMargin = topBottomMargin,
                )
                ReaderPreferencesStore.save(context, prefsToSave)
                val fragment = context.supportFragmentManager.findFragmentById(containerId) as? EpubNavigatorFragment
                if (fragment != null) {
                    fragment.submitPreferences(EpubPreferences(
                        fontSize = fontSize.toDouble(),
                        fontFamily = when (fontFamilyName) {
                            FontFamilyName.SANS_SERIF.name -> FontFamily.SANS_SERIF
                            FontFamilyName.OPEN_DYSLEXIC.name -> FontFamily.OPEN_DYSLEXIC
                            else -> FontFamily.SERIF
                        },
                        lineHeight = lineHeight.toDouble(),
                        pageMargins = pageMargins.toDouble(),
                        publisherStyles = false,
                    ))
                }
                val density = context.resources.displayMetrics.density
                val marginPx = (topBottomMargin * density).toInt()
                context.findViewById<androidx.fragment.app.FragmentContainerView>(containerId)
                    ?.setPadding(0, marginPx, 0, marginPx)
                onDismiss()
            }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontFamilyDropdown(
    selected: FontFamilyName,
    onSelected: (FontFamilyName) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            FontFamilyName.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
