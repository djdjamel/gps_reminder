package com.remindly.domain.model

data class SavedPlace(
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 100f
)
