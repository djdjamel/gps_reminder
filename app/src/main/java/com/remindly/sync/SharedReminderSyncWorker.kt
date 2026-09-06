package com.remindly.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Filet de livraison en arrière-plan (plan Spark : pas de push/FCM via Cloud Functions).
 * Descend les `shared_reminders (receiverId == moi)`, les ingère dans Room et (re)planifie.
 * Planifié en périodique + one-shot au lancement par [com.remindly.RemindlyApp].
 */
@HiltWorker
class SharedReminderSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: SharedReminderSyncManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            syncManager.syncOnce()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
