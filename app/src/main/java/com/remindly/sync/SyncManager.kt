package com.remindly.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remindly.auth.AuthManager
import com.remindly.data.repo.ReminderRepository
import com.remindly.data.repo.SharedListRepository
import com.remindly.data.remote.FirestoreDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class SyncManager @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val authManager: AuthManager,
    private val firestoreDataSource: FirestoreDataSource,
    private val sharedListRepository: SharedListRepository,
    private val reminderRepository: ReminderRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val currentUser = authManager.currentUser ?: return Result.failure()

        try {
            // 1. Sync down Shared Lists
            val remoteLists = firestoreDataSource.getSharedListsForUser(currentUser.uid)
            for (list in remoteLists) {
                sharedListRepository.saveLocal(list)
            }

            // 2. Sync up Reminders in Shared Lists
            // Note: In Phase 5, we would fetch all local reminders where listId != null
            // For now, this is a scaffold.
            
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
    }
}
