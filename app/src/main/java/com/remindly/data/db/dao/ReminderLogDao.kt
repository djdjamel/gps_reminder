package com.remindly.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.remindly.data.db.entity.ReminderLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ReminderLogEntity): Long

    @Query("SELECT * FROM reminder_logs ORDER BY timestamp DESC LIMIT 1000")
    fun observeRecentLogs(): Flow<List<ReminderLogEntity>>

    @Query("SELECT * FROM reminder_logs WHERE reminderId = :reminderId ORDER BY timestamp DESC")
    fun observeLogsForReminder(reminderId: Long): Flow<List<ReminderLogEntity>>

    @Query("SELECT * FROM reminder_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLogsForExport(limit: Int = 2000): List<ReminderLogEntity>

    @Query("DELETE FROM reminder_logs")
    suspend fun clearAll()
}
