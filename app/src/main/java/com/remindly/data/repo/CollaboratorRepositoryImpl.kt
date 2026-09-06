package com.remindly.data.repo

import com.remindly.data.db.dao.CollaboratorDao
import com.remindly.data.db.entity.CollaboratorEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollaboratorRepositoryImpl @Inject constructor(
    private val collaboratorDao: CollaboratorDao
) : CollaboratorRepository {

    override fun getAll(): Flow<List<CollaboratorEntity>> {
        return collaboratorDao.getAll()
    }

    override suspend fun save(name: String, email: String) {
        val entity = CollaboratorEntity(name = name.trim(), email = email.trim().lowercase())
        collaboratorDao.insert(entity)
    }

    override suspend fun delete(id: Long) {
        collaboratorDao.delete(id)
    }
}
