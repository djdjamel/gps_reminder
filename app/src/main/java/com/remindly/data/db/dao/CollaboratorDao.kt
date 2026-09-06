package com.remindly.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.remindly.data.db.entity.CollaboratorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollaboratorDao {
    @Query("SELECT * FROM collaborators ORDER BY name ASC")
    fun getAll(): Flow<List<CollaboratorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(collaborator: CollaboratorEntity): Long

    @Query("DELETE FROM collaborators WHERE id = :id")
    suspend fun delete(id: Long)
}
