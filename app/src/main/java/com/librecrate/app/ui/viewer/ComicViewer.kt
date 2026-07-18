package com.librecrate.app.ui.viewer

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

private const val MAX_BITMAP_CACHE = 20

private data class ComicPageInfo(val index: Int, val name: String)

@Composable
fun ComicViewer(
    file: File,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    var pageInfos by remember { mutableStateOf<List<ComicPageInfo>>(emptyList()) }
    val pageBitmaps = remember { mutableStateMapOf<Int, ImageBitmap>() }
    var showFullPage by remember { mutableStateOf(false) }
    var currentPageIndex by remember { mutableIntStateOf(initialPage) }

    LaunchedEffect(file) {
        val infos = withContext(Dispatchers.IO) { loadComicPageInfos(file) }
        pageInfos = infos
        currentPageIndex = initialPage.coerceIn(0, (infos.size - 1).coerceAtLeast(0))
    }

    LaunchedEffect(currentPageIndex) {
        if (currentPageIndex in pageInfos.indices) {
            onPageChanged(currentPageIndex)
        }
    }

    if (showFullPage && currentPageIndex in pageInfos.indices) {
        var fullPageBitmap by remember(currentPageIndex) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(currentPageIndex) {
            val info = pageInfos[currentPageIndex]
            val bitmap = withContext(Dispatchers.IO) {
                decodeSinglePage(file, info.name, maxWidth = 0)
            }
            fullPageBitmap = bitmap?.asImageBitmap()
        }
        FullPageViewer(
            bitmap = fullPageBitmap,
            pageIndex = currentPageIndex,
            pageCount = pageInfos.size,
            onBack = { showFullPage = false },
            onPrevious = {
                if (currentPageIndex > 0) {
                    currentPageIndex--
                }
            },
            onNext = {
                if (currentPageIndex < pageInfos.size - 1) {
                    currentPageIndex++
                }
            },
        )
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "${pageInfos.size} pages",
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
                itemsIndexed(pageInfos) { index, _ ->
                    LaunchedEffect(index) {
                        val bitmap = withContext(Dispatchers.IO) {
                            decodeSinglePage(file, pageInfos[index].name, maxWidth = 200)
                        }
                        if (bitmap != null) {
                            pageBitmaps[index] = bitmap.asImageBitmap()
                            if (pageBitmaps.size > MAX_BITMAP_CACHE) {
                                val toEvict = pageBitmaps.keys
                                    .filter { it != index }
                                    .sorted()
                                    .take(pageBitmaps.size - MAX_BITMAP_CACHE)
                                toEvict.forEach { pageBitmaps.remove(it) }
                            }
                        }
                    }
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
                            val thumb = pageBitmaps[index]
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb,
                                    contentDescription = "Page ${index + 1}",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clearAndSetSemantics { },
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Text(
                                    text = "Page ${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullPageViewer(
    bitmap: ImageBitmap?,
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
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
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
        }

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

private fun loadComicPageInfos(file: File): List<ComicPageInfo> {
    return try {
        val name = file.name.lowercase()
        val entries = when {
            name.endsWith(".cbz") -> loadCbzEntries(file)
            name.endsWith(".cbr") -> loadRarEntries(file)
            else -> emptyList()
        }
        entries.mapIndexed { index, entryName -> ComicPageInfo(index, entryName) }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadCbzEntries(file: File): List<String> {
    return try {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && isImageEntry(it.name) }
                .sortedBy { it.name }
                .map { it.name }
                .toList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun loadRarEntries(file: File): List<String> {
    return try {
        FileInputStream(file).use { fis ->
            Archive(fis).use { archive ->
                archive.fileHeaders
                    .filter { !it.isDirectory && isImageEntry(it.fileName) }
                    .sortedBy { it.fileName }
                    .map { it.fileName }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun decodeSinglePage(file: File, entryName: String, maxWidth: Int): Bitmap? {
    return try {
        val name = file.name.lowercase()
        val bitmap = when {
            name.endsWith(".cbz") -> decodeCbzEntry(file, entryName)
            name.endsWith(".cbr") -> decodeRarEntry(file, entryName)
            else -> null
        }
        if (bitmap != null && maxWidth > 0 && bitmap.width > maxWidth) {
            val h = (maxWidth * bitmap.height) / bitmap.width
            Bitmap.createScaledBitmap(bitmap, maxWidth, h.coerceAtLeast(1), true)
        } else bitmap
    } catch (_: Exception) {
        null
    }
}

private fun decodeCbzEntry(file: File, entryName: String): Bitmap? {
    return try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            zip.getInputStream(entry).use { stream ->
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(stream, null, opts)
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return@use null
                val sampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, 2048)
                zip.getInputStream(entry).use { s ->
                    BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    })
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun decodeRarEntry(file: File, entryName: String): Bitmap? {
    return try {
        FileInputStream(file).use { fis ->
            Archive(fis).use { archive ->
                val fh = archive.fileHeaders.find { it.fileName == entryName } ?: return null
                archive.getInputStream(fh).use { stream ->
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(stream, null, opts)
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) return@use null
                    val sampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, 2048)
                    archive.getInputStream(fh).use { s ->
                        BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                        })
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var inSampleSize = 1
    if (height > maxDimension || width > maxDimension) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= maxDimension && halfWidth / inSampleSize >= maxDimension) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

private fun isImageEntry(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
        lower.endsWith(".png") || lower.endsWith(".webp") ||
        lower.endsWith(".gif") || lower.endsWith(".bmp")
}
