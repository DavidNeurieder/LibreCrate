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

                renderFields(passData.primaryFields, fgColor, labelColor)
                Spacer(Modifier.height(8.dp))
                renderFields(passData.secondaryFields, fgColor, labelColor)
                Spacer(Modifier.height(8.dp))
                renderFields(passData.auxiliaryFields, fgColor, labelColor)
            }
        }

        Spacer(Modifier.height(24.dp))

        if (passData.barcodeFormat != null && passData.barcodeValue != null) {
            BarcodeImage(
                format = passData.barcodeFormat,
                value = passData.barcodeValue,
                modifier = Modifier.fillMaxWidth(),
            )
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

private data class PassData(
    val description: String?,
    val organizationName: String?,
    val logoText: String?,
    val foregroundColor: String?,
    val backgroundColor: String?,
    val labelColor: String?,
    val primaryFields: List<PassField>,
    val secondaryFields: List<PassField>,
    val auxiliaryFields: List<PassField>,
    val backFields: List<PassField>,
    val barcodeFormat: String?,
    val barcodeValue: String?,
    val logoBitmap: Bitmap?,
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
            loadImage(zip, "thumbnail.png")
            loadImage(zip, "background.png")

            val barcode = json.optJSONObject("barcode")
                ?: json.optJSONObject("barCode")

            return PassData(
                description = json.optString("description").takeIf { it.isNotEmpty() },
                organizationName = json.optString("organizationName").takeIf { it.isNotEmpty() },
                logoText = json.optString("logoText").takeIf { it.isNotEmpty() },
                foregroundColor = json.optString("foregroundColor").takeIf { it.isNotEmpty() },
                backgroundColor = json.optString("backgroundColor").takeIf { it.isNotEmpty() },
                labelColor = json.optString("labelColor").takeIf { it.isNotEmpty() },
                primaryFields = parseFields(json.optJSONArray("primaryFields")),
                secondaryFields = parseFields(json.optJSONArray("secondaryFields")),
                auxiliaryFields = parseFields(json.optJSONArray("auxiliaryFields")),
                backFields = parseFields(json.optJSONArray("backFields")),
                barcodeFormat = barcode?.optString("format")?.takeIf { it.isNotEmpty() },
                barcodeValue = barcode?.optString("message")?.takeIf { it.isNotEmpty() },
                logoBitmap = logoBitmap,
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

private fun parseColorString(hex: String): Color {
    return try {
        val colorString = hex.removePrefix("#")
        val colorLong = colorString.toLong(16)
        Color(
            alpha = if (colorString.length == 8) ((colorLong shr 24) and 0xFF) / 255f else 1f,
            red = ((colorLong shr 16) and 0xFF) / 255f,
            green = ((colorLong shr 8) and 0xFF) / 255f,
            blue = (colorLong and 0xFF) / 255f,
        )
    } catch (e: Exception) {
        Color.Unspecified
    }
}
