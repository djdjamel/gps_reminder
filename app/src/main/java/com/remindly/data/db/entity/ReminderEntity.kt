package com.remindly.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.RepeatRule
import com.remindly.domain.model.SyncState
import com.remindly.domain.model.TriggerType

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null,
    val listId: String? = null,
    val authorId: String? = null,
    val authorName: String? = null,
    val text: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val status: ReminderStatus = ReminderStatus.ACTIVE,
    val pinned: Boolean = false,
    val colorTag: Int? = null,
    val triggerType: TriggerType = TriggerType.NONE,
    val isRepeating: Boolean = false,
    val lastFiredAt: Long? = null,
    
    // TIME trigger fields
    val triggerTimeMillis: Long? = null,
    val repeatRule: RepeatRule? = null,
    val repeatIntervalMin: Int? = null,
    val repeatDaysMask: Int? = null,
    
    // PLACE trigger fields
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    val placeRadiusM: Float? = null,
    val savedPlaceId: Long? = null,
    val placeLabel: String? = null,
    
    val syncState: SyncState = SyncState.SYNCED,
    val sortOrder: Int = 0
)
