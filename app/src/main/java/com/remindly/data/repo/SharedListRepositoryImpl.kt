package com.remindly.data.repo

import com.remindly.data.db.dao.SharedListDao
import com.remindly.data.db.entity.SharedListEntity
import com.remindly.domain.model.SharedList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SharedListRepositoryImpl @Inject constructor(
    private val sharedListDao: SharedListDao
) : SharedListRepository {

    override fun observeAll(): Flow<List<SharedList>> {
        return sharedListDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun saveLocal(list: SharedList) {
        sharedListDao.insert(list.toEntity())
    }

    override suspend fun deleteLocal(id: String) {
        sharedListDao.delete(id)
    }

    private fun SharedListEntity.toDomain() = SharedList(
        id = listId,
        name = name,
        ownerId = ownerId,
        color = color?.toIntOrNull()
    )

    private fun SharedList.toEntity() = SharedListEntity(
        listId = id,
        name = name,
        ownerId = ownerId,
        myRole = "OWNER",
        color = color?.toString()
    )
}
