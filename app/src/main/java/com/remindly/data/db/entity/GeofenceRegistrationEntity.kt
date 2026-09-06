package com.remindly.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geofence_registrations")
data class GeofenceRegistrationEntity(
    @PrimaryKey val requestId: String,
    val reminderId: Long,
    val lat: Double,
    val lng: Double,
    val radiusM: Float,
    val registeredAt: Long = System.currentTimeMillis()
)
