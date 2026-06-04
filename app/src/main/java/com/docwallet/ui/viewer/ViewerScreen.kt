package com.docwallet.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docwallet.data.model.DocumentType
import com.docwallet.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    documentId: String,
    onBack: () -> Unit,
    viewModel: ViewerViewModel = viewModel(),
) {
    val document by viewModel.document.collectAsState()
    val decryptedFile by viewModel.decryptedFile.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(documentId) {
        viewModel.loadDocument(documentId)
    }

    Scaffold(
        topBar = {
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
                                onClick = { menuExpanded = false },
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
                        DocumentType.PDF -> PdfViewer(file = file, document = doc)
                        DocumentType.EPUB -> {
                            val context = LocalContext.current
                            LaunchedEffect(file) {
                                EpubReaderActivity.start(context, file.absolutePath)
                                onBack()
                            }
                        }
                        DocumentType.PKPASS -> PkPassViewer(file = file)
                        DocumentType.CBZ, DocumentType.CBR -> ComicViewer(file = file)
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
