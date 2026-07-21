package com.librecrate.app.ui.viewer
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.commit
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.AppPreferencesStore
import com.librecrate.app.data.FontFamilyName
import com.librecrate.app.data.ReaderPreferences
import com.librecrate.app.data.ReaderPreferencesStore
import com.librecrate.app.data.SessionStore
import com.librecrate.app.data.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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


private const val TAG = "EpubReaderActivity"
class EpubReaderActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_DOCUMENT_ID = "document_id"
        private const val EXTRA_TARGET_SECTION = "target_section"
        fun start(context: Context, decryptedFilePath: String, documentId: String, targetSection: Int? = null) {
            val intent = Intent(context, EpubReaderActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, decryptedFilePath)
                putExtra(EXTRA_DOCUMENT_ID, documentId)
                if (targetSection != null) putExtra(EXTRA_TARGET_SECTION, targetSection)
            }
            context.startActivity(intent)
        }
    }
    @JvmField
    internal var containerId: Int = View.generateViewId()
    private var documentId: String? = null
    private var targetSection: Int? = null
    private var epubFile: File? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        documentId = intent?.getStringExtra(EXTRA_DOCUMENT_ID)
        documentId?.let { SessionStore.saveLastDocumentId(this, it) }
        targetSection = intent?.getIntExtra(EXTRA_TARGET_SECTION, -1)?.takeIf { it >= 0 }
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH) ?: run { finish(); return }
        val file = File(filePath)
        if (!file.exists()) { finish(); return }
        epubFile = file
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateScreenCaptureFlag()
        containerId = View.generateViewId()
        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EpubReaderHost(
                        epubFile = epubFile,
                        documentId = documentId,
                        targetSection = targetSection,
                        containerId = containerId,
                        activity = this@EpubReaderActivity,
                        onBack = { finish() },
                        onToggleFavorite = { toggleFavorite() },
                    )
                }
            }
        }
    }
    override fun onResume() { super.onResume(); updateScreenCaptureFlag() }
    private fun updateScreenCaptureFlag() {
        if (AppPreferencesStore.isScreenshotsEnabled(this)) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    override fun onPause() {
        super.onPause()
        val docId = documentId ?: return
        val fragment = supportFragmentManager.findFragmentById(containerId) as? EpubNavigatorFragment ?: return
        val locator = fragment.currentLocator.value
        val progression = locator.locations.progression ?: 0.0
        val percent = (progression * 100).toInt().coerceIn(1, 100)
        lifecycleScope.launch(Dispatchers.IO) {
            val app = application as LibreCrateApplication
            app.vaultRepository.setReadingPosition(docId, locator.toJSON().toString())
            app.vaultRepository.setCurrentPage(docId, percent)
        }
    }
    private fun toggleFavorite() {
        val docId = documentId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val app = application as LibreCrateApplication
            val doc = app.vaultRepository.getDocument(docId) ?: return@launch
            app.vaultRepository.updateDocument(docId, doc.title, !doc.isFavorite)
        }
    }
}
@OptIn(ExperimentalReadiumApi::class)
@Composable
private fun EpubReaderHost(
    epubFile: File?,
    documentId: String?,
    targetSection: Int?,
    containerId: Int,
    activity: FragmentActivity,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val context = LocalContext.current as FragmentActivity
    var publication by remember { mutableStateOf<Publication?>(null) }
    var document by remember { mutableStateOf<Document?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val app = context.application as LibreCrateApplication
            val doc = withContext(Dispatchers.IO) {
                documentId?.let { app.vaultRepository.getDocument(it) }
            }
            document = doc
            val fileToOpen = epubFile ?: throw RuntimeException("No EPUB file available")
            val pub = withContext(Dispatchers.IO) {
                val httpClient = DefaultHttpClient()
                val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
                val parser = DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null)
                val opener = PublicationOpener(parser)
                val asset = when (val result = assetRetriever.retrieve(fileToOpen)) {
                    is Try.Success -> result.value
                    is Try.Failure -> throw RuntimeException("Asset retrieval failed: ${result.value.message}")
                }
                when (val result = opener.open(asset, allowUserInteraction = false)) {
                    is Try.Success -> result.value
                    is Try.Failure -> throw RuntimeException("Publication open failed: ${result.value.message}")
                }
            }
            publication = pub
            val initialLocator = withContext(Dispatchers.IO) {
                documentId?.let { id ->
                    val docEntry = app.vaultRepository.getDocument(id)
                    docEntry?.readingPosition?.let { json ->
                        try { Locator.fromJSON(JSONObject(json)) } catch (_: Exception) { null }
                    }
                }
            }
            val readerPrefs = ReaderPreferencesStore.load(context)
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
            activity.supportFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(initialLocator = initialLocator, initialPreferences = initialPreferences)
            isReady = true
        } catch (e: Exception) {
            com.librecrate.app.util.ErrorLogger.logException(context, TAG, "Failed to open EPUB", e)
            error = e.message
        }
    }
    when {
        error != null -> {
            LaunchedEffect(error) {
                Toast.makeText(context, "Failed to open EPUB: $error", Toast.LENGTH_LONG).show()
                (context as? FragmentActivity)?.finish()
            }
        }
        isReady -> EpubReaderScreen(document = document, containerId = containerId, publication = publication, targetSection = targetSection, onBack = onBack, onToggleFavorite = onToggleFavorite)
        else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
@Composable
private fun EpubReaderScreen(
    document: Document?,
    containerId: Int,
    publication: Publication?,
    targetSection: Int?,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var isFavorite by remember { mutableStateOf(document?.isFavorite ?: false) }
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    fun navigateForward() { (activity?.supportFragmentManager?.findFragmentById(containerId) as? EpubNavigatorFragment)?.goForward(true) }
    fun navigateBackward() { (activity?.supportFragmentManager?.findFragmentById(containerId) as? EpubNavigatorFragment)?.goBackward(true) }
    LaunchedEffect(targetSection, publication) {
        if (targetSection == null || publication == null) return@LaunchedEffect
        if (targetSection >= publication.readingOrder.size) return@LaunchedEffect
        val frag = activity?.supportFragmentManager?.findFragmentById(containerId) as? EpubNavigatorFragment ?: return@LaunchedEffect
        frag.go(publication.readingOrder[targetSection], true)
    }
    if (showInfoDialog && document != null) {
        val doc = document
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        AlertDialog(
            onDismissRequest = { showInfoDialog = false }, title = { Text("Document Info") },
            text = {
                Column {
                    InfoRow("Title", doc.title); InfoRow("Type", doc.mimeType); InfoRow("Size", formatFileSize(doc.fileSize))
                    InfoRow("Pages", doc.pageCount.toString()); InfoRow("Author", doc.author.ifEmpty { "\u2014" })
                    InfoRow("Imported", dateFormat.format(Date(doc.importedAt)))
                    if (doc.lastOpenedAt > 0) InfoRow("Last opened", dateFormat.format(Date(doc.lastOpenedAt)))
                    doc.description.ifEmpty { null }?.let {
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                        Text("Description", style = MaterialTheme.typography.labelMedium); Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showInfoDialog = false }) { Text("Close") } },
        )
    }
    if (showSettingsDialog) {
        val act = activity
        if (act != null) ReaderSettingsDialog(activity = act, containerId = containerId, onDismiss = { showSettingsDialog = false })
    }
    if (showRenameDialog && document != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false; renameText = "" }, title = { Text("Rename document") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotEmpty()) {
                        val docId = document?.id ?: return@TextButton
                        scope.launch(Dispatchers.IO) {
                            val app = (activity?.application as? LibreCrateApplication) ?: return@launch
                            val doc = app.vaultRepository.getDocument(docId) ?: return@launch
                            app.vaultRepository.updateDocument(docId, name, doc.isFavorite)
                        }
                    }
                    showRenameDialog = false; renameText = ""
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false; renameText = "" }) { Text("Cancel") } },
        )
    }
    if (showDeleteDialog && document != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false }, title = { Text("Delete document") }, text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false; val doc = document
                    val app = (activity?.application as? LibreCrateApplication) ?: return@TextButton
                    scope.launch(Dispatchers.IO) {
                        app.vaultRepository.deleteDocumentFull(doc.id)
                        withContext(Dispatchers.Main) { activity?.finish() }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }
    if (showTocSheet && publication != null) {
        val fragment = activity?.supportFragmentManager?.findFragmentById(containerId) as? EpubNavigatorFragment
        if (fragment != null) ChapterTocSheet(publication = publication, navigatorFragment = fragment, onDismiss = { showTocSheet = false })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = document?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (document != null) {
                        IconButton(onClick = { showTocSheet = true }) { Icon(Icons.Filled.List, "Table of Contents") }
                        var showMore by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMore = true }) { Icon(Icons.Default.MoreVert, "More options") }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(text = { Text("Info") }, onClick = { showMore = false; showInfoDialog = true }, leadingIcon = { Icon(Icons.Filled.Info, null) })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { showMore = false; renameText = document.title; showRenameDialog = true }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                            DropdownMenuItem(text = { if (isFavorite) Text("Remove favorite") else Text("Add favorite") }, onClick = { showMore = false; isFavorite = !isFavorite; onToggleFavorite() }, leadingIcon = { Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { showMore = false; showDeleteDialog = true }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                            DropdownMenuItem(text = { Text("Reader Settings") }, onClick = { showMore = false; showSettingsDialog = true }, leadingIcon = { Icon(Icons.Filled.Settings, null) })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val marginPx = with(ctx.resources.displayMetrics) { (ReaderPreferencesStore.load(ctx).topBottomMargin * density).toInt() }
                        FrameLayout(ctx).apply {
                            setBackgroundColor(android.graphics.Color.WHITE)
                            addView(FragmentContainerView(ctx).apply { id = containerId; setPadding(0, marginPx, 0, marginPx) }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            post {
                                val fm = (ctx as FragmentActivity).supportFragmentManager
                                if (fm.findFragmentById(containerId) == null) fm.commit { add(containerId, EpubNavigatorFragment::class.java, null) }
                                fm.executePendingTransactions()
                                (fm.findFragmentById(containerId) as? EpubNavigatorFragment)?.addInputListener(object : InputListener {
                                    override fun onTap(event: TapEvent): Boolean {
                                        val sw = ctx.resources.displayMetrics.widthPixels
                                        if (event.point.x < sw / 3f) navigateBackward() else navigateForward()
                                        return true
                                    }
                                    override fun onDrag(event: DragEvent): Boolean = false
                                    override fun onKey(event: org.readium.r2.navigator.input.KeyEvent): Boolean = false
                                })
                            }
                        }
                    },
                )
            }
        }
    }
}
@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics { })
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterTocSheet(publication: Publication, navigatorFragment: EpubNavigatorFragment, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tocLinks = remember { flattenToc(publication.tableOfContents) }
    val listState = rememberLazyListState()
    val currentLocator by navigatorFragment.currentLocator.collectAsState()
    val activeIndex = remember(currentLocator, tocLinks) { findActiveTocIndex(currentLocator, tocLinks) }
    LaunchedEffect(activeIndex) { if (activeIndex >= 0) listState.animateScrollToItem(activeIndex) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text("Table of Contents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        HorizontalDivider()
        if (tocLinks.isEmpty()) {
            Text("No table of contents available.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(tocLinks, key = { _, link -> link.first }) { index, (link, depth) ->
                    TocItem(link = link, depth = depth, isActive = index == activeIndex, onClick = { navigatorFragment.go(link, true); onDismiss() })
                }
            }
        }
    }
}
@Composable
private fun TocItem(link: Link, depth: Int, isActive: Boolean, onClick: () -> Unit) {
    val title = (link.title ?: "").ifBlank { link.href.toString() }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (24 + depth * 16).dp, end = 24.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (isActive) "\u25B6 " else "  ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
internal fun flattenToc(links: List<Link>, depth: Int = 0): List<Pair<Link, Int>> {
    val result = mutableListOf<Pair<Link, Int>>()
    for (link in links) { result.add(link to depth); if (link.children.isNotEmpty()) result.addAll(flattenToc(link.children, depth + 1)) }
    return result
}
internal fun findActiveTocIndex(currentLocator: Locator?, tocLinks: List<Pair<Link, Int>>): Int {
    val locator = currentLocator ?: return -1; val locatorHref = locator.href.toString()
    var bestIndex = -1; var bestLength = 0
    for (i in tocLinks.indices) { val linkHref = tocLinks[i].first.href.toString(); if (locatorHref.startsWith(linkHref) && linkHref.length > bestLength) { bestIndex = i; bestLength = linkHref.length } }
    return bestIndex
}
private fun formatFileSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "${bytes / 1024} KB"; else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024)) }
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsDialog(activity: FragmentActivity, containerId: Int, onDismiss: () -> Unit) {
    val context = activity; val prefs = remember { ReaderPreferencesStore.load(context) }
    var fontSize by remember { mutableStateOf(prefs.fontSize) }; var fontFamilyName by remember { mutableStateOf(prefs.fontFamilyName) }
    var lineHeight by remember { mutableStateOf(prefs.lineHeight) }; var pageMargins by remember { mutableStateOf(prefs.pageMargins) }; var topBottomMargin by remember { mutableStateOf(prefs.topBottomMargin) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Reader Settings") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Font Size", style = MaterialTheme.typography.bodyLarge); Text("${String.format(Locale.ROOT, "%.1f", fontSize)}x", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 0.75f..2.0f, steps = 4, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Font Family", style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(4.dp))
                FontFamilyDropdown(selected = FontFamilyName.entries.firstOrNull { it.name == fontFamilyName } ?: FontFamilyName.SERIF, onSelected = { fontFamilyName = it.name })
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Line spacing", style = MaterialTheme.typography.bodyLarge); Text("${String.format(Locale.ROOT, "%.1f", lineHeight)}x", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Slider(value = lineHeight, onValueChange = { lineHeight = it }, valueRange = 1.0f..2.5f, steps = 5, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Page margins", style = MaterialTheme.typography.bodyLarge); Text("${String.format(Locale.ROOT, "%.1f", pageMargins)}x", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Slider(value = pageMargins, onValueChange = { pageMargins = it }, valueRange = 0.0f..3.0f, steps = 5, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Top/bottom margin", style = MaterialTheme.typography.bodyLarge); Text("${String.format(Locale.ROOT, "%.0f", topBottomMargin)} dp", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Slider(value = topBottomMargin, onValueChange = { topBottomMargin = it }, valueRange = 0f..50f, steps = 9, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ReaderPreferencesStore.save(context, ReaderPreferences(fontSize, fontFamilyName, lineHeight, pageMargins, topBottomMargin))
                (context.supportFragmentManager.findFragmentById(containerId) as? EpubNavigatorFragment)?.submitPreferences(EpubPreferences(fontSize = fontSize.toDouble(), fontFamily = when (fontFamilyName) { FontFamilyName.SANS_SERIF.name -> FontFamily.SANS_SERIF; FontFamilyName.OPEN_DYSLEXIC.name -> FontFamily.OPEN_DYSLEXIC; else -> FontFamily.SERIF }, lineHeight = lineHeight.toDouble(), pageMargins = pageMargins.toDouble(), publisherStyles = false))
                val density = context.resources.displayMetrics.density
                context.findViewById<FragmentContainerView>(containerId)?.setPadding(0, (topBottomMargin * density).toInt(), 0, (topBottomMargin * density).toInt())
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontFamilyDropdown(selected: FontFamilyName, onSelected: (FontFamilyName) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(value = selected.label, onValueChange = {}, readOnly = true, singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FontFamilyName.entries.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelected(option); expanded = false }) }
        }
    }
}
