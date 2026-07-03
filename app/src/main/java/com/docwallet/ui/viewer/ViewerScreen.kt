package com.docwallet.ui.viewer

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.unit.dp
import com.docwallet.data.PageFitMode
import com.docwallet.data.PdfPreferences
import com.docwallet.data.PdfPreferencesStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docwallet.data.model.DocumentType
import com.docwallet.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    documentId: String,
    isNewNote: Boolean = false,
    onBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel(),
    onDocumentNotFound: () -> Unit = {},
) {
    val document by viewModel.document.collectAsState()
    val decryptedFile by viewModel.decryptedFile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var showInfoDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showPdfSettingsDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pdfPreferences = remember { mutableStateOf(PdfPreferencesStore.load(context)) }

    if (showInfoDialog && document != null) {
        val doc = document!!
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
                    InfoRow("Author", doc.author.ifEmpty { "—" })
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

    if (showDeleteDialog && document != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete document") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteDocument()
                    onBack()
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

    if (showRenameDialog && document != null) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                renameText = ""
            },
            title = { Text("Rename document") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renameText.trim()
                        if (name.isNotEmpty()) {
                            viewModel.renameDocument(name)
                        }
                        showRenameDialog = false
                        renameText = ""
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        renameText = ""
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    LaunchedEffect(documentId) {
        viewModel.loadDocument(documentId, isNewNote)
    }

    LaunchedEffect(error) {
        if (error == "Document not found") {
            onDocumentNotFound()
        }
    }

    if (showPdfSettingsDialog) {
        val currentPrefs = pdfPreferences.value
        AlertDialog(
            onDismissRequest = { showPdfSettingsDialog = false },
            title = { Text("PDF Settings") },
            text = {
                Column {
                    Text("Page fit mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    PageFitMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pdfPreferences.value = currentPrefs.copy(pageFitMode = mode)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = currentPrefs.pageFitMode == mode,
                                onClick = {
                                    pdfPreferences.value = currentPrefs.copy(pageFitMode = mode)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (mode) {
                                    PageFitMode.FIT_WIDTH -> "Fit width"
                                    PageFitMode.FIT_PAGE -> "Fit page"
                                    PageFitMode.ACTUAL_SIZE -> "Actual size"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Night mode",
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = currentPrefs.nightMode,
                            onCheckedChange = {
                                pdfPreferences.value = currentPrefs.copy(nightMode = it)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PdfPreferencesStore.save(context, pdfPreferences.value)
                    showPdfSettingsDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPdfSettingsDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = document?.title ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
                        var showMore by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMore = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                            )
                        }
                        DropdownMenu(
                            expanded = showMore,
                            onDismissRequest = { showMore = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Info") },
                                onClick = { showMore = false; showInfoDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Info, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    showMore = false
                                    renameText = document!!.title
                                    showRenameDialog = true
                                },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            )
                            DropdownMenuItem(
                                text = {
                                    if (document!!.isFavorite) Text("Remove favorite")
                                    else Text("Add favorite")
                                },
                                onClick = { showMore = false; viewModel.toggleFavorite() },
                                leadingIcon = {
                                    Icon(
                                        if (document!!.isFavorite) Icons.Filled.Favorite
                                        else Icons.Outlined.FavoriteBorder,
                                        null,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { showMore = false; showDeleteDialog = true },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            )
                            if (viewModel.getDocumentType() == DocumentType.PDF) {
                                DropdownMenuItem(
                                    text = { Text("PDF Settings") },
                                    onClick = { showMore = false; showPdfSettingsDialog = true },
                                    leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    EmptyState(
                        icon = Icons.Filled.Info,
                        title = "Error",
                        subtitle = error,
                    )
                }
                document != null && decryptedFile != null -> {
                    val doc = document!!
                    val file = decryptedFile!!
                    when (viewModel.getDocumentType()) {
                        DocumentType.PDF -> PdfViewer(
                            file = file,
                            document = doc,
                            initialPage = doc.currentPage,
                            onPageChanged = viewModel::saveReadingPosition,
                            pdfPreferences = pdfPreferences.value,
                        )
                        DocumentType.EPUB -> {
                            LaunchedEffect(file) {
                                EpubReaderActivity.start(context, file.absolutePath, doc.id)
                                onBack()
                            }
                        }
                        DocumentType.PKPASS -> PkPassViewer(file = file)
                        DocumentType.CBZ, DocumentType.CBR -> ComicViewer(
                            file = file,
                            initialPage = doc.currentPage,
                            onPageChanged = viewModel::saveReadingPosition,
                        )
                        DocumentType.NOTE -> NoteEditor(
                            document = doc,
                            onSaved = { viewModel.loadDocument(documentId) },
                        )
                        DocumentType.IMAGE -> ImageViewer(file = file, document = doc)
                        DocumentType.UNKNOWN -> EmptyState(
                            icon = Icons.Filled.Info,
                            title = "Unsupported format",
                            subtitle = "This file format cannot be viewed.",
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

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}
