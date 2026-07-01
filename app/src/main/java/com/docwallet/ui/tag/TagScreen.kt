package com.docwallet.ui.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docwallet.data.model.Tag
import com.docwallet.ui.common.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    onBack: () -> Unit,
    onTagClick: (String) -> Unit,
    viewModel: TagViewModel = viewModel(),
) {
    val tags by viewModel.tags.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val nameInput by viewModel.nameInput.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val showDialog by viewModel.showDialog.collectAsState()
    val showEditDialog by viewModel.showEditDialog.collectAsState()

    var tagToDelete by remember { mutableStateOf<Tag?>(null) }

    val presetColors = listOf(
        0xFFF44336L to "Red",
        0xFFFF9800L to "Orange",
        0xFFFFEB3BL to "Yellow",
        0xFF4CAF50L to "Green",
        0xFF009688L to "Teal",
        0xFF2196F3L to "Blue",
        0xFF9C27B0L to "Purple",
        0xFFE91E63L to "Pink",
        0xFF795548L to "Brown",
        0xFF9E9E9EL to "Grey",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.nameInput.value = ""
                    viewModel.selectedColor.value = viewModel.randomColor()
                    viewModel.showDialog.value = true
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Create tag",
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (tags.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.Label,
                    title = "No tags yet",
                    subtitle = "Tap + to create your first tag",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tags, key = { it.id }) { tag ->
                        Card(
                            onClick = { onTagClick(tag.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(tag.color)),
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "? items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(onClick = { viewModel.setEditing(tag) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = "Rename",
                                    )
                                }
                                IconButton(onClick = { tagToDelete = tag }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.showDialog.value = false
            },
            title = { Text("Create tag") },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { viewModel.nameInput.value = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Color",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        presetColors.forEach { (color, _) ->
                            val isSelected = selectedColor == color
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(color))
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .clickable { viewModel.selectedColor.value = color },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = nameInput.trim()
                        if (name.isNotEmpty()) {
                            viewModel.createTag(name, selectedColor)
                            viewModel.showDialog.value = false
                        }
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDialog.value = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.showEditDialog.value = false
                viewModel.nameInput.value = ""
                viewModel.editingTag.value = null
            },
            title = { Text("Rename tag") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { viewModel.nameInput.value = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.saveEdit() }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.showEditDialog.value = false
                        viewModel.nameInput.value = ""
                        viewModel.editingTag.value = null
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            title = { Text("Delete tag") },
            text = { Text("Are you sure you want to delete \"${tag.name}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTag(tag.id)
                        tagToDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
