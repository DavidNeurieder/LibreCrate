package com.docwallet.ui.viewer

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.docwallet.data.PageFitMode
import com.docwallet.data.PdfPreferences
import com.docwallet.data.model.Document as DocWalletDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

private const val MAX_CACHED_PAGES = 12

private val invertColorMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

@Composable
fun PdfViewer(
    file: File,
    document: DocWalletDocument,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    pdfPreferences: PdfPreferences = PdfPreferences(),
) {
    var pageCount by remember { mutableIntStateOf(document.pageCount) }
    val initialIndex = (initialPage - 1).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    var currentPage by remember { mutableIntStateOf(1) }

    val renderedPages = remember { mutableStateMapOf<Int, Bitmap>() }

    LaunchedEffect(Unit) {
        if (pageCount <= 0) {
            val count = withContext(Dispatchers.IO) {
                try {
                    val doc = Document.openDocument(file.absolutePath)
                    try {
                        doc.countPages()
                    } finally {
                        doc.destroy()
                    }
                } catch (e: Exception) {
                    Log.w("PdfViewer", "Failed to load page count", e)
                    0
                }
            }
            pageCount = count
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex) {
        currentPage = listState.firstVisibleItemIndex + 1
        onPageChanged(currentPage)
        val start = maxOf(0, listState.firstVisibleItemIndex - 1)
        val end = minOf(pageCount - 1, listState.firstVisibleItemIndex + 3)
        for (i in start..end) {
            if (i !in renderedPages) {
                withContext(Dispatchers.IO) {
                    try {
                        val doc = Document.openDocument(file.absolutePath)
                        try {
                            val page = doc.loadPage(i)
                            try {
                                val scale = 150f / 72f
                                val matrix = Matrix(scale, 0f, 0f, scale, 0f, 0f)
                                val pixmap = page.toPixmap(
                                    matrix, ColorSpace.DeviceRGB, true
                                )
                                try {
                                    val bitmap = Bitmap.createBitmap(
                                        pixmap.width, pixmap.height,
                                        Bitmap.Config.ARGB_8888
                                    )
                                    bitmap.copyPixelsFromBuffer(
                                        ByteBuffer.wrap(pixmap.samples)
                                    )
                                    renderedPages[i] = bitmap
                                } finally {
                                    pixmap.destroy()
                                }
                            } finally {
                                page.destroy()
                            }
                        } finally {
                            doc.destroy()
                        }
                    } catch (e: Exception) {
                        Log.e("PdfViewer", "Failed to render page $i", e)
                    }
                }
            }
        }
        if (renderedPages.size > MAX_CACHED_PAGES) {
            val visible = (start..end).toSet()
            val toEvict = renderedPages.keys
                .filter { it !in visible }
                .sorted()
                .take(renderedPages.size - MAX_CACHED_PAGES)
            toEvict.forEach { renderedPages.remove(it)?.recycle() }
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
    ) {
        if (pageCount > 0) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            ) {
                itemsIndexed(Array(pageCount) { it }.toList()) { index, _ ->
                    val bitmap = renderedPages[index]
                    if (bitmap != null) {
                        val pageContentScale = when (pdfPreferences.pageFitMode) {
                            PageFitMode.FIT_WIDTH -> ContentScale.FillWidth
                            PageFitMode.FIT_PAGE -> ContentScale.Fit
                            PageFitMode.ACTUAL_SIZE -> ContentScale.None
                        }
                        val pageColorFilter = if (pdfPreferences.nightMode) {
                            ColorFilter.colorMatrix(invertColorMatrix)
                        } else null
                        val pageBg = if (pdfPreferences.nightMode) {
                            androidx.compose.ui.graphics.Color(0xFF1A1A1A)
                        } else {
                            androidx.compose.ui.graphics.Color.White
                        }
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page ${index + 1}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(pageBg)
                                .clearAndSetSemantics { },
                            contentScale = pageContentScale,
                            colorFilter = pageColorFilter,
                        )
                    } else {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp),
                        )
                    }
                }
            }

            Text(
                text = "Page $currentPage of $pageCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
