package com.librecrate.app.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.librecrate.app.data.PageFitMode
import com.librecrate.app.data.PdfPreferences
import com.librecrate.app.data.model.Document as LibreCrateDocument
import com.librecrate.app.reader.pdf.PdfDocumentReader
import com.librecrate.app.util.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_CACHED_PAGES = 10
private const val DEFAULT_PAGE_ASPECT = 3f / 4f
private const val DOUBLE_TAP_THRESHOLD_MS = 300L

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
    file: java.io.File,
    document: LibreCrateDocument,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    pdfPreferences: PdfPreferences = PdfPreferences(),
) {
    val context = LocalContext.current
    val reader = remember { PdfDocumentReader(file.absolutePath) }

    DisposableEffect(Unit) {
        onDispose { reader.close() }
    }

    var pageCount by remember { mutableIntStateOf(document.pageCount) }
    val initialIndex = (initialPage - 1).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    var currentPage by remember { mutableIntStateOf(1) }

    val renderedPages = remember { mutableStateMapOf<Int, Bitmap>() }
    var pageAspectRatio by remember { mutableFloatStateOf(DEFAULT_PAGE_ASPECT) }

    val screenWidthPx = context.resources.displayMetrics.widthPixels

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var lastTapDownTime by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        if (pageCount <= 0) {
            pageCount = withContext(Dispatchers.IO) {
                try {
                    reader.pageCount
                } catch (e: Exception) {
                    ErrorLogger.logWarning(context, "PdfViewer", "Failed to load page count", e)
                    0
                }
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, pageCount) {
        currentPage = listState.firstVisibleItemIndex + 1
        onPageChanged(currentPage)
        if (pageCount <= 0) return@LaunchedEffect
        val start = maxOf(0, listState.firstVisibleItemIndex - 2)
        val end = minOf(pageCount - 1, listState.firstVisibleItemIndex + 5)
        for (i in start..end) {
            if (i !in renderedPages) {
                withContext(Dispatchers.IO) {
                    try {
                        val bitmap = reader.renderPageBitmap(i, targetWidthPx = screenWidthPx)
                        renderedPages[i] = bitmap
                        if (i == start) {
                            pageAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                        }
                    } catch (e: Exception) {
                        ErrorLogger.logWarning(context, "PdfViewer", "Failed to render page $i", e)
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

    Box(
        modifier = Modifier.fillMaxSize(),
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
                        transformOrigin = TransformOrigin(0f, 0f),
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            var isZooming = false
                            var initialSpan = 0f
                            var prevCentroid = Offset.Zero
                            var prevPanPos = Offset.Zero
                            var hasPrevPanPos = false

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { ch -> ch.pressed }

                                if (pressed.isEmpty()) {
                                    isZooming = false
                                    hasPrevPanPos = false
                                    break
                                }

                                if (pressed.size >= 2) {
                                    isZooming = true
                                    for (ch in event.changes) { ch.consume() }

                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val span = (p0 - p1).getDistance()
                                    val centroid = (p0 + p1) / 2f

                                    if (initialSpan == 0f) {
                                        initialSpan = span
                                        prevCentroid = centroid
                                    } else {
                                        val oldScale = scale
                                        val zoomDelta = span / initialSpan
                                        val newScale = (scale * zoomDelta).coerceIn(1f, 5f)
                                        val actualZoom = if (oldScale > 0f) newScale / oldScale else 1f
                                        offsetX = centroid.x * (1f - actualZoom) + offsetX * actualZoom
                                        scale = newScale
                                        initialSpan = span
                                        prevCentroid = centroid
                                    }
                                } else if (pressed.size == 1 && (isZooming || scale > 1f)) {
                                    for (ch in event.changes) { ch.consume() }
                                    val pos = pressed[0].position
                                    if (!hasPrevPanPos) {
                                        prevPanPos = pos
                                        hasPrevPanPos = true
                                    } else {
                                        val pan = pos - prevPanPos
                                        offsetX += pan.x
                                        if (pan.y != 0f) {
                                            listState.dispatchRawDelta(-pan.y / scale)
                                        }
                                        prevPanPos = pos
                                    }
                                } else if (pressed.size == 1 && scale == 1f) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapDownTime < DOUBLE_TAP_THRESHOLD_MS && lastTapDownTime > 0L) {
                                        scale = 2.5f
                                    }
                                    lastTapDownTime = now
                                }
                            }
                        }
                    },
            ) {
                items(pageCount, key = { it }) { index ->
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(pageAspectRatio)
                                .padding(vertical = 4.dp),
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
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
