package com.remindly.location

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

data class RouteOption(
    val id: Int,
    val summary: String,
    val distanceText: String,
    val durationText: String,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val points: List<LatLng>,
    val encodedPolyline: String
)

@Singleton
class DirectionsService @Inject constructor() {

    private val apiKey = "AIzaSyBCv_6Tt9fu9eLQDwIHiFSYjDiRqZkC8eA"

    suspend fun getRoutes(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): List<RouteOption> = withContext(Dispatchers.IO) {
        try {
            val urlString = "https://maps.googleapis.com/maps/api/directions/json" +
                    "?origin=$startLat,$startLng" +
                    "&destination=$endLat,$endLng" +
                    "&alternatives=true" +
                    "&mode=driving" +
                    "&language=fr" +
                    "&key=$apiKey"

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val status = json.optString("status")

                if (status == "OK") {
                    val routesArray = json.getJSONArray("routes")
                    val routes = mutableListOf<RouteOption>()

                    for (i in 0 until routesArray.length()) {
                        val routeObj = routesArray.getJSONObject(i)
                        val summary = routeObj.optString("summary").ifBlank { "Itinéraire ${i + 1}" }
                        val overviewPolyline = routeObj.getJSONObject("overview_polyline").getString("points")
                        val decodedPoints = decodePolyline(overviewPolyline)

                        val legs = routeObj.getJSONArray("legs")
                        var totalDistanceMeters = 0L
                        var totalDurationSeconds = 0L
                        var distanceText = ""
                        var durationText = ""

                        for (j in 0 until legs.length()) {
                            val leg = legs.getJSONObject(j)
                            val distance = leg.getJSONObject("distance")
                            val duration = leg.getJSONObject("duration")
                            totalDistanceMeters += distance.optLong("value", 0L)
                            totalDurationSeconds += duration.optLong("value", 0L)
                            distanceText = distance.optString("text", "")
                            durationText = duration.optString("text", "")
                        }

                        routes.add(
                            RouteOption(
                                id = i,
                                summary = summary,
                                distanceText = distanceText,
                                durationText = durationText,
                                distanceMeters = totalDistanceMeters,
                                durationSeconds = totalDurationSeconds,
                                points = decodedPoints,
                                encodedPolyline = overviewPolyline
                            )
                        )
                    }

                    if (routes.isNotEmpty()) {
                        return@withContext routes
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback si l'API Directions n'est pas activée ou hors ligne
        listOf(createFallbackRoute(startLat, startLng, endLat, endLng))
    }

    private fun createFallbackRoute(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): RouteOption {
        val points = mutableListOf<LatLng>()
        val count = 20
        for (i in 0..count) {
            val fraction = i.toDouble() / count
            val lat = startLat + fraction * (endLat - startLat)
            val lng = startLng + fraction * (endLng - startLng)
            points.add(LatLng(lat, lng))
        }

        val distance = calculateDistance(startLat, startLng, endLat, endLng)
        val distanceKm = "%.1f km".format(distance / 1000f)
        val durationMin = "${max(1, (distance / 1000f / 40f * 60f).roundToInt())} min"

        return RouteOption(
            id = 0,
            summary = "Trajet direct",
            distanceText = distanceKm,
            durationText = durationMin,
            distanceMeters = distance.toLong(),
            durationSeconds = (distance / 1000f / 40f * 3600f).toLong(),
            points = points,
            encodedPolyline = encodePolyline(points)
        )
    }

    /**
     * Décode une chaîne de polyline encodée Google en liste de LatLng
     */
    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val p = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }
        return poly
    }

    /**
     * Encode une liste de LatLng en format polyline
     */
    fun encodePolyline(points: List<LatLng>): String {
        val result = StringBuilder()
        var lastLat = 0
        var lastLng = 0

        for (point in points) {
            val lat = (point.latitude * 1e5).roundToInt()
            val lng = (point.longitude * 1e5).roundToInt()

            encodeValue(lat - lastLat, result)
            encodeValue(lng - lastLng, result)

            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encodeValue(v: Int, result: StringBuilder) {
        var value = if (v < 0) (v shl 1).inv() else v shl 1
        while (value >= 0x20) {
            result.append(((0x20 or (value and 0x1f)) + 63).toChar())
            value = value shr 5
        }
        result.append((value + 63).toChar())
    }

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
