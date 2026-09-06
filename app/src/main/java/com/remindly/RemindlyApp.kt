package com.remindly

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.remindly.notify.NotificationChannels
import com.remindly.sync.SharedReminderSyncManager
import com.remindly.sync.SharedReminderSyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class RemindlyApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sharedReminderSyncManager: SharedReminderSyncManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createChannels(this)

        // Ingestion temps réel des rappels entrants tant que le process est vivant.
        sharedReminderSyncManager.start()

        // Filet de livraison en arrière-plan (app fermée) + rattrapage immédiat au lancement.
        scheduleSharedReminderSync()
    }

    private fun scheduleSharedReminderSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<SharedReminderSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            "shared_reminder_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        val oneShot = OneTimeWorkRequestBuilder<SharedReminderSyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            "shared_reminder_sync_now",
            ExistingWorkPolicy.REPLACE,
            oneShot
        )
    }
}
