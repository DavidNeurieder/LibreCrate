package com.docwallet.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docwallet.data.model.DocumentType
import com.docwallet.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    documentId: String,
    onBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel(),
    onDocumentNotFound: () -> Unit = {},
) {
    val document by viewModel.document.collectAsState()
    val decryptedFile by viewModel.decryptedFile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }

    val activity = LocalContext.current as? Activity

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val window = activity?.window ?: return
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

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

    LaunchedEffect(documentId) {
        viewModel.loadDocument(documentId)
    }

    LaunchedEffect(error) {
        if (error == "Document not found") {
            onDocumentNotFound()
        }
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(visible = !isFullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            text = document?.title ?: "Document",
                            fontWeight = FontWeight.Bold,
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
                            IconButton(onClick = { viewModel.toggleFavorite() }) {
                                Icon(
                                    imageVector = if (document!!.isFavorite)
                                        Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (document!!.isFavorite)
                                        "Remove from favorites" else "Add to favorites",
                                    tint = if (document!!.isFavorite)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = "More options",
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Document info") },
                                    onClick = {
                                        menuExpanded = false
                                        showInfoDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Info, contentDescription = null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Delete document",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.deleteDocument()
                                        onBack()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
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
                            onToggleFullscreen = ::toggleFullscreen,
                            isFullscreen = isFullscreen,
                        )
                        DocumentType.EPUB -> {
                            val context = LocalContext.current
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
