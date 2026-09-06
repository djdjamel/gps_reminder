package com.remindly.data.repo

import com.remindly.data.db.entity.CollaboratorEntity
import kotlinx.coroutines.flow.Flow

interface CollaboratorRepository {
    fun getAll(): Flow<List<CollaboratorEntity>>
    suspend fun save(name: String, email: String)
    suspend fun delete(id: Long)
}
