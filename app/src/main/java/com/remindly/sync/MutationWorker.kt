package com.remindly.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.remindly.auth.AuthManager
import com.remindly.data.db.dao.PendingSyncDao
import com.remindly.data.repo.ReminderRepository
import com.remindly.data.remote.FirestoreDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MutationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val authManager: AuthManager,
    private val pendingSyncDao: PendingSyncDao,
    private val firestoreDataSource: FirestoreDataSource,
    private val reminderRepository: ReminderRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val currentUser = authManager.currentUser ?: return Result.failure()

        try {
            val pendingMutations = pendingSyncDao.getAllPending()
            
            for (mutation in pendingMutations) {
                // scaffold: handle operations based on type (CREATE, UPDATE, DELETE)
                when (mutation.operationType) {
                    "CREATE", "UPDATE" -> {
                        val reminder = reminderRepository.getById(mutation.reminderId)
                        if (reminder != null) {
                            firestoreDataSource.saveReminderInSharedList(
                                userId = currentUser.uid,
                                listId = mutation.listId,
                                reminder = reminder
                            )
                        }
                    }
                    "DELETE" -> {
                        // TODO: Implement delete on Firestore
                    }
                }
                
                // Si succès, on supprime de la queue
                pendingSyncDao.delete(mutation.id)
            }

            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // Backoff exponentiel natif de WorkManager
            return Result.retry()
        }
    }
}
