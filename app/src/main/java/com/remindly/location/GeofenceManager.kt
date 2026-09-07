package com.remindly.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Base64
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.domain.model.CategoryReferenceType
import com.remindly.domain.model.CommuteDirection
import com.remindly.domain.model.PlaceCategory
import com.remindly.domain.model.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearbyPlacesService: NearbyPlacesService,
    private val directionsService: DirectionsService,
    private val settingsRepository: VoiceAlarmSettingsRepository
) {
    private val tag = "GeofenceManager"
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("geofence_tracking", Context.MODE_PRIVATE)

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = "com.remindly.action.ACTION_GEOFENCE_EVENT"
        }
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    fun addGeofence(reminder: Reminder) {
        scope.launch {
            try {
                // 1. Rappel par Catégorie (POI)
                if (reminder.placeCategory != null) {
                    val settings = settingsRepository.getSettings()

                    // Vérifier si c'est un trajet avec direction RETURN (Au retour : Travail -> Maison)
                    val isCommuteRoute = reminder.categoryRefType == CategoryReferenceType.COMMUTE_ROUTE.id && settings.hasCommuteRoute
                    val isReturnDirection = reminder.commuteDirection == CommuteDirection.RETURN.id || reminder.commuteDirection == null

                    if (isCommuteRoute && isReturnDirection && settings.commuteEndLat != null && settings.commuteEndLng != null) {
                        val currentLocation = try {
                            fusedLocationClient.lastLocation.await()
                        } catch (e: Exception) {
                            null
                        }

                        val results = FloatArray(1)
                        if (currentLocation != null) {
                            Location.distanceBetween(
                                currentLocation.latitude,
                                currentLocation.longitude,
                                settings.commuteEndLat!!,
                                settings.commuteEndLng!!,
                                results
                            )
                        }

                        val isAlreadyAtDestination = currentLocation != null && results[0] < 500f

                        if (!isAlreadyAtDestination) {
                            // Poser uniquement un géofence d'étape sur le lieu de travail / arrivée
                            val stageReqId = "${reminder.id}_stage_dest"
                            Log.i(tag, "addGeofence: Rappel ${reminder.id} configuré 'Au retour'. Armement de l'étape intermédiaire sur l'arrivée (${settings.commuteEndLabel ?: "Travail"} à ${settings.commuteEndLat}, ${settings.commuteEndLng})")
                            registerSingleGeofence(stageReqId, settings.commuteEndLat!!, settings.commuteEndLng!!, 350f)

                            val currentTracked = prefs.getStringSet("geofences_${reminder.id}", emptySet()) ?: emptySet()
                            prefs.edit().putStringSet("geofences_${reminder.id}", currentTracked + stageReqId).apply()
                            return@launch
                        } else {
                            Log.i(tag, "addGeofence: Utilisateur déjà à destination (${results[0]}m). Armement direct des POIs de retour.")
                        }
                    }

                    // Armement direct des POIs
                    armCategoryPoIs(reminder)
                } else if (reminder.placeLat != null && reminder.placeLng != null && reminder.placeLat != 0.0 && reminder.placeLng != 0.0) {
                    // 2. Rappel à adresse fixe unique
                    Log.d(tag, "addGeofence: Enregistrement géofence unique pour ${reminder.id} à (${reminder.placeLat}, ${reminder.placeLng})")
                    registerSingleGeofence(
                        reminder.id.toString(),
                        reminder.placeLat,
                        reminder.placeLng,
                        reminder.placeRadiusM ?: 250f
                    )
                }
            } catch (t: Throwable) {
                Log.e(tag, "Exception dans addGeofence: ${t.message}", t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun armCategoryPoIs(reminder: Reminder) {
        scope.launch {
            try {
                if (reminder.placeCategory == null) return@launch
                val category = PlaceCategory.fromId(reminder.placeCategory) ?: return@launch
                val settings = settingsRepository.getSettings()

                Log.d(tag, "armCategoryPoIs: Début de recherche pour rappel ${reminder.id} - Catégorie ${category.displayName}")

                val places = if (reminder.categoryRefType == CategoryReferenceType.COMMUTE_ROUTE.id && settings.hasCommuteRoute) {
                    if (settings.commuteRoutePolyline != null) {
                        val allPoints = directionsService.decodePolyline(settings.commuteRoutePolyline)
                        Log.d(tag, "armCategoryPoIs: Recherche le long de la polyline (${allPoints.size} points)")
                        nearbyPlacesService.searchAlongWaypoints(allPoints, category, radiusPerPointMeters = 1000)
                    } else {
                        Log.d(tag, "armCategoryPoIs: Recherche le long du trajet direct (${settings.commuteStartLabel} -> ${settings.commuteEndLabel})")
                        nearbyPlacesService.searchAlongRoute(
                            startLat = settings.commuteStartLat!!,
                            startLng = settings.commuteStartLng!!,
                            endLat = settings.commuteEndLat!!,
                            endLng = settings.commuteEndLng!!,
                            category = category,
                            radiusPerPointMeters = 1000
                        )
                    }
                } else {
                    val location = try {
                        fusedLocationClient.lastLocation.await()
                    } catch (e: Exception) {
                        null
                    }

                    val centerLat = location?.latitude ?: reminder.placeLat
                    val centerLng = location?.longitude ?: reminder.placeLng

                    if (centerLat != null && centerLng != null && centerLat != 0.0 && centerLng != 0.0) {
                        val radiusMeters = settings.poiSearchRadiusKm * 1000
                        Log.d(tag, "armCategoryPoIs: Recherche autour de ($centerLat, $centerLng) sur un rayon de $radiusMeters m")
                        nearbyPlacesService.searchNearby(
                            centerLat = centerLat,
                            centerLng = centerLng,
                            radiusMeters = radiusMeters,
                            category = category
                        )
                    } else {
                        Log.w(tag, "armCategoryPoIs: Position actuelle inconnue pour la recherche de catégorie")
                        emptyList()
                    }
                }

                if (places.isNotEmpty()) {
                    val geofences = places.mapIndexed { index, place ->
                        val safeName = try {
                            Base64.encodeToString(place.name.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
                        } catch (e: Exception) {
                            ""
                        }
                        val reqId = if (safeName.isNotEmpty()) "${reminder.id}_geo_${index}__$safeName" else "${reminder.id}_geo_${index}"
                        Log.d(tag, "-> Géofence [$reqId] posée sur: ${place.name} (${place.latitude}, ${place.longitude}) - Rayon 250m")
                        Geofence.Builder()
                            .setRequestId(reqId)
                            .setCircularRegion(
                                place.latitude,
                                place.longitude,
                                250f
                            )
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                            .setNotificationResponsiveness(0)
                            .build()
                    }

                    val requestIds = geofences.map { it.requestId }.toSet()
                    val currentTracked = prefs.getStringSet("geofences_${reminder.id}", emptySet()) ?: emptySet()
                    prefs.edit().putStringSet("geofences_${reminder.id}", currentTracked + requestIds).apply()

                    val request = GeofencingRequest.Builder()
                        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                        .addGeofences(geofences)
                        .build()

                    geofencingClient.addGeofences(request, geofencePendingIntent)
                        .addOnSuccessListener {
                            Log.i(tag, "SUCCÈS: ${geofences.size} géofences de POI enregistrées pour le rappel ${reminder.id}")
                        }
                        .addOnFailureListener { e ->
                            Log.e(tag, "ÉCHEC enregistrement géofences: ${e.message}", e)
                        }
                } else {
                    Log.w(tag, "ATTENTION: Aucun POI trouvé pour la catégorie ${category.displayName}.")
                }
            } catch (t: Throwable) {
                Log.e(tag, "Exception dans armCategoryPoIs: ${t.message}", t)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSingleGeofence(requestId: String, lat: Double, lng: Double, radius: Float) {
        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setNotificationResponsiveness(0)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnSuccessListener {
                Log.i(tag, "SUCCÈS: Géofence unique $requestId enregistrée (${lat}, ${lng})")
            }
            .addOnFailureListener { e ->
                Log.e(tag, "ÉCHEC géofence unique $requestId: ${e.message}", e)
            }
    }

    fun removeSingleGeofence(requestId: String) {
        geofencingClient.removeGeofences(listOf(requestId))
            .addOnSuccessListener {
                Log.d(tag, "Géofence unique $requestId supprimée")
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Erreur suppression géofence $requestId: ${e.message}")
            }
    }

    fun removeGeofence(reminderId: Long) {
        val storedIds = prefs.getStringSet("geofences_$reminderId", emptySet()) ?: emptySet()
        val defaultIds = listOf(reminderId.toString(), "${reminderId}_stage_dest") + (0..35).map { "${reminderId}_geo_$it" }
        val idsToRemove = (storedIds + defaultIds).toList()

        geofencingClient.removeGeofences(idsToRemove)
            .addOnSuccessListener {
                Log.d(tag, "Géofences du rappel $reminderId supprimées (${idsToRemove.size} IDs)")
                prefs.edit().remove("geofences_$reminderId").apply()
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Erreur suppression géofences $reminderId: ${e.message}")
                prefs.edit().remove("geofences_$reminderId").apply()
            }
    }
}
