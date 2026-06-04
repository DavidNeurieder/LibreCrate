package com.docwallet.ui.viewer

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.docwallet.data.model.Document as DocWalletDocument
import java.io.File
import java.nio.ByteBuffer

@Composable
fun PdfViewer(
    file: File,
    document: DocWalletDocument,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
) {
    val pageCount = remember(document.pageCount) {
        if (document.pageCount > 0) document.pageCount
        else try {
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

    val renderedPages = remember { mutableStateMapOf<Int, Bitmap>() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
    )
    var currentPage by remember { mutableIntStateOf(1) }

    LaunchedEffect(listState.firstVisibleItemIndex) {
        currentPage = listState.firstVisibleItemIndex + 1
        onPageChanged(currentPage)
        val start = maxOf(0, listState.firstVisibleItemIndex - 1)
        val end = minOf(pageCount - 1, listState.firstVisibleItemIndex + 3)
        for (i in start..end) {
            if (i !in renderedPages) {
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
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(androidx.compose.ui.graphics.Color.White),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Page ${index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
    }
}
