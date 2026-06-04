package com.docwallet.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docwallet.ui.common.BarcodeImage
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

@Composable
fun PkPassViewer(file: File) {
    val passData = remember(file) { parsePkPass(file) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (passData == null) {
            Text(
                text = "Unable to parse pass",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }

        val bgColor = passData.backgroundColor?.let { parseColorString(it) }
        val fgColor = passData.foregroundColor?.let { parseColorString(it) }
        val labelColor = passData.labelColor?.let { parseColorString(it) }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = bgColor ?: MaterialTheme.colorScheme.surfaceVariant,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                passData.logoBitmap?.let { logo ->
                    Image(
                        bitmap = logo.asImageBitmap(),
                        contentDescription = "Logo",
                        modifier = Modifier
                            .height(48.dp)
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }

                passData.organizationName?.let { org ->
                    Text(
                        text = org,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = fgColor ?: MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(12.dp))

                passData.description?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))

                passData.thumbnailBitmap?.let { thumb ->
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = "Thumbnail",
                        modifier = Modifier
                            .height(48.dp)
                            .padding(bottom = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                }

                passData.stripBitmap?.let { strip ->
                    Image(
                        bitmap = strip.asImageBitmap(),
                        contentDescription = "Strip",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                renderFields(passData.headerFields, fgColor, labelColor)
                Spacer(Modifier.height(8.dp))
                renderFields(passData.primaryFields, fgColor, labelColor)
                Spacer(Modifier.height(8.dp))
                renderFields(passData.secondaryFields, fgColor, labelColor)
                Spacer(Modifier.height(8.dp))
                renderFields(passData.auxiliaryFields, fgColor, labelColor)
            }
        }

        Spacer(Modifier.height(24.dp))

        passData.relevantDate?.let { date ->
            Text(
                text = "Relevant date: $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }

        passData.expirationDate?.let { date ->
            Text(
                text = "Expires: $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
        }

        if (passData.barcodes.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            passData.barcodes.forEachIndexed { index, barcode ->
                if (index > 0) Spacer(Modifier.height(16.dp))
                BarcodeImage(
                    format = barcode.format,
                    value = barcode.message,
                    modifier = Modifier.fillMaxWidth(),
                )
                barcode.altText?.let { alt ->
                    Text(
                        text = alt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
        }

        if (passData.backFields.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Additional Information",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    passData.backFields.forEach { field ->
                        Text(
                            text = field.key,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = field.value,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun renderFields(
    fields: List<PassField>,
    fgColor: Color?,
    labelColor: Color?,
) {
    fields.forEach { field ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            field.label?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = field.value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = fgColor ?: MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private data class PassBarcode(
    val format: String,
    val message: String,
    val altText: String?,
)

private data class PassData(
    val description: String?,
    val organizationName: String?,
    val logoText: String?,
    val foregroundColor: String?,
    val backgroundColor: String?,
    val labelColor: String?,
    val headerFields: List<PassField>,
    val primaryFields: List<PassField>,
    val secondaryFields: List<PassField>,
    val auxiliaryFields: List<PassField>,
    val backFields: List<PassField>,
    val barcodes: List<PassBarcode>,
    val relevantDate: String?,
    val expirationDate: String?,
    val logoBitmap: Bitmap?,
    val thumbnailBitmap: Bitmap?,
    val stripBitmap: Bitmap?,
)

private data class PassField(
    val key: String,
    val label: String?,
    val value: String,
)

private fun parsePkPass(file: File): PassData? {
    try {
        ZipFile(file).use { zip ->
            val passEntry = zip.getEntry("pass.json") ?: return null
            val jsonText = zip.getInputStream(passEntry).readBytes().decodeToString()
            val json = JSONObject(jsonText)

            val logoBitmap = loadImage(zip, "logo.png")
                ?: loadImage(zip, "icon.png")
            val stripBitmap = loadImage(zip, "strip.png")
            val thumbnailBitmap = loadImage(zip, "thumbnail.png")

            val barcode = json.optJSONObject("barcode")
                ?: json.optJSONObject("barCode")
            val barcodesArray = json.optJSONArray("barcodes")
            val barcodes = mutableListOf<PassBarcode>()

            if (barcode != null) {
                val fmt = barcode.optString("format").takeIf { it.isNotEmpty() }
                val msg = barcode.optString("message").takeIf { it.isNotEmpty() }
                if (fmt != null && msg != null) {
                    barcodes.add(
                        PassBarcode(
                            format = fmt,
                            message = msg,
                            altText = barcode.optString("altText").takeIf { it.isNotEmpty() },
                        )
                    )
                }
            }

            if (barcodesArray != null) {
                for (i in 0 until barcodesArray.length()) {
                    val obj = barcodesArray.getJSONObject(i)
                    val fmt = obj.optString("format").takeIf { it.isNotEmpty() }
                    val msg = obj.optString("message").takeIf { it.isNotEmpty() }
                    if (fmt != null && msg != null) {
                        barcodes.add(
                            PassBarcode(
                                format = fmt,
                                message = msg,
                                altText = obj.optString("altText").takeIf { it.isNotEmpty() },
                            )
                        )
                    }
                }
            }

            return PassData(
                description = json.optString("description").takeIf { it.isNotEmpty() },
                organizationName = json.optString("organizationName").takeIf { it.isNotEmpty() },
                logoText = json.optString("logoText").takeIf { it.isNotEmpty() },
                foregroundColor = json.optString("foregroundColor").takeIf { it.isNotEmpty() },
                backgroundColor = json.optString("backgroundColor").takeIf { it.isNotEmpty() },
                labelColor = json.optString("labelColor").takeIf { it.isNotEmpty() },
                headerFields = parseFields(json.optJSONArray("headerFields")),
                primaryFields = parseFields(json.optJSONArray("primaryFields")),
                secondaryFields = parseFields(json.optJSONArray("secondaryFields")),
                auxiliaryFields = parseFields(json.optJSONArray("auxiliaryFields")),
                backFields = parseFields(json.optJSONArray("backFields")),
                barcodes = barcodes,
                relevantDate = json.optString("relevantDate").takeIf { it.isNotEmpty() },
                expirationDate = json.optString("expirationDate").takeIf { it.isNotEmpty() },
                logoBitmap = logoBitmap,
                thumbnailBitmap = thumbnailBitmap,
                stripBitmap = stripBitmap,
            )
        }
    } catch (e: Exception) {
        return null
    }
}

private fun parseFields(jsonArray: org.json.JSONArray?): List<PassField> {
    if (jsonArray == null) return emptyList()
    val fields = mutableListOf<PassField>()
    for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        fields.add(
            PassField(
                key = obj.optString("key", ""),
                label = obj.optString("label").takeIf { it.isNotEmpty() },
                value = obj.optString("value", ""),
            )
        )
    }
    return fields
}

private fun loadImage(zip: ZipFile, name: String): Bitmap? {
    val entry = zip.getEntry(name) ?: return null
    return zip.getInputStream(entry).use { stream ->
        BitmapFactory.decodeStream(stream)
    }
}

private fun parseColorString(colorStr: String): Color? {
    val trimmed = colorStr.trim()
    return try {
        if (trimmed.startsWith("rgb")) {
            val rgb = trimmed.removePrefix("rgba").removePrefix("rgb")
                .trimStart('(').trimEnd(')').split(",").map { it.trim() }
            val r = rgb[0].toFloatOrNull() ?: return null
            val g = rgb[1].toFloatOrNull() ?: return null
            val b = rgb[2].toFloatOrNull() ?: return null
            val a = rgb.getOrNull(3)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
            Color(
                red = r.coerceIn(0f, 255f) / 255f,
                green = g.coerceIn(0f, 255f) / 255f,
                blue = b.coerceIn(0f, 255f) / 255f,
                alpha = a,
            )
        } else {
            val hex = trimmed.removePrefix("#")
            val colorLong = hex.toLong(16)
            Color(
                alpha = if (hex.length == 8) ((colorLong shr 24) and 0xFF) / 255f else 1f,
                red = ((colorLong shr 16) and 0xFF) / 255f,
                green = ((colorLong shr 8) and 0xFF) / 255f,
                blue = (colorLong and 0xFF) / 255f,
            )
        }
    } catch (e: Exception) {
        null
    }
}
