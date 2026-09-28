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
import com.remindly.location.registry.DiscoveredPoi
import com.remindly.location.registry.LinkLifecycleState
import com.remindly.location.registry.PoiProvider
import com.remindly.location.registry.PoiRegistry
import com.remindly.location.registry.ReminderPoiLink
import com.remindly.location.registry.TrackedGeofence
import com.remindly.location.scheduler.ContextualGeofenceScheduler
import com.remindly.location.scheduler.DiffOperation
import com.remindly.location.scheduler.GeofenceDiffEngine
import com.remindly.location.scheduler.GeofenceStateSnapshot
import com.remindly.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
    private val reminderRepositoryProvider: Provider<com.remindly.data.repo.ReminderRepository>,
    private val poiRegistry: PoiRegistry,
    private val scheduler: ContextualGeofenceScheduler,
    private val diffEngine: GeofenceDiffEngine,
    private val triggerCoordinator: TriggerCoordinator,
    private val userActivityTracker: UserActivityTracker
) {
    private val tag = "GeofenceManager"
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("geofence_tracking", Context.MODE_PRIVATE)
    private var actualState = GeofenceStateSnapshot()

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
                if (reminder.status != com.remindly.domain.model.ReminderStatus.ACTIVE) {
                    val msg = "addGeofence ignoré: rappel #${reminder.id} n'est pas actif (status=${reminder.status})"
                    Log.d(tag, msg)
                    return@launch
                }
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

                            val currentTracked = prefs.getStringSet("geofences_${reminder.id}", emptySet()) ?: emptySet()
                            val editor = prefs.edit()
                            editor.putFloat("place_lat_${stageReqId}", settings.commuteEndLat!!.toFloat())
                            editor.putFloat("place_lng_${stageReqId}", settings.commuteEndLng!!.toFloat())
                            editor.putFloat("radius_${stageReqId}", 350f)
                            editor.putStringSet("geofences_${reminder.id}", currentTracked + stageReqId)
                            editor.apply()
                            synchronizeGeofences()
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
                    val semanticRadius = reminder.placeRadiusM ?: settings.poiDetectionRadiusM.toFloat()
                    val currentActivity = userActivityTracker.currentActivity.value
                    val wakeRadius = GeofenceFilterUtils.computeDynamicWakeRadius(
                        semanticRadius = semanticRadius,
                        activityType = currentActivity,
                        isFixedPlace = true
                    )

                    val editor = prefs.edit()
                    editor.putFloat("semantic_radius_${reminder.id}", semanticRadius)
                    editor.putFloat("wake_radius_${reminder.id}", wakeRadius)
                    editor.putString("place_name_${reminder.id}", reminder.placeLabel ?: reminder.text ?: "Lieu fixe")
                    editor.putFloat("place_lat_${reminder.id}", reminder.placeLat.toFloat())
                    editor.putFloat("place_lng_${reminder.id}", reminder.placeLng.toFloat())
                    editor.putStringSet("geofences_${reminder.id}", setOf(reminder.id.toString()))
                    editor.apply()

                    val activityLabel = ActivityTransitionReceiver.getActivityLabel(currentActivity).second
                    val msg = "Enregistrement géofence unique pour '${reminder.placeLabel ?: reminder.text ?: "Lieu fixe"}' (${reminder.placeLat}, ${reminder.placeLng}) - Rayon Sémantique: ${semanticRadius.toInt()}m | WakeRadius: ${wakeRadius.toInt()}m [$activityLabel]"
                    Log.d(tag, "addGeofence: $msg")
                    appLogger.i("GEOFENCE_ARMED", msg, reminder.id)

                    synchronizeGeofences()
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
                if (reminder.status != com.remindly.domain.model.ReminderStatus.ACTIVE) {
                    val msg = "armCategoryPoIs ignoré: rappel #${reminder.id} n'est pas actif (status=${reminder.status})"
                    Log.d(tag, msg)
                    return@launch
                }
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
                    val allTrackedIds = mutableSetOf<String>()

                    places.forEachIndexed { index, place ->
                        val distFormatted = if (place.distanceMeters > 0f) "${place.distanceMeters.toInt()}m" else "Inconnue"
                        val poiDetailMsg = "#${index + 1} ${place.name} | Dist: $distFormatted | Pos: (${place.latitude}, ${place.longitude})"
                        Log.d(tag, "-> POI: $poiDetailMsg")
                        appLogger.i("POI_DETAIL", poiDetailMsg, reminder.id)

                        // 1. Détermination du fournisseur (Google vs OSM)
                        val provider = if (place.placeId.startsWith("osm_")) PoiProvider.OPEN_STREET_MAP else PoiProvider.GOOGLE_PLACES

                        // 2. Enregistrement dédupliqué dans le PoiRegistry
                        val poi = poiRegistry.registerOrGetPoi(
                            provider = provider,
                            providerId = place.placeId,
                            name = place.name,
                            latitude = place.latitude,
                            longitude = place.longitude,
                            category = reminder.placeCategory
                        )

                        // 3. Liaison sémantique Reminder <-> POI
                        val currentActivity = userActivityTracker.currentActivity.value
                        val link = poiRegistry.linkReminderToPoi(
                            reminderId = reminder.id,
                            poiId = poi.id,
                            semanticRadiusM = detectionRadiusM,
                            activityType = currentActivity
                        )

                        // 4. Calcul du contextScore initial
                        val initialScore = if (place.distanceMeters > 0f) {
                            (100 - (place.distanceMeters / 25f).toInt()).coerceIn(20, 95)
                        } else {
                            70
                        }
                        link.contextScore = initialScore

                        // Clé canonique pour la compatibilité prefs et DrivingPulseService
                        val reqId = "geo_${poi.id.replace(":", "_").replace("/", "_")}"
                        allTrackedIds.add(reqId)
                        editor.putString("place_name_${reqId}", place.name)
                        editor.putFloat("place_lat_${reqId}", place.latitude.toFloat())
                        editor.putFloat("place_lng_${reqId}", place.longitude.toFloat())
                        editor.putString("poi_id_${reqId}", poi.id)
                    }

                    // Configuration de la Fenêtre Glissante (Rolling Exit Geofence) si recherche autour de position
                    if (searchCenterLat != null && searchCenterLng != null && reminder.categoryRefType != CategoryReferenceType.COMMUTE_ROUTE.id) {
                        val exitReqId = "${reminder.id}_exit_zone"
                        val exitRadiusM = settings.rollingExitRadiusM.toFloat()

                        editor.putFloat("exit_center_lat_${reminder.id}", searchCenterLat.toFloat())
                        editor.putFloat("exit_center_lng_${reminder.id}", searchCenterLng.toFloat())
                        editor.putFloat("exit_radius_${reminder.id}", exitRadiusM)
                        allTrackedIds.add(exitReqId)

                        val exitMsg = "Fenêtre glissante configurée : Zone tampon de sortie (${exitRadiusM.toInt()}m) autour de (${searchCenterLat}, ${searchCenterLng})"
                        Log.i(tag, "armCategoryPoIs: $exitMsg")
                        appLogger.i("ROLLING_ZONE_CONFIG", exitMsg, reminder.id)
                    }

                    editor.putStringSet("geofences_${reminder.id}", allTrackedIds).apply()

                    // Synchronisation unifiée respectant le Hard Cap 85 via DiffEngine
                    synchronizeGeofences()
                } else {
                    val noPoiMsg = "Aucun POI trouvé pour la catégorie ${category.displayName} dans la zone immédiate"
                    Log.w(tag, "ATTENTION: $noPoiMsg")
                    appLogger.w("POI_SEARCH", noPoiMsg, reminder.id)

                    // Configuration de la Fenêtre Glissante en veille même sans POI immédiat pour actualisation à la prochaine agglomération
                    if (searchCenterLat != null && searchCenterLng != null && reminder.categoryRefType != CategoryReferenceType.COMMUTE_ROUTE.id) {
                        val exitReqId = "${reminder.id}_exit_zone"
                        val exitRadiusM = settings.rollingExitRadiusM.toFloat()

                        val editor = prefs.edit()
                        editor.putFloat("exit_center_lat_${reminder.id}", searchCenterLat.toFloat())
                        editor.putFloat("exit_center_lng_${reminder.id}", searchCenterLng.toFloat())
                        editor.putFloat("exit_radius_${reminder.id}", exitRadiusM)
                        editor.putStringSet("geofences_${reminder.id}", setOf(exitReqId)).apply()

                        val exitMsg = "Zone sans commerce immédiat. Fenêtre glissante configurée en veille (${exitRadiusM.toInt()}m) pour actualisation à la prochaine agglomération."
                        Log.i(tag, "armCategoryPoIs: $exitMsg")
                        appLogger.i("ROLLING_ZONE_CONFIG", exitMsg, reminder.id)

                        synchronizeGeofences()
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
        responsivenessMs: Int = 1000
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

    suspend fun removeAllGeofences() {
        try {
            geofencingClient.removeGeofences(geofencePendingIntent).await()
            val msg = "Toutes les géofences ont été purgées auprès de Google Play Services"
            Log.i(tag, msg)
            appLogger.i("GEOFENCE_CLEANUP", msg)
        } catch (e: Exception) {
            Log.w(tag, "Avertissement lors de la purge globale des géofences: ${e.message}")
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

    private fun getGeofenceIdsForReminder(reminderId: Long): List<String> {
        val storedIds = prefs.getStringSet("geofences_$reminderId", emptySet()) ?: emptySet()
        val defaultIds = listOf(reminderId.toString(), "${reminderId}_stage_dest", "${reminderId}_exit_zone") + (0..150).map { "${reminderId}_geo_$it" }
        val prefix = "${reminderId}_"
        val dynamicIds = prefs.all.keys
            .filter { it.startsWith("place_name_${prefix}") || it.startsWith("place_lat_${prefix}") || it.startsWith("place_lng_${prefix}") }
            .map { it.removePrefix("place_name_").removePrefix("place_lat_").removePrefix("place_lng_") }
            .toSet()
        return (storedIds + defaultIds + dynamicIds).toSet().toList()
    }

    private fun cleanupPrefsForReminder(reminderId: Long, idsToRemove: List<String>) {
        val editor = prefs.edit()
        idsToRemove.forEach { id ->
            editor.remove("place_name_$id")
            editor.remove("place_lat_$id")
            editor.remove("place_lng_$id")
            editor.remove("semantic_radius_$id")
            editor.remove("wake_radius_$id")
        }
        editor.remove("semantic_radius_$reminderId")
        editor.remove("wake_radius_$reminderId")
        editor.remove("place_name_$reminderId")
        editor.remove("place_lat_$reminderId")
        editor.remove("place_lng_$reminderId")
        editor.remove("exit_center_lat_$reminderId")
        editor.remove("exit_center_lng_$reminderId")
        editor.remove("exit_radius_$reminderId")
        editor.remove("geofences_$reminderId")
        editor.remove("active_geofences_$reminderId")
        editor.apply()
    }

    /**
     * Suppression suspendue et transactionnelle des géofences d'un rappel.
     * Attend la confirmation de Google Play Services avant de purger les métadonnées locales.
     */
    suspend fun removeGeofenceSuspend(reminderId: Long) {
        val idsToRemove = getGeofenceIdsForReminder(reminderId)
        try {
            geofencingClient.removeGeofences(idsToRemove).await()
            cleanupPrefsForReminder(reminderId, idsToRemove)
            val links = poiRegistry.getLinksForReminder(reminderId)
            links.forEach { it.state = LinkLifecycleState.COMPLETED }
            Log.d(tag, "removeGeofenceSuspend: Géofences du rappel $reminderId supprimées (${idsToRemove.size} IDs)")
            appLogger.i("GEOFENCE_CLEANUP", "Géofences du rappel $reminderId désarmées (${idsToRemove.size} IDs)", reminderId)
        } catch (e: Exception) {
            Log.w(tag, "Erreur suppression géofences $reminderId via GMS: ${e.message}")
        }
        synchronizeGeofences()
    }

    fun removeGeofence(reminderId: Long) {
        scope.launch {
            removeGeofenceSuspend(reminderId)
        }
    }

    /**
     * Synchronise l'ensemble des géofences matérielles actives auprès de Google Play Services
     * en utilisant le ContextualGeofenceScheduler (Hard Cap 85) et le GeofenceDiffEngine
     * (rotation avec recouvrement sans jamais dépasser 100).
     */
    suspend fun synchronizeGeofences() {
        try {
            val reminderRepo = reminderRepositoryProvider.get()
            val activeReminders = try {
                reminderRepo.observePersonalActive().first()
            } catch (e: Exception) {
                emptyList()
            }

            val linksMap = mutableMapOf<Long, List<ReminderPoiLink>>()
            for (rem in activeReminders) {
                linksMap[rem.id] = poiRegistry.getLinksForReminder(rem.id)
            }

            // Phase 6 : Rafraîchir dynamiquement les contextScores selon la position GPS actuelle
            try {
                val currentLoc = fusedLocationClient.lastLocation.await()
                    ?: fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                if (currentLoc != null) {
                    val distResults = FloatArray(1)
                    for ((_, links) in linksMap) {
                        for (link in links) {
                            val poi = poiRegistry.getPoi(link.poiId) ?: continue
                            Location.distanceBetween(
                                currentLoc.latitude,
                                currentLoc.longitude,
                                poi.latitude,
                                poi.longitude,
                                distResults
                            )
                            val distM = distResults[0]
                            val score = (100 - (distM / 25f).toInt()).coerceIn(20, 95)
                            link.contextScore = score
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "Position non disponible pour rafraîchir contextScores: ${e.message}")
            }

            val settings = settingsRepository.getSettings()
            val desiredFixed = mutableMapOf<String, TrackedGeofence>()
            val desiredStage = mutableMapOf<String, TrackedGeofence>()
            val desiredExit = mutableMapOf<String, TrackedGeofence>()

            for (rem in activeReminders) {
                // a) Lieu fixe
                if (rem.placeCategory == null && rem.placeLat != null && rem.placeLng != null && rem.placeLat != 0.0 && rem.placeLng != 0.0) {
                    val reqId = rem.id.toString()
                    val semanticRadius = rem.placeRadiusM ?: settings.poiDetectionRadiusM.toFloat()
                    val wakeRadius = prefs.getFloat("wake_radius_${rem.id}", semanticRadius)
                    desiredFixed[reqId] = TrackedGeofence(
                        requestId = reqId,
                        poiId = reqId,
                        latitude = rem.placeLat,
                        longitude = rem.placeLng,
                        radiusMeters = wakeRadius,
                        isExitZone = false
                    )
                }

                val trackedIds = prefs.getStringSet("geofences_${rem.id}", emptySet()) ?: emptySet()

                // b) Étape de retour (stage_dest)
                val stageReqId = "${rem.id}_stage_dest"
                if (trackedIds.contains(stageReqId)) {
                    val stageLat = prefs.getFloat("place_lat_${stageReqId}", Float.NaN)
                    val stageLng = prefs.getFloat("place_lng_${stageReqId}", Float.NaN)
                    val stageRadius = prefs.getFloat("radius_${stageReqId}", 350f)
                    if (!stageLat.isNaN() && !stageLng.isNaN()) {
                        desiredStage[stageReqId] = TrackedGeofence(
                            requestId = stageReqId,
                            poiId = stageReqId,
                            latitude = stageLat.toDouble(),
                            longitude = stageLng.toDouble(),
                            radiusMeters = stageRadius,
                            isExitZone = false
                        )
                    }
                }

                // c) Fenêtre glissante (exit_zone)
                val exitReqId = "${rem.id}_exit_zone"
                if (trackedIds.contains(exitReqId)) {
                    val exitLat = prefs.getFloat("exit_center_lat_${rem.id}", Float.NaN)
                    val exitLng = prefs.getFloat("exit_center_lng_${rem.id}", Float.NaN)
                    val exitRadius = prefs.getFloat("exit_radius_${rem.id}", settings.rollingExitRadiusM.toFloat())
                    if (!exitLat.isNaN() && !exitLng.isNaN()) {
                        desiredExit[exitReqId] = TrackedGeofence(
                            requestId = exitReqId,
                            poiId = exitReqId,
                            latitude = exitLat.toDouble(),
                            longitude = exitLng.toDouble(),
                            radiusMeters = exitRadius,
                            isExitZone = true
                        )
                    }
                }
            }

            // Phase 5 : Réserve réelle de 15 slots pour les POIs (Hard cap total desiredMap <= 85)
            val nonPoiSlots = desiredFixed.size + desiredStage.size + desiredExit.size
            val maxPoiBudget = (85 - nonPoiSlots).coerceAtLeast(10)

            val categoryReminders = activeReminders.filter { it.placeCategory != null }
            val schedulerResult = scheduler.schedule(categoryReminders, linksMap, overrideMaxSlots = maxPoiBudget)

            val desiredMap = mutableMapOf<String, TrackedGeofence>()
            desiredMap.putAll(desiredFixed)
            desiredMap.putAll(desiredStage)
            desiredMap.putAll(desiredExit)

            for (poiId in schedulerResult.allocatedPoiIds) {
                val reqId = "geo_${poiId.replace(":", "_").replace("/", "_")}"
                val tracked = poiRegistry.allocateGeofenceForPoi(poiId, reqId)
                desiredMap[reqId] = tracked
            }

            val desiredState = GeofenceStateSnapshot(desiredMap)
            val plan = diffEngine.computeTransitionPlan(actualState, desiredState)

            if (!plan.isValid) {
                Log.e(tag, "synchronizeGeofences: plan invalide (${plan.failureReason})")
                appLogger.e("GEOFENCE_DIFF_ERROR", "Plan de transition invalide: ${plan.failureReason}")
                return
            }

            for (op in plan.operations) {
                when (op) {
                    is DiffOperation.AddBatch -> {
                        val gmsList = op.geofencesToAdd.map { tracked ->
                            if (tracked.isExitZone) {
                                Geofence.Builder()
                                    .setRequestId(tracked.requestId)
                                    .setCircularRegion(tracked.latitude, tracked.longitude, tracked.radiusMeters)
                                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                                    .setNotificationResponsiveness(5000)
                                    .build()
                            } else {
                                val isFixed = desiredFixed.containsKey(tracked.requestId)
                                val responsiveness = if (isFixed) 0 else 1000
                                Geofence.Builder()
                                    .setRequestId(tracked.requestId)
                                    .setCircularRegion(tracked.latitude, tracked.longitude, tracked.radiusMeters)
                                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                                    .setTransitionTypes(
                                        Geofence.GEOFENCE_TRANSITION_ENTER or
                                        Geofence.GEOFENCE_TRANSITION_DWELL or
                                        Geofence.GEOFENCE_TRANSITION_EXIT
                                    )
                                    .setLoiteringDelay(8000)
                                    .setNotificationResponsiveness(responsiveness)
                                    .build()
                            }
                        }
                        val initialTrigger = if (op.geofencesToAdd.all { it.isExitZone }) {
                            0
                        } else {
                            GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL
                        }
                        val request = GeofencingRequest.Builder()
                            .setInitialTrigger(initialTrigger)
                            .addGeofences(gmsList)
                            .build()

                        try {
                            geofencingClient.addGeofences(request, geofencePendingIntent).await()
                            // Phase 3 : Marquer les géofences confirmées par GMS comme ARMED dans le Registry
                            op.geofencesToAdd.forEach { tracked ->
                                if (!tracked.isExitZone && !tracked.requestId.endsWith("_stage_dest") && tracked.requestId != tracked.poiId) {
                                    poiRegistry.markGeofenceArmed(tracked.requestId)
                                }
                            }
                            actualState = diffEngine.reconcileActualState(actualState, op.geofencesToAdd, emptyList())
                            Log.i(tag, "DiffEngine AddBatch: +${op.geofencesToAdd.size} géofences (Total actuel: ${actualState.count}/100)")
                        } catch (e: Exception) {
                            Log.e(tag, "Échec AddBatch: ${e.message}", e)
                        }
                    }
                    is DiffOperation.RemoveBatch -> {
                        try {
                            geofencingClient.removeGeofences(op.requestIdsToRemove).await()
                            op.requestIdsToRemove.forEach { reqId ->
                                poiRegistry.releaseGeofence(reqId)
                            }
                            actualState = diffEngine.reconcileActualState(actualState, emptyList(), op.requestIdsToRemove)
                            Log.i(tag, "DiffEngine RemoveBatch: -${op.requestIdsToRemove.size} géofences (Total actuel: ${actualState.count}/100)")
                        } catch (e: Exception) {
                            Log.e(tag, "Échec RemoveBatch: ${e.message}", e)
                        }
                    }
                }
            }

            // Sauvegarde de l'ensemble des géofences armées actives pour chaque rappel
            val editor = prefs.edit()
            for (rem in activeReminders) {
                val activeKeys = mutableSetOf<String>()
                if (actualState.geofences.containsKey(rem.id.toString())) {
                    activeKeys.add(rem.id.toString())
                }
                val stageKey = "${rem.id}_stage_dest"
                if (actualState.geofences.containsKey(stageKey)) {
                    activeKeys.add(stageKey)
                }
                val exitKey = "${rem.id}_exit_zone"
                if (actualState.geofences.containsKey(exitKey)) {
                    activeKeys.add(exitKey)
                }
                val linkedPoiIds = poiRegistry.getLinksForReminder(rem.id).map { it.poiId }.toSet()
                actualState.geofences.forEach { (reqId, tracked) ->
                    if (linkedPoiIds.contains(tracked.poiId)) {
                        activeKeys.add(reqId)
                    }
                }
                editor.putStringSet("active_geofences_${rem.id}", activeKeys)
            }
            editor.apply()

            appLogger.success("GEOFENCE_SYNCHRONIZED", "${actualState.count} géofences matérielles actives (Ordonnancement respecté, réserve disponible: ${100 - actualState.count})")
        } catch (e: Exception) {
            Log.e(tag, "Erreur dans synchronizeGeofences: ${e.message}", e)
        }
    }

    /**
     * Retourne l'ensemble des Request IDs actuellement armés et surveillés matériellement.
     * Si [reminderId] est spécifié, filtre sur les géofences propres à ce rappel.
     */
    fun getActiveGeofenceRequestIds(reminderId: Long? = null): Set<String> {
        if (reminderId == null) {
            return actualState.geofences.keys
        }
        val links = poiRegistry.getLinksForReminder(reminderId)
        val poiIdsForReminder = links.map { it.poiId }.toSet()
        val activeForReminder = actualState.geofences.filter { (reqId, tracked) ->
            reqId == reminderId.toString() ||
            reqId.startsWith("${reminderId}_") ||
            poiIdsForReminder.contains(tracked.poiId)
        }.keys
        if (activeForReminder.isNotEmpty()) {
            return activeForReminder
        }
        return prefs.getStringSet("active_geofences_$reminderId", emptySet()) ?: emptySet()
    }

    fun resetCooldown(reminderId: Long) {
        triggerCoordinator.resetCooldown(reminderId)
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
