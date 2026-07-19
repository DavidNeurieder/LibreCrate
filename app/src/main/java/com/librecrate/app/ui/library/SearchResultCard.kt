package com.librecrate.app.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.librecrate.app.data.model.SearchResultItem
import com.librecrate.app.data.model.SearchResultMatch
import com.librecrate.app.data.model.DocumentType

@Composable
fun SearchResultCard(
    result: SearchResultItem,
    onClick: () -> Unit,
    onMatchClick: (String, Int) -> Unit,
    thumbnail: ImageBitmap? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "Document thumbnail",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                DocumentTypeIcon(
                    type = DocumentType.fromMimeType(result.mimeType),
                    modifier = Modifier.size(48.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (result.matches.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    MatchRow(
                        match = result.matches.first(),
                        highlightColor = MaterialTheme.colorScheme.primary,
                    )

                    if (result.matches.size > 1) {
                        val remaining = result.matches.size - 1
                        TextButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text(
                                text = if (expanded) "Show less" else "+$remaining more",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (result.author.isNotBlank() || result.pageCount > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            if (result.author.isNotBlank()) append(result.author)
                            if (result.author.isNotBlank() && result.pageCount > 0) append(" \u00B7 ")
                            if (result.pageCount > 0) append("${result.pageCount} pages")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 60.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                result.matches.drop(1).forEach { match ->
                    MatchRow(
                        match = match,
                        highlightColor = MaterialTheme.colorScheme.primary,
                        onClick = { onMatchClick(result.id, match.pageNumber) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchRow(
    match: SearchResultMatch,
    highlightColor: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = match.snippet.let { highlightSnippet(it, highlightColor) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (match.pageNumber > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "p.${match.pageNumber}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .let { it },
            )
        }
    }
}

fun highlightSnippet(text: String, highlightColor: Color) = buildAnnotatedString {
    val regex = Regex("<b>(.*?)</b>")
    var lastIndex = 0
    regex.findAll(text).forEach { match ->
        append(text.substring(lastIndex, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor)) {
            append(match.groupValues[1])
        }
        lastIndex = match.range.last + 1
    }
    append(text.substring(lastIndex))
}
