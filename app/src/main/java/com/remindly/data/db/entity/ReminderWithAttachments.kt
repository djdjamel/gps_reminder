package com.remindly.data.db.entity

import androidx.room.Embedded
import androidx.room.Relation

data class ReminderWithAttachments(
    @Embedded val reminder: ReminderEntity,
    
    @Relation(
        parentColumn = "id",
        entityColumn = "reminderId"
    )
    val attachments: List<ReminderAttachmentEntity>
)
