package com.remindly.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.remindly.domain.model.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        // FLAG_MUTABLE is required for Geofencing in Android 12+ if target SDK is 31+ in some cases,
        // but Google's docs say FLAG_UPDATE_CURRENT or FLAG_MUTABLE. We use FLAG_UPDATE_CURRENT | FLAG_MUTABLE.
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission") // Caller MUST check permissions before
    fun addGeofence(reminder: Reminder) {
        if (reminder.placeLat == null || reminder.placeLng == null) return
        
        val geofence = Geofence.Builder()
            .setRequestId(reminder.id.toString())
            .setCircularRegion(
                reminder.placeLat,
                reminder.placeLng,
                100f // Rayon de 100 mètres par défaut
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                // Log success
            }
            addOnFailureListener {
                it.printStackTrace()
            }
        }
    }

    fun removeGeofence(reminderId: Long) {
        geofencingClient.removeGeofences(listOf(reminderId.toString()))
    }
}
