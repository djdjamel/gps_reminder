package com.remindly.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.remindly.data.db.entity.ReminderAttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(attachment: ReminderAttachmentEntity): Long

    @Query("SELECT * FROM reminder_attachments WHERE reminderId = :reminderId ORDER BY orderIndex ASC")
    suspend fun getByReminderId(reminderId: Long): List<ReminderAttachmentEntity>

    @Query("DELETE FROM reminder_attachments WHERE id = :attachmentId")
    suspend fun delete(attachmentId: Long)
}
