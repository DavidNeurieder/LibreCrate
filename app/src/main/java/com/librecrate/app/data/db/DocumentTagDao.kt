package com.librecrate.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.librecrate.app.data.model.DocumentTag
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(documentTag: DocumentTag)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(documentTag: DocumentTag)

    @Query("DELETE FROM document_tags WHERE document_id = :documentId AND tag_id = :tagId")
    suspend fun delete(documentId: String, tagId: String)

    @Query("DELETE FROM document_tags WHERE document_id = :documentId")
    suspend fun deleteAllForDocument(documentId: String)

    @Query("SELECT document_id FROM document_tags WHERE tag_id = :tagId")
    fun getDocumentIdsForTag(tagId: String): Flow<List<String>>
}
