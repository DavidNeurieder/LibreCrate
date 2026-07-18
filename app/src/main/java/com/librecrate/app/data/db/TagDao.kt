package com.librecrate.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.librecrate.app.data.model.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTagById(id: String): Tag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: Tag)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(tag: Tag)

    @Update
    suspend fun update(tag: Tag)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT t.* FROM tags t INNER JOIN document_tags dt ON t.id = dt.tag_id WHERE dt.document_id = :documentId ORDER BY t.name ASC")
    suspend fun getTagsForDocument(documentId: String): List<Tag>
}
