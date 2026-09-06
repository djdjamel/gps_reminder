package com.remindly.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.remindly.data.db.entity.SharedListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedListDao {
    @Query("SELECT * FROM shared_lists")
    fun observeAll(): Flow<List<SharedListEntity>>

    @Query("SELECT * FROM shared_lists WHERE listId = :id")
    suspend fun getById(id: String): SharedListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(list: SharedListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lists: List<SharedListEntity>)

    @Query("DELETE FROM shared_lists WHERE listId = :id")
    suspend fun delete(id: String)
}
