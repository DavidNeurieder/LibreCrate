package com.docwallet.ui.viewer

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubReader(file: File) {
    val spineItems = remember(file) { parseSpineItems(file) }
    var currentIndex by remember { mutableIntStateOf(0) }
    val htmlContent by remember(currentIndex, file, spineItems) {
        mutableStateOf(loadSpineContent(file, spineItems, currentIndex))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                }
            },
            update = { webView ->
                htmlContent?.let {
                    val styledHtml = """
                        <html>
                        <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            body { font-family: sans-serif; padding: 16px; line-height: 1.6; color: #1a1a1a; }
                            img { max-width: 100%%; height: auto; }
                        </style>
                        </head>
                        <body>$it</body>
                        </html>
                    """.trimIndent()
                    webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 56.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { if (currentIndex > 0) currentIndex-- },
                enabled = currentIndex > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous")
                Spacer(Modifier.width(4.dp))
                Text("Previous")
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = "${currentIndex + 1} / ${spineItems.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { if (currentIndex < spineItems.size - 1) currentIndex++ },
                enabled = currentIndex < spineItems.size - 1,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text("Next")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
            }
        }
    }
}

internal data class SpineItem(
    val id: String?,
    val href: String,
)

internal fun parseSpineItems(epubFile: File): List<SpineItem> {
    try {
        ZipFile(epubFile).use { zip ->
            val opfEntry = findOpfEntry(zip) ?: return emptyList()
            val opfXml = zip.getInputStream(opfEntry).readBytes().decodeToString()

            val dbFactory = DocumentBuilderFactory.newInstance()
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(opfXml.byteInputStream())

            val basePath = opfEntry.name.substringBeforeLast("/")

            val manifest = mutableMapOf<String, String>()
            val manifestItems = doc.getElementsByTagName("item")
            for (i in 0 until manifestItems.length) {
                val item = manifestItems.item(i)
                val itemId = item.attributes.getNamedItem("id")?.textContent ?: continue
                val href = item.attributes.getNamedItem("href")?.textContent ?: continue
                manifest[itemId] = if (basePath.isEmpty()) href else "$basePath/$href"
            }

            val spineItems = mutableListOf<SpineItem>()
            val spineRefs = doc.getElementsByTagName("itemref")
            for (i in 0 until spineRefs.length) {
                val ref = spineRefs.item(i)
                val idref = ref.attributes.getNamedItem("idref")?.textContent ?: continue
                val href = manifest[idref] ?: continue
                spineItems.add(SpineItem(id = idref, href = href))
            }

            return spineItems
        }
        } catch (e: Exception) {
            Log.e("EpubReader", "parseSpineItems error", e)
            return emptyList()
        }
}

private fun findOpfEntry(zip: ZipFile): java.util.zip.ZipEntry? {
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.name.endsWith(".opf", ignoreCase = true)) return entry
    }

    val containerEntry = zip.getEntry("META-INF/container.xml")
    if (containerEntry != null) {
        try {
            val containerXml = zip.getInputStream(containerEntry).readBytes().decodeToString()
            val dbFactory = DocumentBuilderFactory.newInstance()
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(containerXml.byteInputStream())
            val rootFiles = doc.getElementsByTagName("rootfile")
            if (rootFiles.length > 0) {
                val rootFile = rootFiles.item(0)
                val path = rootFile.attributes.getNamedItem("full-path")?.textContent ?: return null
                return zip.getEntry(path)
            }
        } catch (_: Exception) {
        }
    }

    return null
}

internal fun loadSpineContent(epubFile: File, spineItems: List<SpineItem>, index: Int): String? {
    if (index < 0 || index >= spineItems.size) return null
    try {
        ZipFile(epubFile).use { zip ->
            val entry = zip.getEntry(spineItems[index].href) ?: return null
            val html = zip.getInputStream(entry).readBytes().decodeToString()
            val body = extractBody(html)
            return body ?: html
        }
    } catch (e: Exception) {
        Log.e("EpubReader", "loadSpineContent error for index=$index href=${spineItems.getOrNull(index)?.href}", e)
        return null
    }
}

internal fun extractBody(html: String): String? {
    val bodyStart = html.indexOf("<body", ignoreCase = true)
    if (bodyStart == -1) return null
    val tagEnd = html.indexOf('>', bodyStart)
    if (tagEnd == -1) return null
    val contentStart = tagEnd + 1
    val bodyEnd = html.indexOf("</body>", ignoreCase = true, startIndex = contentStart)
    if (bodyEnd == -1) return null
    return html.substring(contentStart, bodyEnd)
}
