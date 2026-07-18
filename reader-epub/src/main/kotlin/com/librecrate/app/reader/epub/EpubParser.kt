package com.librecrate.app.reader.epub

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class EpubParseResult(
    val title: String,
    val author: String,
    val coverPath: String?,
    val spineItems: List<String>,
    val opfPath: String,
)

object EpubParser {

    fun parse(file: File): EpubParseResult {
        val zip = ZipFile(file)
        try {
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: throw IllegalArgumentException("Missing META-INF/container.xml in EPUB")
            val containerXml = zip.getInputStream(containerEntry).readBytes()
            val opfPath = parseContainerXml(containerXml)
                ?: throw IllegalArgumentException("No OPF path found in container.xml")

            val opfEntry = zip.getEntry(opfPath)
                ?: throw IllegalArgumentException("OPF file not found: $opfPath")
            val opfData = zip.getInputStream(opfEntry).readBytes()

            val (title, author, coverPath, spineItems) = parseOpf(opfData)
            return EpubParseResult(title, author, coverPath, spineItems, opfPath)
        } finally {
            zip.close()
        }
    }

    fun readSpineContent(file: File, opfDir: String, spineItems: List<String>): String? {
        val zip = ZipFile(file)
        try {
            return buildString {
                for ((index, item) in spineItems.withIndex()) {
                    val href = if (opfDir.isNotEmpty() && !item.startsWith("/")) "$opfDir$item" else item
                    val entry = zip.getEntry(href) ?: continue
                    val htmlData = zip.getInputStream(entry).readBytes()
                    val text = htmlData.decodeToString()
                    val stripped = text.replace(Regex("<[^>]*>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (stripped.isNotEmpty()) {
                        appendLine(stripped)
                    }
                    append("[SECTION=${index + 1}]")
                }
            }.takeIf { it.isNotBlank() }
        } finally {
            zip.close()
        }
    }

    fun resolveOpfDir(opfPath: String): String {
        return opfPath.substringBeforeLast('/', "").let { if (it.isNotEmpty()) "$it/" else "" }
    }

    private fun parseContainerXml(data: ByteArray): String? {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(data.inputStream())
        val root = doc.documentElement
        val rootfiles = root.getElementsByTagName("rootfile")
        if (rootfiles.length > 0) {
            return rootfiles.item(0).attributes.getNamedItem("full-path")?.textContent
        }
        return null
    }

    private data class OpfResult(
        val title: String,
        val author: String,
        val coverPath: String?,
        val spineItems: List<String>,
    )

    private fun parseOpf(data: ByteArray): OpfResult {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(data.inputStream())

        val title = doc.getElementsByTagName("dc:title")
            ?.item(0)
            ?.textContent
            ?: ""

        val author = doc.getElementsByTagName("dc:creator")
            ?.item(0)
            ?.textContent
            ?: ""

        val manifest = doc.getElementsByTagName("manifest")?.item(0)
        val idToHref = mutableMapOf<String, String>()
        val idToMediaType = mutableMapOf<String, String>()
        if (manifest != null) {
            val items = manifest.childNodes
            for (i in 0 until items.length) {
                val item = items.item(i)
                if (item.nodeName == "item") {
                    val attrs = item.attributes
                    val id = attrs.getNamedItem("id")?.textContent ?: continue
                    val href = attrs.getNamedItem("href")?.textContent ?: continue
                    val mediaType = attrs.getNamedItem("media-type")?.textContent ?: ""
                    idToHref[id] = href
                    idToMediaType[id] = mediaType
                }
            }
        }

        var coverPath: String? = null
        val metaNodes = doc.getElementsByTagName("meta")
        for (i in 0 until metaNodes.length) {
            val meta = metaNodes.item(i)
            val attrs = meta.attributes
            val name = attrs.getNamedItem("name")?.textContent
            val content = attrs.getNamedItem("content")?.textContent
            if (name == "cover" && content != null) {
                coverPath = idToHref[content]
            }
        }

        if (coverPath == null) {
            for ((id, mediaType) in idToMediaType) {
                if (mediaType.startsWith("image/")) {
                    coverPath = idToHref[id]
                    break
                }
            }
        }

        val spineItems = mutableListOf<String>()
        val spine = doc.getElementsByTagName("spine")?.item(0)
        if (spine != null) {
            val refs = spine.childNodes
            for (i in 0 until refs.length) {
                val ref = refs.item(i)
                if (ref.nodeName == "itemref") {
                    val idref = ref.attributes.getNamedItem("idref")?.textContent ?: continue
                    val href = idToHref[idref]
                    if (href != null) {
                        spineItems.add(href)
                    }
                }
            }
        }

        return OpfResult(title, author, coverPath, spineItems)
    }
}
