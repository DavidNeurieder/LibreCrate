package com.librecrate.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.librecrate.app.data.model.Collection
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY sort_order ASC")
    fun getAllCollections(): Flow<List<Collection>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getCollectionById(id: String): Collection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(collection: Collection)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(collection: Collection)

    @Update
    suspend fun update(collection: Collection)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM documents WHERE collection_id = :collectionId")
    suspend fun getDocumentCount(collectionId: String): Int

    @Query("SELECT * FROM collections WHERE parent_id = :parentId ORDER BY sort_order ASC")
    fun getChildCollections(parentId: String): Flow<List<Collection>>
}
