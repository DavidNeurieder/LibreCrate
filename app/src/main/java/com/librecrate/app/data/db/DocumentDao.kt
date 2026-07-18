package com.librecrate.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.librecrate.app.data.model.Document
import kotlinx.coroutines.flow.Flow

data class DocumentListItem(
    @PrimaryKey val id: String,
    val title: String = "",
    @ColumnInfo(name = "file_name") val fileName: String = "",
    @ColumnInfo(name = "mime_type") val mimeType: String = "",
    @ColumnInfo(name = "file_size") val fileSize: Long = 0,
    @ColumnInfo(name = "page_count") val pageCount: Int = 0,
    val author: String = "",
    val description: String = "",
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    @ColumnInfo(name = "imported_at") val importedAt: Long = 0,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long = 0,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "collection_id") val collectionId: String? = null,
    @ColumnInfo(name = "barcode_format") val barcodeFormat: String? = null,
    @ColumnInfo(name = "barcode_value") val barcodeValue: String? = null,
    @ColumnInfo(name = "current_page") val currentPage: Int = 0,
    @ColumnInfo(name = "reading_position") val readingPosition: String? = null,
)

data class SearchResultMatch(
    val snippet: String,
    val pageNumber: Int,
)

data class SearchResultItem(
    val id: String,
    val title: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    val author: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    val matches: List<SearchResultMatch>,
)

data class SearchResultWithOffsets(
    val id: String,
    val title: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "page_count") val pageCount: Int,
    val author: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "text_content") val textContent: String,
    @ColumnInfo(name = "highlight_content") val highlightContent: String,
)

@Dao
interface DocumentDao {
    @Query("SELECT id, title, file_name, mime_type, file_size, page_count, author, description, thumbnail_path, imported_at, last_opened_at, is_favorite, collection_id, barcode_format, barcode_value, current_page, reading_position FROM documents ORDER BY imported_at DESC")
    fun getDocumentList(): Flow<List<DocumentListItem>>

    @RawQuery(observedEntities = [Document::class])
    fun searchDocuments(query: SupportSQLiteQuery): Flow<List<DocumentListItem>>

    @RawQuery(observedEntities = [Document::class])
    fun searchDocumentsWithOffsets(query: SupportSQLiteQuery): Flow<List<SearchResultWithOffsets>>

    @Query("SELECT * FROM documents ORDER BY imported_at DESC")
    fun getAllDocuments(): Flow<List<Document>>

    @Query("SELECT * FROM documents ORDER BY imported_at DESC")
    suspend fun getAllDocumentsOnce(): List<Document>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocumentById(id: String): Document?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: Document)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(document: Document)

    @Update
    suspend fun update(document: Document)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    @Query("SELECT * FROM documents WHERE mime_type LIKE :mimeType ORDER BY imported_at DESC")
    fun getDocumentsByType(mimeType: String): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE is_favorite = 1 ORDER BY imported_at DESC")
    fun getFavoriteDocuments(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE last_opened_at > :since ORDER BY last_opened_at DESC")
    fun getRecentDocuments(since: Long): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE collection_id = :collectionId ORDER BY imported_at DESC")
    fun getDocumentsByCollection(collectionId: String): Flow<List<Document>>
}
