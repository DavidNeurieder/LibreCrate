package com.docwallet.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.github.junrar.Archive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipFile

@Composable
fun ComicViewer(
    file: File,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    val pageImages = remember(file) { loadComicPages(file) }
    var showFullPage by remember { mutableStateOf(false) }
    var currentPageIndex by remember { mutableIntStateOf(initialPage.coerceIn(0, (pageImages.size - 1).coerceAtLeast(0))) }

    LaunchedEffect(currentPageIndex) {
        if (currentPageIndex in pageImages.indices) {
            onPageChanged(currentPageIndex)
        }
    }

    if (showFullPage && currentPageIndex in pageImages.indices) {
        FullPageViewer(
            bitmap = pageImages[currentPageIndex],
            pageIndex = currentPageIndex,
            pageCount = pageImages.size,
            onBack = { showFullPage = false },
            onPrevious = { if (currentPageIndex > 0) currentPageIndex-- },
            onNext = { if (currentPageIndex < pageImages.size - 1) currentPageIndex++ },
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "${pageImages.size} pages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(pageImages) { index, bitmap ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f)
                            .clickable {
                                currentPageIndex = index
                                showFullPage = true
                            },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Page ${index + 1}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clearAndSetSemantics { },
                                contentScale = ContentScale.Fit,
                            )
                            Text(
                                text = "Page ${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullPageViewer(
    bitmap: Bitmap,
    pageIndex: Int,
    pageCount: Int,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat()
                    if (offset.x < width / 3f) {
                        onPrevious()
                    } else if (offset.x > 2f * width / 3f) {
                        onNext()
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Page ${pageIndex + 1}",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                )
                .clearAndSetSemantics { },
            contentScale = ContentScale.Fit,
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        ) {
            Text(
                text = "Page ${pageIndex + 1} of $pageCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = "< Back",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .clickable { onBack() },
        )
    }
}

private fun loadComicPages(file: File): List<Bitmap> {
    return try {
        val name = file.name.lowercase()
        when {
            name.endsWith(".cbz") -> loadCbzPages(file)
            name.endsWith(".cbr") -> loadRarPages(file)
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadCbzPages(file: File): List<Bitmap> {
    val pages = mutableListOf<Bitmap>()
    try {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && isImageEntry(it.name) }
                .sortedBy { it.name }
                .toList()

            entries.forEach { entry ->
                try {
                    val bitmap = zip.getInputStream(entry).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                    if (bitmap != null) pages.add(bitmap)
                } catch (_: Exception) {
                }
            }
        }
    } catch (_: Exception) {
    }
    return pages
}

private fun loadRarPages(file: File): List<Bitmap> {
    val pages = mutableListOf<Bitmap>()
    try {
        FileInputStream(file).use { fis ->
            Archive(fis).use { archive ->
                val imageHeaders = archive.fileHeaders
                    .filter { !it.isDirectory && isImageEntry(it.fileName) }
                    .sortedBy { it.fileName }

                for (fh in imageHeaders) {
                    try {
                        archive.getInputStream(fh).use { stream ->
                            val bitmap = BitmapFactory.decodeStream(stream)
                            if (bitmap != null) pages.add(bitmap)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    } catch (_: Exception) {
    }
    return pages
}

private fun isImageEntry(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
        lower.endsWith(".png") || lower.endsWith(".webp") ||
        lower.endsWith(".gif") || lower.endsWith(".bmp")
}
