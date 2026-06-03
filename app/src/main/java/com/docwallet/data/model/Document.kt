package com.docwallet.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "documents")
data class Document(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    @ColumnInfo(name = "file_name")
    val fileName: String = "",
    @ColumnInfo(name = "mime_type")
    val mimeType: String = "",
    @ColumnInfo(name = "file_path")
    val filePath: String = "",
    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,
    @ColumnInfo(name = "page_count")
    val pageCount: Int = 0,
    val author: String = "",
    val description: String = "",
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,
    @ColumnInfo(name = "imported_at")
    val importedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAt: Long = 0,
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
    @ColumnInfo(name = "collection_id")
    val collectionId: String? = null,
    @ColumnInfo(name = "encryption_iv")
    val encryptionIv: ByteArray? = null,
    @ColumnInfo(name = "text_content")
    val textContent: String? = null,
    @ColumnInfo(name = "barcode_format")
    val barcodeFormat: String? = null,
    @ColumnInfo(name = "barcode_value")
    val barcodeValue: String? = null,
)
