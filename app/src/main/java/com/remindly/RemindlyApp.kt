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
import com.remindly.location.ReRegisterGeofencesWorker
import com.remindly.notify.NotificationChannels
import com.remindly.sync.SharedReminderSyncManager
import com.remindly.sync.SharedReminderSyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class RemindlyApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sharedReminderSyncManager: SharedReminderSyncManager

    @Inject
    lateinit var passiveLocationManager: com.remindly.location.PassiveLocationManager

    @Inject
    lateinit var vehicleModeManager: com.remindly.location.VehicleModeManager

    @Inject
    lateinit var settingsRepository: com.remindly.data.settings.VoiceAlarmSettingsRepository

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

        // Filet de sécurité : réenregistrement périodique des géofences (toutes les 6h)
        scheduleGeofenceRefresh()

        // Moniteur passif & Détection d'activité : démarrage au lancement si activés
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.getSettings()
                if (settings.passiveLocationMonitoring) {
                    passiveLocationManager.start()
                }
                if (settings.autoVehicleDetection) {
                    vehicleModeManager.startMonitoring()
                }
            } catch (_: Exception) {}
        }
    }

    private fun scheduleGeofenceRefresh() {
        val geofenceRefresh = PeriodicWorkRequestBuilder<ReRegisterGeofencesWorker>(
            6, TimeUnit.HOURS
        ).build()
        val workManager = WorkManager.getInstance(this)
        workManager.enqueueUniquePeriodicWork(
            "geofence_periodic_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            geofenceRefresh
        )

        // Réenregistrement immédiat au démarrage pour rattraper toute mise à jour d'APK ou redémarrage
        val oneShot = OneTimeWorkRequestBuilder<ReRegisterGeofencesWorker>().build()
        workManager.enqueueUniqueWork(
            "geofence_startup_refresh",
            ExistingWorkPolicy.KEEP,
            oneShot
        )
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
