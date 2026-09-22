package com.remindly.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.remindly.data.db.entity.ReminderEntity
import com.remindly.domain.model.ReminderStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @androidx.room.Transaction
    @Query("SELECT * FROM reminders WHERE listId IS NULL AND status = 'ACTIVE' ORDER BY sortOrder ASC, createdAt DESC")
    fun observePersonalActiveWithAttachments(): Flow<List<com.remindly.data.db.entity.ReminderWithAttachments>>

    @androidx.room.Transaction
    @Query("SELECT * FROM reminders WHERE listId IS NULL AND status != 'ARCHIVED' ORDER BY sortOrder ASC, createdAt DESC")
    fun observePersonalNonArchivedWithAttachments(): Flow<List<com.remindly.data.db.entity.ReminderWithAttachments>>

    @androidx.room.Transaction
    @Query("SELECT * FROM reminders WHERE id = :id")
    fun observeByIdWithAttachments(id: Long): Flow<com.remindly.data.db.entity.ReminderWithAttachments?>

    @Query("SELECT * FROM reminders WHERE id = :id")
    fun observeById(id: Long): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @androidx.room.Transaction
    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getByIdWithAttachments(id: Long): com.remindly.data.db.entity.ReminderWithAttachments?

    @Query("SELECT * FROM reminders WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): ReminderEntity?

    // Tous les rappels entrants ingérés depuis le Cloud (remoteId non nul).
    @Query("SELECT * FROM reminders WHERE remoteId IS NOT NULL")
    suspend fun getAllIncoming(): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: ReminderStatus)

    @Query("UPDATE reminders SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE reminders SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)
}
