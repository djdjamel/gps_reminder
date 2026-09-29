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
import java.util.Locale
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
    private val apiKey = com.remindly.BuildConfig.MAPS_API_KEY.ifEmpty { "AIzaSyBCv_6Tt9fu9eLQDwIHiFSYjDiRqZkC8eA" }
    private val tag = "NearbyPlacesService"

    companion object {
        /**
         * Normalisation Unicode stricte pour la recherche textuelle :
         * - trim
         * - décomposition NFD + suppression des diacritiques (accents)
         * - minuscules
         * - réduction des espaces multiples
         */
        fun normalizeForSearch(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return ""
            val normalized = java.text.Normalizer.normalize(trimmed, java.text.Normalizer.Form.NFD)
            val withoutDiacritics = normalized.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            return withoutDiacritics.lowercase().replace("\\s+".toRegex(), " ")
        }
    }

    /**
     * Recherche les établissements d'une catégorie autour d'un point central.
     * Utilise conjointement Google Places nearbysearch HTTP officiel et OpenStreetMap (Overpass API)
     * pour garantir une couverture exhaustive (ex: 100% des pharmacies locales en Algérie/zones denses).
     * Si un mot-clé/enseigne est fourni, le filtrage local strict sur le titre du commerce fait foi.
     */
    suspend fun searchNearby(
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int,
        category: PlaceCategory,
        keyword: String? = null,
        maxResults: Int = 60
    ): List<NearbyPlace> = supervisorScope {
        try {
            val cleanKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
            val allTypes = listOf(category.primaryType) + category.secondaryTypes
            val placesMap = mutableMapOf<String, NearbyPlace>()

            // 1. Requête Google Places en asynchrone (le keyword aide Google à remonter les bons candidats)
            val googleDeferred = async {
                val gMap = mutableMapOf<String, NearbyPlace>()
                for (type in allTypes) {
                    val found = fetchNearbyPlacesHttp(centerLat, centerLng, radiusMeters, type = type, keyword = cleanKeyword)
                    for (p in found) {
                        gMap[p.placeId] = p
                    }
                }
                if (gMap.isEmpty() && category.keywords.isNotEmpty()) {
                    for (kw in category.keywords.take(2)) {
                        val searchKw = if (cleanKeyword != null) "$cleanKeyword $kw" else kw
                        val found = fetchNearbyPlacesHttp(centerLat, centerLng, radiusMeters, type = null, keyword = searchKw)
                        for (p in found) {
                            gMap[p.placeId] = p
                        }
                    }
                }
                gMap.values.toList()
            }

            // 2. Requête OpenStreetMap / Overpass en asynchrone (exhaustivité des commerces de quartier)
            val overpassDeferred = async {
                fetchOverpassPlacesHttp(centerLat, centerLng, radiusMeters, category)
            }

            val googleResults = try { googleDeferred.await() } catch (t: Throwable) {
                Log.w(tag, "Google Places fetch error: ${t.message}")
                emptyList()
            }
            val overpassResults = try { overpassDeferred.await() } catch (t: Throwable) {
                Log.w(tag, "Overpass fetch error: ${t.message}")
                emptyList()
            }

            // 3. Fusionner les résultats : d'abord Google Places
            for (p in googleResults) {
                placesMap[p.placeId] = p
            }

            // Ajouter les POIs Overpass non encore répertoriés (dédoublonnage à 35 mètres)
            for (op in overpassResults) {
                val isDuplicate = placesMap.values.any { existing ->
                    calculateDistance(existing.latitude, existing.longitude, op.latitude, op.longitude) < 35f
                }
                if (!isDuplicate) {
                    placesMap[op.placeId] = op
                }
            }

            // 4. FILTRE LOCAL STRICT FAISANT FOI (uniquement sur le titre du commerce place.name)
            val candidatePlaces = placesMap.values
            val strictlyFiltered = if (cleanKeyword == null) {
                candidatePlaces
            } else {
                val targetNormalized = normalizeForSearch(cleanKeyword)
                candidatePlaces.filter { normalizeForSearch(it.name).contains(targetNormalized) }
            }

            val sorted = strictlyFiltered.sortedBy { it.distanceMeters }.take(maxResults)
            Log.d(tag, "searchNearby ($centerLat, $centerLng) category=${category.id}, keyword=$cleanKeyword -> ${sorted.size} POIs uniques trouvés (filtrés depuis ${candidatePlaces.size} candidats)")
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
        keyword: String? = null,
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
                        keyword = keyword,
                        maxResults = 12
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
            totalFound.take(60)
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
        keyword: String? = null,
        sampleCount: Int = 6,
        radiusPerPointMeters: Int = 1000
    ): List<NearbyPlace> {
        val waypoints = generateWaypoints(startLat, startLng, endLat, endLng, sampleCount)
        return searchAlongWaypoints(waypoints, category, keyword, radiusPerPointMeters)
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

    /**
     * Requête Overpass API (OpenStreetMap) pour récupérer tous les commerces de la catégorie spécifiée
     */
    private suspend fun fetchOverpassPlacesHttp(
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int,
        category: PlaceCategory
    ): List<NearbyPlace> = withContext(Dispatchers.IO) {
        try {
            val filter = getOverpassFilter(category)
            val formattedFilter = String.format(
                Locale.US,
                filter,
                radiusMeters, centerLat, centerLng,
                radiusMeters, centerLat, centerLng
            )
            val query = "[out:json][timeout:10];($formattedFilter);out center;"
            val url = URL("https://overpass-api.de/api/interpreter")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "RemindlyApp/1.0 (Android)")

            val postData = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { os ->
                os.write(postData.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val elements = json.optJSONArray("elements") ?: return@withContext emptyList()
                val places = mutableListOf<NearbyPlace>()

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val type = el.optString("type")
                    val id = el.optLong("id")
                    val tags = el.optJSONObject("tags")

                    var lat = el.optDouble("lat", Double.NaN)
                    var lon = el.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) {
                        val center = el.optJSONObject("center")
                        if (center != null) {
                            lat = center.optDouble("lat", Double.NaN)
                            lon = center.optDouble("lon", Double.NaN)
                        }
                    }

                    if (!lat.isNaN() && !lon.isNaN()) {
                        val osmName = tags?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: tags?.optString("name:fr")?.takeIf { it.isNotBlank() }
                            ?: tags?.optString("name:ar")?.takeIf { it.isNotBlank() }
                            ?: category.displayName
                        val dist = calculateDistance(centerLat, centerLng, lat, lon)
                        places.add(
                            NearbyPlace(
                                placeId = "osm_${type}_$id",
                                name = osmName,
                                latitude = lat,
                                longitude = lon,
                                distanceMeters = dist
                            )
                        )
                    }
                }
                Log.d(tag, "Overpass OSM: ${places.size} POIs trouvés pour ${category.id}")
                return@withContext places
            } else {
                Log.w(tag, "Overpass API returned HTTP $responseCode")
            }
        } catch (t: Throwable) {
            Log.w(tag, "Overpass API fetch error (repli Google Places): ${t.message}")
        }
        emptyList()
    }

    private fun getOverpassFilter(category: PlaceCategory): String {
        return when (category) {
            PlaceCategory.PHARMACY -> """
                node["amenity"="pharmacy"](around:%d,%f,%f);
                way["amenity"="pharmacy"](around:%d,%f,%f);
            """.trimIndent()
            PlaceCategory.SUPERMARKET -> """
                node["shop"~"supermarket|convenience|grocery"](around:%d,%f,%f);
                way["shop"~"supermarket|convenience|grocery"](around:%d,%f,%f);
            """.trimIndent()
            PlaceCategory.BAKERY -> """
                node["shop"="bakery"](around:%d,%f,%f);
                way["shop"="bakery"](around:%d,%f,%f);
            """.trimIndent()
            PlaceCategory.GAS_STATION -> """
                node["amenity"="fuel"](around:%d,%f,%f);
                way["amenity"="fuel"](around:%d,%f,%f);
            """.trimIndent()
            PlaceCategory.ATM -> """
                node["amenity"~"atm|bank"](around:%d,%f,%f);
                way["amenity"~"atm|bank"](around:%d,%f,%f);
            """.trimIndent()
            PlaceCategory.RESTAURANT -> """
                node["amenity"~"restaurant|cafe|fast_food"](around:%d,%f,%f);
                way["amenity"~"restaurant|cafe|fast_food"](around:%d,%f,%f);
            """.trimIndent()
        }
    }
}
