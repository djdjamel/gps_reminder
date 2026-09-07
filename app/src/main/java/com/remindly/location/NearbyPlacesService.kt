package com.remindly.location

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.remindly.domain.model.PlaceCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    private val apiKey = "AIzaSyBCv_6Tt9fu9eLQDwIHiFSYjDiRqZkC8eA"
    private val tag = "NearbyPlacesService"

    /**
     * Recherche les établissements d'une catégorie autour d'un point central.
     * Utilise Google Places nearbysearch HTTP officiel avec les types officiels (supermarket, etc.).
     */
    suspend fun searchNearby(
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int,
        category: PlaceCategory,
        maxResults: Int = 20
    ): List<NearbyPlace> = supervisorScope {
        try {
            val allTypes = listOf(category.primaryType) + category.secondaryTypes
            val placesMap = mutableMapOf<String, NearbyPlace>()

            // 1. Recherche par types officiels Google (ex: supermarket + convenience_store)
            for (type in allTypes) {
                val found = fetchNearbyPlacesHttp(centerLat, centerLng, radiusMeters, type = type, keyword = null)
                for (p in found) {
                    placesMap[p.placeId] = p
                }
            }

            // 2. Si aucun résultat (zone avec types moins précis), tenter avec les mots-clés
            if (placesMap.isEmpty() && category.keywords.isNotEmpty()) {
                for (kw in category.keywords.take(2)) {
                    val found = fetchNearbyPlacesHttp(centerLat, centerLng, radiusMeters, type = null, keyword = kw)
                    for (p in found) {
                        placesMap[p.placeId] = p
                    }
                }
            }

            val sorted = placesMap.values.sortedBy { it.distanceMeters }.take(maxResults)
            Log.d(tag, "searchNearby ($centerLat, $centerLng) category=${category.id} -> ${sorted.size} POIs trouvés")
            sorted
        } catch (t: Throwable) {
            Log.e(tag, "Erreur searchNearby: ${t.message}", t)
            emptyList()
        }
    }

    /**
     * Recherche les établissements d'une catégorie le long d'une liste de waypoints (polyline de trajet).
     * Échantillonne automatiquement les waypoints pour couvrir le trajet tous les 1 à 1.5 km.
     */
    suspend fun searchAlongWaypoints(
        waypoints: List<LatLng>,
        category: PlaceCategory,
        radiusPerPointMeters: Int = 1000
    ): List<NearbyPlace> = supervisorScope {
        try {
            if (waypoints.isEmpty()) return@supervisorScope emptyList()

            // Filtrer les waypoints pour espacer les requêtes d'au moins 1200 mètres
            val filteredWaypoints = downsampleWaypoints(waypoints, minDistanceMeters = 1200f)
            Log.d(tag, "searchAlongWaypoints: ${waypoints.size} points initiaux réduits à ${filteredWaypoints.size} waypoints de recherche")

            val placesMap = mutableMapOf<String, NearbyPlace>()

            val deferredResults = filteredWaypoints.map { point ->
                async {
                    searchNearby(
                        centerLat = point.latitude,
                        centerLng = point.longitude,
                        radiusMeters = radiusPerPointMeters,
                        category = category,
                        maxResults = 8
                    )
                }
            }

            val resultsList = deferredResults.awaitAll()
            for (list in resultsList) {
                for (place in list) {
                    placesMap[place.placeId] = place
                }
            }

            val totalFound = placesMap.values.toList()
            Log.d(tag, "searchAlongWaypoints TOTAL: ${totalFound.size} POIs uniques trouvés le long du trajet")
            totalFound.take(30)
        } catch (t: Throwable) {
            Log.e(tag, "Erreur searchAlongWaypoints: ${t.message}", t)
            emptyList()
        }
    }

    /**
     * Recherche le long d'une ligne droite entre départ et arrivée
     */
    suspend fun searchAlongRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        category: PlaceCategory,
        sampleCount: Int = 6,
        radiusPerPointMeters: Int = 1000
    ): List<NearbyPlace> {
        val waypoints = generateWaypoints(startLat, startLng, endLat, endLng, sampleCount)
        return searchAlongWaypoints(waypoints, category, radiusPerPointMeters)
    }

    /**
     * Appel HTTP direct à Google Places nearbysearch
     */
    private suspend fun fetchNearbyPlacesHttp(
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int,
        type: String?,
        keyword: String?
    ): List<NearbyPlace> = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder("https://maps.googleapis.com/maps/api/place/nearbysearch/json")
            sb.append("?location=$centerLat,$centerLng")
            sb.append("&radius=$radiusMeters")
            sb.append("&language=fr")
            if (!type.isNullOrBlank()) {
                sb.append("&type=").append(URLEncoder.encode(type, "UTF-8"))
            }
            if (!keyword.isNullOrBlank()) {
                sb.append("&keyword=").append(URLEncoder.encode(keyword, "UTF-8"))
            }
            sb.append("&key=").append(apiKey)

            val url = URL(sb.toString())
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 7000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val status = json.optString("status")

                if (status == "OK" || status == "ZERO_RESULTS") {
                    val resultsArray = json.optJSONArray("results") ?: return@withContext emptyList()
                    val places = mutableListOf<NearbyPlace>()

                    for (i in 0 until resultsArray.length()) {
                        val item = resultsArray.getJSONObject(i)
                        val placeId = item.optString("place_id")
                        val name = item.optString("name")
                        val geometry = item.optJSONObject("geometry")
                        val location = geometry?.optJSONObject("location")

                        if (location != null && placeId.isNotBlank()) {
                            val lat = location.getDouble("lat")
                            val lng = location.getDouble("lng")
                            val distance = calculateDistance(centerLat, centerLng, lat, lng)
                            places.add(
                                NearbyPlace(
                                    placeId = placeId,
                                    name = name,
                                    latitude = lat,
                                    longitude = lng,
                                    distanceMeters = distance
                                )
                            )
                        }
                    }
                    return@withContext places
                } else {
                    Log.w(tag, "nearbysearch returned status: $status")
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "fetchNearbyPlacesHttp error: ${t.message}")
        }
        emptyList()
    }

    /**
     * Sous-échantillonne une liste de points pour garantir un espacement minimal
     */
    private fun downsampleWaypoints(points: List<LatLng>, minDistanceMeters: Float): List<LatLng> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf<LatLng>()
        var lastPoint = points.first()
        result.add(lastPoint)

        for (i in 1 until points.size) {
            val current = points[i]
            val distance = calculateDistance(lastPoint.latitude, lastPoint.longitude, current.latitude, current.longitude)
            if (distance >= minDistanceMeters) {
                result.add(current)
                lastPoint = current
            }
        }

        // Toujours inclure le point de fin si non proche du dernier ajouté
        val finalPoint = points.last()
        val distToLast = calculateDistance(result.last().latitude, result.last().longitude, finalPoint.latitude, finalPoint.longitude)
        if (distToLast > minDistanceMeters / 2) {
            result.add(finalPoint)
        }
        return result
    }

    /**
     * Génère N points équidistants entre départ et arrivée
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
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }
}
