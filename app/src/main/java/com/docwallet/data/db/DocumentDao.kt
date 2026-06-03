package com.docwallet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.docwallet.data.model.Document
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY imported_at DESC")
    fun getAllDocuments(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocumentById(id: String): Document?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: Document)

    @Update
    suspend fun update(document: Document)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM documents WHERE mime_type LIKE :mimeType ORDER BY imported_at DESC")
    fun getDocumentsByType(mimeType: String): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE is_favorite = 1 ORDER BY imported_at DESC")
    fun getFavoriteDocuments(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE last_opened_at > :since ORDER BY last_opened_at DESC")
    fun getRecentDocuments(since: Long): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE collection_id = :collectionId ORDER BY imported_at DESC")
    fun getDocumentsByCollection(collectionId: String): Flow<List<Document>>
}
