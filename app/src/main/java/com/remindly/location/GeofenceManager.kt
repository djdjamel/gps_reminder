package com.remindly.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import android.widget.Toast
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.domain.model.CategoryReferenceType
import com.remindly.domain.model.CommuteDirection
import com.remindly.domain.model.PlaceCategory
import com.remindly.domain.model.Reminder
import com.remindly.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class GeofenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearbyPlacesService: NearbyPlacesService,
    private val directionsService: DirectionsService,
    private val settingsRepository: VoiceAlarmSettingsRepository,
    private val appLogger: AppLogger,
    private val reminderRepositoryProvider: Provider<com.remindly.data.repo.ReminderRepository>
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
                val settings = settingsRepository.getSettings()

                // 1. Rappel par Catégorie (POI)
                if (reminder.placeCategory != null) {
                    // Vérifier si c'est un trajet avec direction RETURN (Au retour : Travail -> Maison)
                    val isCommuteRoute = reminder.categoryRefType == CategoryReferenceType.COMMUTE_ROUTE.id && settings.hasCommuteRoute
                    val isReturnDirection = reminder.commuteDirection == CommuteDirection.RETURN.id

                    if (isCommuteRoute && isReturnDirection && settings.commuteEndLat != null && settings.commuteEndLng != null) {
                        val currentLocation = try {
                            fusedLocationClient.lastLocation.await() ?: fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
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

                        val distM = if (currentLocation != null) results[0].toInt() else -1
                        val isAlreadyAtDestination = currentLocation != null && results[0] < 500f

                        val evalMsg = "Évaluation trajet 'Au retour': Distance à destination (${settings.commuteEndLabel ?: "Arrivée"}) = ${if (distM >= 0) "${distM}m" else "Inconnue"} (Seuil: 500m) -> ${if (isAlreadyAtDestination) "Déjà sur place, POIs armés immédiatement" else "Étape armée, en attente d'arrivée"}"
                        Log.i(tag, "addGeofence: $evalMsg")
                        appLogger.i("ROUTE_EVAL", evalMsg, reminder.id)

                        if (!isAlreadyAtDestination) {
                            // Poser uniquement un géofence d'étape sur le lieu de travail / arrivée
                            val stageReqId = "${reminder.id}_stage_dest"
                            val msg = "Rappel configuré 'Au retour'. Armement de l'étape sur destination (${settings.commuteEndLabel ?: "Travail"} à ${settings.commuteEndLat}, ${settings.commuteEndLng})"
                            Log.i(tag, "addGeofence: $msg")
                            appLogger.i("GEOFENCE_ARMED", msg, reminder.id)

                            registerSingleGeofence(stageReqId, settings.commuteEndLat!!, settings.commuteEndLng!!, 350f, reminder.id)

                            val currentTracked = prefs.getStringSet("geofences_${reminder.id}", emptySet()) ?: emptySet()
                            prefs.edit().putStringSet("geofences_${reminder.id}", currentTracked + stageReqId).apply()
                            return@launch
                        } else {
                            val msg = "Utilisateur déjà à destination (${distM}m). Armement direct des POIs de retour."
                            Log.i(tag, "addGeofence: $msg")
                            appLogger.i("GEOFENCE_ARMED", msg, reminder.id)
                        }
                    }

                    // Armement direct des POIs
                    armCategoryPoIs(reminder)
                } else if (reminder.placeLat != null && reminder.placeLng != null && reminder.placeLat != 0.0 && reminder.placeLng != 0.0) {
                    // 2. Rappel à adresse fixe unique (Rayon personnalisé ou défaut paramètres)
                    val radius = reminder.placeRadiusM ?: settings.poiDetectionRadiusM.toFloat()
                    val msg = "Enregistrement géofence unique pour '${reminder.placeLabel ?: "Lieu fixe"}' (${reminder.placeLat}, ${reminder.placeLng}) - Rayon ${radius}m"
                    Log.d(tag, "addGeofence: $msg")
                    appLogger.i("GEOFENCE_ARMED", msg, reminder.id)

                    registerSingleGeofence(
                        reminder.id.toString(),
                        reminder.placeLat,
                        reminder.placeLng,
                        radius,
                        reminder.id,
                        responsivenessMs = 0  // Réactivité maximale pour lieu fixe
                    )
                }
            } catch (t: Throwable) {
                Log.e(tag, "Exception dans addGeofence: ${t.message}", t)
                appLogger.e("GEOFENCE_ERROR", "Erreur addGeofence: ${t.message}", t, reminder.id)
                handleGeofenceError(t, "Enregistrement du rappel ${reminder.id}")
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
                val detectionRadiusM = reminder.placeRadiusM ?: settings.poiDetectionRadiusM.toFloat()

                appLogger.i("POI_SEARCH", "Recherche des POIs catégorie '${category.displayName}' (Rayon de détection: ${detectionRadiusM.toInt()}m)", reminder.id)

                var searchCenterLat: Double? = null
                var searchCenterLng: Double? = null

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
                        fusedLocationClient.lastLocation.await() ?: fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                    } catch (e: Exception) {
                        null
                    }

                    var centerLat = location?.latitude ?: reminder.placeLat
                    var centerLng = location?.longitude ?: reminder.placeLng

                    if (centerLat == null || centerLng == null || centerLat == 0.0 || centerLng == 0.0) {
                        // Tenter une acquisition active de position fraîche
                        try {
                            val freshLoc = fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                            if (freshLoc != null) {
                                centerLat = freshLoc.latitude
                                centerLng = freshLoc.longitude
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Échec acquisition position fraîche: ${e.message}")
                        }
                    }

                    if (centerLat != null && centerLng != null && centerLat != 0.0 && centerLng != 0.0) {
                        searchCenterLat = centerLat
                        searchCenterLng = centerLng
                        val radiusMeters = settings.poiSearchRadiusKm * 1000
                        Log.d(tag, "armCategoryPoIs: Recherche autour de ($centerLat, $centerLng) sur un rayon de $radiusMeters m")
                        nearbyPlacesService.searchNearby(
                            centerLat = centerLat,
                            centerLng = centerLng,
                            radiusMeters = radiusMeters,
                            category = category
                        )
                    } else {
                        val warnMsg = "Position actuelle inconnue pour la recherche de catégorie"
                        Log.w(tag, "armCategoryPoIs: $warnMsg")
                        appLogger.w("POI_SEARCH", warnMsg, reminder.id)
                        emptyList()
                    }
                }

                if (places.isNotEmpty()) {
                    val editor = prefs.edit()
                    places.forEachIndexed { index, place ->
                        val distFormatted = if (place.distanceMeters > 0f) "${place.distanceMeters.toInt()}m" else "Inconnue"
                        val poiDetailMsg = "#${index + 1} ${place.name} | Dist: $distFormatted | Pos: (${place.latitude}, ${place.longitude})"
                        Log.d(tag, "-> POI: $poiDetailMsg")
                        appLogger.i("POI_DETAIL", poiDetailMsg, reminder.id)
                    }

                    val poiGeofences = places.mapIndexed { index, place ->
                        val reqId = "${reminder.id}_geo_${index}"
                        editor.putString("place_name_${reqId}", place.name)
                        editor.putFloat("place_lat_${reqId}", place.latitude.toFloat())
                        editor.putFloat("place_lng_${reqId}", place.longitude.toFloat())
                        Geofence.Builder()
                            .setRequestId(reqId)
                            .setCircularRegion(
                                place.latitude,
                                place.longitude,
                                detectionRadiusM
                            )
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(
                                Geofence.GEOFENCE_TRANSITION_ENTER or 
                                Geofence.GEOFENCE_TRANSITION_DWELL or 
                                Geofence.GEOFENCE_TRANSITION_EXIT
                            )
                            .setLoiteringDelay(8000)
                            .setNotificationResponsiveness(3000)
                            .build()
                    }

                    val allTrackedIds = poiGeofences.map { it.requestId }.toMutableSet()

                    // Armement de la Fenêtre Glissante (Rolling Exit Geofence) si recherche autour de position
                    if (searchCenterLat != null && searchCenterLng != null && reminder.categoryRefType != CategoryReferenceType.COMMUTE_ROUTE.id) {
                        val exitReqId = "${reminder.id}_exit_zone"
                        val exitRadiusM = settings.rollingExitRadiusM.toFloat()

                        editor.putFloat("exit_center_lat_${reminder.id}", searchCenterLat.toFloat())
                        editor.putFloat("exit_center_lng_${reminder.id}", searchCenterLng.toFloat())
                        editor.putFloat("exit_radius_${reminder.id}", exitRadiusM)
                        allTrackedIds.add(exitReqId)

                        val exitGeofence = Geofence.Builder()
                            .setRequestId(exitReqId)
                            .setCircularRegion(searchCenterLat, searchCenterLng, exitRadiusM)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                            .setNotificationResponsiveness(5000)
                            .build()

                        // Requête dédiée pour l'exit zone avec initialTrigger = 0 pour éviter tout faux déclenchement initial
                        val exitRequest = GeofencingRequest.Builder()
                            .setInitialTrigger(0)
                            .addGeofence(exitGeofence)
                            .build()

                        geofencingClient.addGeofences(exitRequest, geofencePendingIntent)
                            .addOnSuccessListener {
                                val exitArmedMsg = "Fenêtre glissante armée : Zone tampon de sortie (${exitRadiusM.toInt()}m) autour de (${searchCenterLat}, ${searchCenterLng})"
                                Log.i(tag, "armCategoryPoIs: $exitArmedMsg")
                                appLogger.i("ROLLING_ZONE_ARMED", exitArmedMsg, reminder.id)
                            }
                            .addOnFailureListener { e ->
                                Log.e(tag, "ÉCHEC armement exit_zone: ${e.message}", e)
                            }
                    }

                    editor.putStringSet("geofences_${reminder.id}", allTrackedIds).apply()

                    val poiRequest = GeofencingRequest.Builder()
                        .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
                        .addGeofences(poiGeofences)
                        .build()

                    geofencingClient.addGeofences(poiRequest, geofencePendingIntent)
                        .addOnSuccessListener {
                            val successMsg = "${places.size} géofences de POI armées (Rayon: ${detectionRadiusM.toInt()}m, DWELL: 8s, Resp: 3s)"
                            Log.i(tag, "SUCCÈS: $successMsg")
                            appLogger.success("GEOFENCE_REGISTERED", successMsg, reminder.id)
                        }
                        .addOnFailureListener { e ->
                            Log.e(tag, "ÉCHEC enregistrement géofences: ${e.message}", e)
                            appLogger.e("GEOFENCE_ERROR", "Échec Play Services addGeofences: ${e.message}", e, reminder.id)
                            handleGeofenceError(e, "Enregistrement des POIs pour rappel ${reminder.id}")
                        }
                } else {
                    val noPoiMsg = "Aucun POI trouvé pour la catégorie ${category.displayName} dans la zone immédiate"
                    Log.w(tag, "ATTENTION: $noPoiMsg")
                    appLogger.w("POI_SEARCH", noPoiMsg, reminder.id)

                    // Armement de la Fenêtre Glissante en veille même sans POI immédiat pour actualisation à la prochaine agglomération
                    if (searchCenterLat != null && searchCenterLng != null && reminder.categoryRefType != CategoryReferenceType.COMMUTE_ROUTE.id) {
                        val exitReqId = "${reminder.id}_exit_zone"
                        val exitRadiusM = settings.rollingExitRadiusM.toFloat()

                        val editor = prefs.edit()
                        editor.putFloat("exit_center_lat_${reminder.id}", searchCenterLat.toFloat())
                        editor.putFloat("exit_center_lng_${reminder.id}", searchCenterLng.toFloat())
                        editor.putFloat("exit_radius_${reminder.id}", exitRadiusM)
                        editor.putStringSet("geofences_${reminder.id}", setOf(exitReqId)).apply()

                        val exitGeofence = Geofence.Builder()
                            .setRequestId(exitReqId)
                            .setCircularRegion(searchCenterLat, searchCenterLng, exitRadiusM)
                            .setExpirationDuration(Geofence.NEVER_EXPIRE)
                            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                            .setNotificationResponsiveness(5000)
                            .build()

                        val request = GeofencingRequest.Builder()
                            .setInitialTrigger(0)
                            .addGeofences(listOf(exitGeofence))
                            .build()

                        geofencingClient.addGeofences(request, geofencePendingIntent)
                            .addOnSuccessListener {
                                val exitArmedMsg = "Zone sans commerce immédiat. Fenêtre glissante armée en veille (${exitRadiusM.toInt()}m) pour actualisation à la prochaine agglomération."
                                Log.i(tag, "armCategoryPoIs: $exitArmedMsg")
                                appLogger.i("ROLLING_ZONE_ARMED", exitArmedMsg, reminder.id)
                            }
                            .addOnFailureListener { e ->
                                Log.e(tag, "Échec armement exit_zone de veille: ${e.message}")
                            }
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "Exception dans armCategoryPoIs: ${t.message}", t)
                appLogger.e("GEOFENCE_ERROR", "Exception armCategoryPoIs: ${t.message}", t, reminder.id)
                handleGeofenceError(t, "Recherche POI pour rappel ${reminder.id}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerSingleGeofence(
        requestId: String,
        lat: Double,
        lng: Double,
        radius: Float,
        reminderId: Long? = null,
        responsivenessMs: Int = 3000
    ) {
        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(lat, lng, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or 
                Geofence.GEOFENCE_TRANSITION_DWELL or 
                Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .setLoiteringDelay(8000)
            .setNotificationResponsiveness(responsivenessMs)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent)
            .addOnSuccessListener {
                val successMsg = "Géofence unique $requestId enregistrée (${lat}, ${lng}, rayon: ${radius.toInt()}m)"
                Log.i(tag, "SUCCÈS: $successMsg")
                appLogger.success("GEOFENCE_REGISTERED", successMsg, reminderId)
            }
            .addOnFailureListener { e ->
                Log.e(tag, "ÉCHEC géofence unique $requestId: ${e.message}", e)
                appLogger.e("GEOFENCE_ERROR", "Échec géofence unique $requestId: ${e.message}", e, reminderId)
                handleGeofenceError(e, "Géofence $requestId")
            }
    }

    private fun handleGeofenceError(e: Throwable, contextMsg: String) {
        scope.launch(Dispatchers.Main) {
            val userMsg = if (e is ApiException) {
                when (e.statusCode) {
                    1004 -> "⚠️ Localisation en arrière-plan requise ('Toujours autoriser') pour les rappels de lieu."
                    1000 -> "⚠️ Service de localisation indisponible. Veuillez activer le GPS."
                    else -> "⚠️ Erreur géolocalisation (${e.statusCode}): ${e.message}"
                }
            } else {
                "⚠️ Impossible d'activer la zone de rappel : ${e.message}"
            }
            try {
                Toast.makeText(context, userMsg, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
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
        val defaultIds = listOf(reminderId.toString(), "${reminderId}_stage_dest", "${reminderId}_exit_zone") + (0..35).map { "${reminderId}_geo_$it" }
        val idsToRemove = (storedIds + defaultIds).toList()

        geofencingClient.removeGeofences(idsToRemove)
            .addOnSuccessListener {
                Log.d(tag, "Géofences du rappel $reminderId supprimées (${idsToRemove.size} IDs)")
                appLogger.i("GEOFENCE_CLEANUP", "Géofences du rappel $reminderId désarmées (${idsToRemove.size} IDs)", reminderId)
                val editor = prefs.edit()
                idsToRemove.forEach { id ->
                    editor.remove("place_name_$id")
                    editor.remove("place_lat_$id")
                    editor.remove("place_lng_$id")
                }
                editor.remove("exit_center_lat_$reminderId")
                editor.remove("exit_center_lng_$reminderId")
                editor.remove("exit_radius_$reminderId")
                editor.remove("geofences_$reminderId").apply()
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Erreur suppression géofences $reminderId: ${e.message}")
                val editor = prefs.edit()
                idsToRemove.forEach { id ->
                    editor.remove("place_name_$id")
                    editor.remove("place_lat_$id")
                    editor.remove("place_lng_$id")
                }
                editor.remove("exit_center_lat_$reminderId")
                editor.remove("exit_center_lng_$reminderId")
                editor.remove("exit_radius_$reminderId")
                editor.remove("geofences_$reminderId").apply()
            }
    }

    fun resetCooldown(reminderId: Long) {
        prefs.edit().remove("last_trigger_time_$reminderId").apply()
    }

    fun rearmGeofence(reminderId: Long) {
        scope.launch {
            try {
                val repository = reminderRepositoryProvider.get()
                val reminder = repository.getById(reminderId)
                if (reminder != null && reminder.status == com.remindly.domain.model.ReminderStatus.ACTIVE) {
                    appLogger.i("GEOFENCE_REARM", "Réarmement automatique du géofence pour '${reminder.placeLabel ?: reminder.text}' (Rappel #$reminderId)", reminderId)
                    removeGeofence(reminderId)
                    kotlinx.coroutines.delay(300)
                    addGeofence(reminder)
                }
            } catch (e: Exception) {
                Log.e(tag, "Erreur rearmGeofence pour #$reminderId: ${e.message}", e)
            }
        }
    }

    fun rearmGeofence(reminder: Reminder) {
        scope.launch {
            try {
                if (reminder.status == com.remindly.domain.model.ReminderStatus.ACTIVE) {
                    appLogger.i("GEOFENCE_REARM", "Réarmement automatique du géofence pour '${reminder.placeLabel ?: reminder.text}' (Rappel #${reminder.id})", reminder.id)
                    removeGeofence(reminder.id)
                    kotlinx.coroutines.delay(300)
                    addGeofence(reminder)
                }
            } catch (e: Exception) {
                Log.e(tag, "Erreur rearmGeofence: ${e.message}", e)
            }
        }
    }
}
