package com.remindly.location

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.remindly.domain.model.PlaceCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

data class NearbyPlace(
    val placeId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float = 0f
)

@Singleton
class NearbyPlacesService @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val placesClient: PlacesClient by lazy {
        if (!Places.isInitialized()) {
            Places.initialize(context, "AIzaSyBCv_6Tt9fu9eLQDwIHiFSYjDiRqZkC8eA")
        }
        Places.createClient(context)
    }

    /**
     * Recherche les établissements d'une catégorie autour d'un point central.
     * Trie les résultats par distance croissante.
     */
    suspend fun searchNearby(
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int,
        category: PlaceCategory,
        maxResults: Int = 20
    ): List<NearbyPlace> = coroutineScope {
        try {
            val centerLatLng = LatLng(centerLat, centerLng)
            val bounds = CircularBounds.newInstance(centerLatLng, radiusMeters.toDouble())

            val request = FindAutocompletePredictionsRequest.builder()
                .setQuery(category.searchQuery)
                .setLocationBias(bounds)
                .build()

            val response = placesClient.findAutocompletePredictions(request).await()
            val predictions = response.autocompletePredictions.take(maxResults)

            val placeFields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG)

            val placesDeferred = predictions.map { prediction ->
                async {
                    try {
                        val fetchRequest = FetchPlaceRequest.newInstance(prediction.placeId, placeFields)
                        val fetchResponse = placesClient.fetchPlace(fetchRequest).await()
                        val place = fetchResponse.place
                        val latLng = place.latLng

                        if (latLng != null) {
                            val distance = calculateDistance(centerLat, centerLng, latLng.latitude, latLng.longitude)
                            NearbyPlace(
                                placeId = place.id ?: prediction.placeId,
                                name = place.name ?: prediction.getPrimaryText(null).toString(),
                                latitude = latLng.latitude,
                                longitude = latLng.longitude,
                                distanceMeters = distance
                            )
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            placesDeferred.awaitAll()
                .filterNotNull()
                .sortedBy { it.distanceMeters }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Recherche les établissements d'une catégorie le long d'un itinéraire entre 2 points.
     * Échantillonne des waypoints le long du trajet et fusionne/déduplique les résultats.
     */
    suspend fun searchAlongRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        category: PlaceCategory,
        sampleCount: Int = 5,
        radiusPerPointMeters: Int = 600
    ): List<NearbyPlace> = coroutineScope {
        try {
            val waypoints = generateWaypoints(startLat, startLng, endLat, endLng, sampleCount)
            val allPlaces = mutableListOf<NearbyPlace>()
            val seenPlaceIds = mutableSetOf<String>()

            for (point in waypoints) {
                val found = searchNearby(
                    centerLat = point.latitude,
                    centerLng = point.longitude,
                    radiusMeters = radiusPerPointMeters,
                    category = category,
                    maxResults = 6
                )

                for (place in found) {
                    if (seenPlaceIds.add(place.placeId)) {
                        allPlaces.add(place)
                    }
                }
            }

            allPlaces.take(25)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Génère N points équidistants entre le départ et l'arrivée
     */
    private fun generateWaypoints(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        count: Int
    ): List<LatLng> {
        val points = mutableListOf<LatLng>()
        for (i in 0..count) {
            val fraction = i.toDouble() / count
            val lat = startLat + fraction * (endLat - startLat)
            val lng = startLng + fraction * (endLng - startLng)
            points.add(LatLng(lat, lng))
        }
        return points
    }

    /**
     * Calcul de la distance haversine en mètres
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // mètres
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }
}
