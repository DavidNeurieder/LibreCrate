package com.librecrate.app.ui.library
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.librecrate.app.data.model.Document
import com.librecrate.app.data.model.DocumentType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DocumentCard(
    document: Document,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    thumbnail: ImageBitmap? = null,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "Document thumbnail",
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                DocumentTypeIcon(
                    type = DocumentType.fromMimeType(document.mimeType),
                    modifier = Modifier.size(64.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        val docType = DocumentType.fromMimeType(document.mimeType)
                        append(docType.name)
                        if (document.currentPage > 0) {
                            if (docType == DocumentType.EPUB) {
                                append(" \u00B7 ${document.currentPage}% read")
                            } else if (document.pageCount > 0) {
                                append(" \u00B7 Page ${document.currentPage} of ${document.pageCount}")
                            }
                        } else if (docType == DocumentType.EPUB) {
                            append(" \u00B7 0% read")
                        } else if (document.pageCount > 0) {
                            append(" \u00B7 ${document.pageCount} pages")
                        }
                        append(" \u00B7 ${formatFileSize(document.fileSize)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (document.lastOpenedAt > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Opened ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        RelativeTimestamp(timestamp = document.lastOpenedAt)
                    }
                } else {
                    Text(
                        text = "Not yet opened",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (document.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (document.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (document.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
@Composable
fun DocumentTypeIcon(type: DocumentType, modifier: Modifier = Modifier) {
    val icon: ImageVector = when (type) {
        DocumentType.PDF -> Icons.Outlined.PictureAsPdf
        DocumentType.EPUB -> Icons.AutoMirrored.Outlined.MenuBook
        DocumentType.PKPASS -> Icons.Outlined.ConfirmationNumber
        DocumentType.CBZ, DocumentType.CBR -> Icons.Outlined.AutoStories
        DocumentType.IMAGE -> Icons.Outlined.Image
        DocumentType.NOTE -> Icons.AutoMirrored.Outlined.Notes
        DocumentType.UNKNOWN -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    Icon(
        imageVector = icon,
        contentDescription = type.name,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
@Composable
fun RelativeTimestamp(timestamp: Long) {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val text = when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} minutes ago"
        diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
        diff < 172_800_000 -> "Yesterday"
        diff < 604_800_000 -> "${diff / 86_400_000} days ago"
        else -> {
            val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}
