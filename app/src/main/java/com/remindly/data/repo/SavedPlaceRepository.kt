package com.remindly.data.repo

import com.remindly.domain.model.SavedPlace
import kotlinx.coroutines.flow.Flow

interface SavedPlaceRepository {
    fun observeAll(): Flow<List<SavedPlace>>
    suspend fun save(place: SavedPlace): Long
    suspend fun delete(id: Long)
}
