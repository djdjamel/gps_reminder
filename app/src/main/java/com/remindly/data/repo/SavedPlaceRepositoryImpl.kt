package com.remindly.data.repo

import com.remindly.data.db.dao.SavedPlaceDao
import com.remindly.data.db.entity.SavedPlaceEntity
import com.remindly.domain.model.SavedPlace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedPlaceRepositoryImpl @Inject constructor(
    private val savedPlaceDao: SavedPlaceDao
) : SavedPlaceRepository {

    override fun observeAll(): Flow<List<SavedPlace>> {
        return savedPlaceDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun save(place: SavedPlace): Long {
        return savedPlaceDao.insert(place.toEntity())
    }

    override suspend fun delete(id: Long) {
        savedPlaceDao.delete(id)
    }

    private fun SavedPlaceEntity.toDomain() = SavedPlace(
        id = id,
        name = name,
        latitude = lat,
        longitude = lng,
        radiusMeters = defaultRadiusM
    )

    private fun SavedPlace.toEntity() = SavedPlaceEntity(
        id = id,
        name = name,
        lat = latitude,
        lng = longitude,
        defaultRadiusM = radiusMeters
    )
}
