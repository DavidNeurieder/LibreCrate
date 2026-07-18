package com.librecrate.app.ui.library

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.librecrate.app.vault.model.DocumentType
import com.librecrate.app.ui.common.EmptyState

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
    onDocumentClickWithPage: (String, Int) -> Unit,
    onSettingsClick: () -> Unit,
    onNewNoteClick: () -> Unit = {},
    pendingImportUris: List<Uri> = emptyList(),
    onPendingImportConsumed: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val documents by viewModel.documents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val selectedSort by viewModel.selectedSort.collectAsState()
    val filterType by viewModel.filterType.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    var fabExpanded by remember { mutableStateOf(false) }
    var isSearchActive by remember { mutableStateOf(false) }

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
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.search(it) },
                            placeholder = { Text("Search documents") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(
                            text = "LibreCrate",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                navigationIcon = {
                    if (isSearchActive) {
                        IconButton(onClick = { isSearchActive = false; viewModel.search("") }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close search",
                            )
                        }
                    }
                },
                actions = {
                    if (isSearchActive) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.search("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search",
                            )
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                            )
                        }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var sortExpanded by remember { mutableStateOf(false) }

                FilterChip(
                    selected = sortExpanded,
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

                var typeExpanded by remember { mutableStateOf(false) }

                val typeLabel = when {
                    filterType == DocumentType.PDF -> "PDFs"
                    filterType == DocumentType.EPUB -> "Books"
                    filterType == DocumentType.CBZ || filterType == DocumentType.CBR -> "Comics"
                    filterType == DocumentType.PKPASS -> "Passes"
                    filterType == DocumentType.IMAGE -> "Images"
                    filterType == DocumentType.NOTE -> "Notes"
                    else -> "All"
                }

                FilterChip(
                    selected = filterType != null,
                    onClick = { typeExpanded = true },
                    label = { Text(typeLabel) },
                )

                DropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All") },
                        onClick = { viewModel.setFilter(null); typeExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("PDFs") },
                        onClick = { viewModel.setFilter(DocumentType.PDF); typeExpanded = false },
                        leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null, modifier = Modifier.size(20.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Books") },
                        onClick = { viewModel.setFilter(DocumentType.EPUB); typeExpanded = false },
                        leadingIcon = { Icon(Icons.Outlined.AutoStories, null, modifier = Modifier.size(20.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Comics") },
                        onClick = { viewModel.setFilter(DocumentType.CBZ); typeExpanded = false },
                        leadingIcon = { Icon(Icons.Outlined.AutoStories, null, modifier = Modifier.size(20.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Passes") },
                        onClick = { viewModel.setFilter(DocumentType.PKPASS); typeExpanded = false },
                        leadingIcon = { Icon(Icons.Outlined.ConfirmationNumber, null, modifier = Modifier.size(20.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Images") },
                        onClick = { viewModel.setFilter(DocumentType.IMAGE); typeExpanded = false },
                        leadingIcon = { Icon(Icons.Outlined.Image, null, modifier = Modifier.size(20.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Notes") },
                        onClick = { viewModel.setFilter(DocumentType.NOTE); typeExpanded = false },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Notes, null, modifier = Modifier.size(20.dp)) },
                    )
                }

                FilterChip(
                    selected = favoritesOnly,
                    onClick = { viewModel.favoritesOnly.value = !favoritesOnly },
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

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (isSearchActive && searchQuery.isNotBlank()) {
                if (searchResults.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = "No results found",
                        subtitle = "Try a different search term",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(searchResults, key = { it.id }) { result ->
                            SearchResultCard(
                                result = result,
                                onClick = {
                                    viewModel.search("")
                                    onDocumentClick(result.id)
                                },
                                onMatchClick = { docId, pageNumber ->
                                    viewModel.search("")
                                    onDocumentClickWithPage(docId, pageNumber)
                                },
                                thumbnail = thumbnails[result.id],
                            )
                        }
                    }
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
                val listState = remember { LazyListState() }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
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
private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}
