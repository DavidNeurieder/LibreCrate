package com.librecrate.app.ui.viewer
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.librecrate.app.LibreCrateApplication
import com.librecrate.app.data.model.Document
import com.librecrate.app.util.ErrorLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File


@Composable
fun NoteEditor(
    document: Document,
    filePath: String,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as LibreCrateApplication
    val vault = app.vaultRepository
    val parser = remember { Parser.builder().build() }
    val htmlRenderer = remember { HtmlRenderer.builder().build() }
    var text by remember(document.id) { mutableStateOf("") }
    var wordCount by remember { mutableIntStateOf(0) }
    var charCount by remember { mutableIntStateOf(0) }
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(document.id, filePath) {
        val content = withContext(Dispatchers.IO) {
            try {
                File(filePath).readText()
            } catch (e: Exception) {
                ErrorLogger.logWarning(context, "NoteEditor", "Failed to read note file", e)
                vault.exportDocumentFile(document.id)?.decodeToString() ?: ""
            }
        }
        text = content
        loaded = true
        updateCounts(content) { w, c -> wordCount = w; charCount = c }
    }
    LaunchedEffect(text) {
        if (!loaded) return@LaunchedEffect
        saveJob?.cancel()
        saveJob = launch {
            delay(1000L)
            saveNoteInternal(app, vault, document, text)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatToolbarButton(icon = Icons.Filled.FormatBold, contentDescription = "Bold", onClick = { text = "$text****" })
            FormatToolbarButton(icon = Icons.Filled.FormatItalic, contentDescription = "Italic", onClick = { text = "$text**" })
            FormatToolbarButton(icon = Icons.Filled.Title, contentDescription = "Heading", onClick = { text = "$text\n# " })
            FormatToolbarButton(icon = Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Bullet list", onClick = { text = "$text\n- " })
            Spacer(Modifier.weight(1f))
            Text("$wordCount words | $charCount chars", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Divider()
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                updateCounts(newText) { w, c -> wordCount = w; charCount = c }
            },
            modifier = Modifier.fillMaxWidth().weight(0.5f).padding(8.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        )
        Divider()
        Box(modifier = Modifier.fillMaxWidth().weight(0.5f).padding(8.dp)) {
            val html = remember(text, parser, htmlRenderer) {
                try { val node: Node = parser.parse(text); htmlRenderer.render(node) } catch (e: Exception) { ErrorLogger.logWarning(context, "NoteEditor", "Preview render failed", e); "<p>Preview error</p>" }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true }
                    }
                },
                update = { webView ->
                    val styledHtml = """
                        <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>body { font-family: sans-serif; padding: 8px; line-height: 1.6; } img { max-width: 100%; } pre { background: #f0f0f0; padding: 8px; overflow-x: auto; } code { background: #f0f0f0; padding: 2px 4px; }</style>
                        </head><body>$html</body></html>
                    """.trimIndent()
                    webView.loadDataWithBaseURL("https://librecrate.local/", styledHtml, "text/html", "UTF-8", null)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Divider()
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
            Button(
                onClick = {
                    saveJob?.cancel()
                    scope.launch { saveNoteInternal(app, vault, document, text); onSaved() }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) { Text("Save") }
        }
    }
}
@Composable
private fun FormatToolbarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp)) }
}
private suspend fun saveNoteInternal(
    app: LibreCrateApplication,
    vault: com.librecrate.app.data.vault.VaultRepository,
    document: Document,
    content: String,
) {
    withContext(Dispatchers.IO) {
        vault.importDocument(
            id = document.id,
            title = document.title,
            fileData = content.encodeToByteArray(),
            mimeType = document.mimeType,
            author = document.author,
            description = content.take(200),
            textContent = content,
        )
    }
}
private fun updateCounts(content: String, onResult: (Int, Int) -> Unit) {
    val words = content.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
    onResult(words, content.length)
}
