package com.remindly.data.repo

import com.remindly.domain.model.SharedList
import kotlinx.coroutines.flow.Flow

interface SharedListRepository {
    fun observeAll(): Flow<List<SharedList>>
    suspend fun saveLocal(list: SharedList)
    suspend fun deleteLocal(id: String)
}
