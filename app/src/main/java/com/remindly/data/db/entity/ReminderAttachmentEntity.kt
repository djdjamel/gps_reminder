package com.remindly.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.remindly.domain.model.AttachmentType

@Entity(
    tableName = "reminder_attachments",
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("reminderId")]
)
data class ReminderAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val type: AttachmentType,
    val localPath: String,
    val mimeType: String,
    val durationMs: Long? = null,
    val orderIndex: Int = 0
)
