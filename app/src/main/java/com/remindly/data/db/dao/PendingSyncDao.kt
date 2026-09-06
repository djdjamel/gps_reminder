package com.remindly.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.remindly.data.db.entity.PendingSyncEntity

@Dao
interface PendingSyncDao {
    @Insert
    suspend fun insert(pendingSync: PendingSyncEntity)

    @Query("SELECT * FROM pending_sync ORDER BY timestamp ASC")
    suspend fun getAllPending(): List<PendingSyncEntity>

    @Query("DELETE FROM pending_sync WHERE id = :id")
    suspend fun delete(id: Long)
    
    @Query("DELETE FROM pending_sync WHERE reminderId = :reminderId")
    suspend fun deleteByReminderId(reminderId: Long)
}
