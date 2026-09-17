package com.remindly.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PassiveLocationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, PassiveLocationReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 0L)
                .setMinUpdateIntervalMillis(1000L)
                .build()

            fusedLocationClient.requestLocationUpdates(request, pendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Moniteur passif de localisation démarré avec succès (0% batterie)")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Échec démarrage écoute passive: ${e.message}", e)
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission de localisation manquante pour l'écoute passive: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception démarrage écoute passive: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            fusedLocationClient.removeLocationUpdates(pendingIntent)
                .addOnSuccessListener {
                    Log.i(TAG, "Moniteur passif de localisation arrêté")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Échec arrêt écoute passive: ${e.message}", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception arrêt écoute passive: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "PassiveLocationManager"
        private const val PENDING_INTENT_REQUEST_CODE = 4041
    }
}
