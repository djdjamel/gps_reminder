package com.remindly.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_sync")
data class PendingSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    val listId: String,
    val operationType: String, // "CREATE", "UPDATE", "DELETE"
    val timestamp: Long = System.currentTimeMillis()
)
