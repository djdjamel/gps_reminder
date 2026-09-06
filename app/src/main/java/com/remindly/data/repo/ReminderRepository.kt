package com.remindly.data.repo

import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import kotlinx.coroutines.flow.Flow

interface ReminderRepository {
    fun observePersonalActive(): Flow<List<Reminder>>
    fun observeById(id: Long): Flow<Reminder?>
    suspend fun getById(id: Long): Reminder?
    suspend fun save(reminder: Reminder): Long
    suspend fun setStatus(id: Long, status: ReminderStatus)
    suspend fun setPinned(id: Long, pinned: Boolean)
    suspend fun delete(id: Long)
    
    suspend fun addAttachment(reminderId: Long, attachment: com.remindly.domain.model.Attachment): Long
    suspend fun removeAttachment(attachmentId: Long, localPath: String)
    suspend fun updateSortOrder(id: Long, sortOrder: Int)
}
