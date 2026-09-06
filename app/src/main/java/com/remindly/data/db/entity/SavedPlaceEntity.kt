package com.remindly.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val defaultRadiusM: Float = 120f,
    val iconTag: String? = null,
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
