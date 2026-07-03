package com.docwallet.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docwallet.data.model.DocumentType
import com.docwallet.ui.common.EmptyState

private val IMPORT_MIME_TYPES = arrayOf(
    "application/pdf",
    "application/epub+zip",
    "application/vnd.apple.pkpass",
    "application/vnd.comicbook+zip",
    "application/x-cbr",
    "image/*",
    "text/markdown",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onDocumentClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onNewNoteClick: () -> Unit = {},
    pendingImportUris: List<Uri> = emptyList(),
    onPendingImportConsumed: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val documents by viewModel.documents.collectAsState()
    val continueReading by viewModel.continueReading.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedSort by viewModel.selectedSort.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    var fabExpanded by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importDocuments(uris)
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbarMessage()
        }
    }

    LaunchedEffect(pendingImportUris) {
        if (pendingImportUris.isNotEmpty()) {
            viewModel.importDocuments(pendingImportUris)
            onPendingImportConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "DocWallet",
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Box {
                LargeFloatingActionButton(
                    onClick = { fabExpanded = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add",
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "New",
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                DropdownMenu(
                    expanded = fabExpanded,
                    onDismissRequest = { fabExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Import document") },
                        onClick = {
                            fabExpanded = false
                            importLauncher.launch(IMPORT_MIME_TYPES)
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("New note") },
                        onClick = {
                            fabExpanded = false
                            onNewNoteClick()
                        },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null)
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.search(it) },
                placeholder = { Text("Search documents") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.search("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                } else null,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var sortExpanded by remember { mutableStateOf(false) }

                FilterChip(
                    selected = false,
                    onClick = { sortExpanded = true },
                    label = { Text(selectedSort.label) },
                )

                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                ) {
                    SortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                viewModel.setSort(option)
                                sortExpanded = false
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = filterType == null && !favoritesOnly,
                            onClick = {
                                viewModel.setFilter(null)
                                viewModel.favoritesOnly.value = false
                            },
                            label = { Text("All") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filterType == DocumentType.PDF,
                            onClick = { viewModel.setFilter(DocumentType.PDF) },
                            label = { Text("PDFs") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.PictureAsPdf,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filterType == DocumentType.EPUB,
                            onClick = { viewModel.setFilter(DocumentType.EPUB) },
                            label = { Text("Books") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.AutoStories,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filterType == DocumentType.CBZ || filterType == DocumentType.CBR,
                            onClick = {
                                viewModel.apply {
                                    setFilter(
                                        if (filterType == DocumentType.CBZ) null else DocumentType.CBZ
                                    )
                                }
                            },
                            label = { Text("Comics") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.AutoStories,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filterType == DocumentType.PKPASS,
                            onClick = { viewModel.setFilter(DocumentType.PKPASS) },
                            label = { Text("Passes") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.ConfirmationNumber,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filterType == DocumentType.IMAGE,
                            onClick = { viewModel.setFilter(DocumentType.IMAGE) },
                            label = { Text("Images") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = filterType == DocumentType.NOTE,
                            onClick = { viewModel.setFilter(DocumentType.NOTE) },
                            label = { Text("Notes") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Notes,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    item {
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = {
                                viewModel.favoritesOnly.value = !favoritesOnly
                            },
                            label = { Text("Favorites") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (documents.isEmpty()) {
                EmptyState(
                    icon = if (searchQuery.isNotBlank() || filterType != null || favoritesOnly)
                        Icons.Filled.Search else Icons.Outlined.Bookmarks,
                    title = if (searchQuery.isNotBlank()) "No results found"
                    else if (favoritesOnly) "No favorite documents"
                    else "No documents yet",
                    subtitle = if (searchQuery.isNotBlank()) "Try a different search term"
                    else if (favoritesOnly) "Tap the heart icon to add favorites"
                    else "Tap + to add your first document",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (continueReading.isNotEmpty()) {
                        item {
                            Column {
                                Text(
                                    text = "Continue reading",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(continueReading, key = { it.id }) { doc ->
                                        ContinueReadingCard(
                                            document = doc,
                                            onClick = { onDocumentClick(doc.id) },
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    items(documents, key = { it.id }) { document ->
                        DocumentCard(
                            document = document,
                            onClick = { onDocumentClick(document.id) },
                            onFavoriteClick = { viewModel.toggleFavorite(document) },
                            thumbnail = thumbnails[document.id],
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { documents }
            .collect { list ->
                list.forEach { document ->
                    document.thumbnailPath?.let { viewModel.loadThumbnail(document.id, it) }
                }
            }
    }
}

@Composable
private fun ContinueReadingCard(
    document: com.docwallet.data.model.Document,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(160.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            DocumentTypeIcon(
                type = com.docwallet.data.model.DocumentType.fromMimeType(document.mimeType),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.clearAndSetSemantics { }) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (document.currentPage > 0 && document.pageCount > 0) {
                    Text(
                        text = "Page ${document.currentPage + 1} of ${document.pageCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = "Opened ${formatRelativeTime(document.lastOpenedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}
