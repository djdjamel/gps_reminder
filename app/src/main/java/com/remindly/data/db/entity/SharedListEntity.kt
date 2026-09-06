package com.remindly.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_lists")
data class SharedListEntity(
    @PrimaryKey val listId: String,
    val name: String,
    val ownerId: String,
    val myRole: String, // OWNER or GUEST
    val color: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
