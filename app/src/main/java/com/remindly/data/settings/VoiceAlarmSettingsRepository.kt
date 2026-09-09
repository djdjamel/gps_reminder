package com.remindly.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_alarm_settings")

interface VoiceAlarmSettingsRepository {
    val settingsFlow: Flow<VoiceAlarmSettings>
    suspend fun getSettings(): VoiceAlarmSettings
    suspend fun setVolume(volume: Float)
    suspend fun setRepeatCount(repeatCount: Int)
    suspend fun setVibrate(vibrate: Boolean)
    suspend fun setPoiSearchRadius(radiusKm: Int)
    suspend fun setPoiDetectionRadiusM(radiusM: Int)
    suspend fun setRollingExitRadiusM(radiusM: Int)
    suspend fun setGeofenceCooldownSeconds(seconds: Int)
    suspend fun setAnnouncePlaceByVoice(enabled: Boolean)
    suspend fun setReadTextRemindersAloud(enabled: Boolean)
    suspend fun setCommuteStart(lat: Double, lng: Double, label: String)
    suspend fun setCommuteEnd(lat: Double, lng: Double, label: String)
    suspend fun setCommuteRoute(
        startLat: Double,
        startLng: Double,
        startLabel: String,
        endLat: Double,
        endLng: Double,
        endLabel: String,
        polyline: String?
    )
    suspend fun clearCommuteRoute()
}

class VoiceAlarmSettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : VoiceAlarmSettingsRepository {

    private object PreferencesKeys {
        val KEY_VOLUME = floatPreferencesKey("voice_volume")
        val KEY_REPEAT_COUNT = intPreferencesKey("voice_repeat_count")
        val KEY_VIBRATE = booleanPreferencesKey("voice_vibrate")
        val KEY_POI_RADIUS = intPreferencesKey("poi_search_radius_km")
        val KEY_POI_DETECTION_RADIUS_M = intPreferencesKey("poi_detection_radius_m")
        val KEY_ROLLING_EXIT_RADIUS_M = intPreferencesKey("rolling_exit_radius_m")
        val KEY_GEOFENCE_COOLDOWN_SECONDS = intPreferencesKey("geofence_cooldown_seconds")
        val KEY_ANNOUNCE_PLACE_BY_VOICE = booleanPreferencesKey("announce_place_by_voice")
        val KEY_READ_TEXT_REMINDERS_ALOUD = booleanPreferencesKey("read_text_reminders_aloud")
        
        // Trajet habituel
        val KEY_COMMUTE_START_LAT = doublePreferencesKey("commute_start_lat")
        val KEY_COMMUTE_START_LNG = doublePreferencesKey("commute_start_lng")
        val KEY_COMMUTE_START_LABEL = stringPreferencesKey("commute_start_label")
        val KEY_COMMUTE_END_LAT = doublePreferencesKey("commute_end_lat")
        val KEY_COMMUTE_END_LNG = doublePreferencesKey("commute_end_lng")
        val KEY_COMMUTE_END_LABEL = stringPreferencesKey("commute_end_label")
        val KEY_COMMUTE_ROUTE_POLYLINE = stringPreferencesKey("commute_route_polyline")
    }

    override val settingsFlow: Flow<VoiceAlarmSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            VoiceAlarmSettings(
                volume = preferences[PreferencesKeys.KEY_VOLUME] ?: 1.0f,
                repeatCount = preferences[PreferencesKeys.KEY_REPEAT_COUNT] ?: 1,
                vibrate = preferences[PreferencesKeys.KEY_VIBRATE] ?: true,
                poiSearchRadiusKm = preferences[PreferencesKeys.KEY_POI_RADIUS] ?: 3,
                poiDetectionRadiusM = preferences[PreferencesKeys.KEY_POI_DETECTION_RADIUS_M] ?: 450,
                announcePlaceByVoice = preferences[PreferencesKeys.KEY_ANNOUNCE_PLACE_BY_VOICE] ?: true,
                readTextRemindersAloud = preferences[PreferencesKeys.KEY_READ_TEXT_REMINDERS_ALOUD] ?: true,
                rollingExitRadiusM = preferences[PreferencesKeys.KEY_ROLLING_EXIT_RADIUS_M] ?: 2500,
                geofenceCooldownSeconds = preferences[PreferencesKeys.KEY_GEOFENCE_COOLDOWN_SECONDS] ?: 15,
                commuteStartLat = preferences[PreferencesKeys.KEY_COMMUTE_START_LAT],
                commuteStartLng = preferences[PreferencesKeys.KEY_COMMUTE_START_LNG],
                commuteStartLabel = preferences[PreferencesKeys.KEY_COMMUTE_START_LABEL],
                commuteEndLat = preferences[PreferencesKeys.KEY_COMMUTE_END_LAT],
                commuteEndLng = preferences[PreferencesKeys.KEY_COMMUTE_END_LNG],
                commuteEndLabel = preferences[PreferencesKeys.KEY_COMMUTE_END_LABEL],
                commuteRoutePolyline = preferences[PreferencesKeys.KEY_COMMUTE_ROUTE_POLYLINE]
            )
        }

    override suspend fun getSettings(): VoiceAlarmSettings {
        return settingsFlow.first()
    }

    override suspend fun setVolume(volume: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_VOLUME] = volume.coerceIn(0.05f, 1.0f)
        }
    }

    override suspend fun setRepeatCount(repeatCount: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_REPEAT_COUNT] = repeatCount
        }
    }

    override suspend fun setVibrate(vibrate: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_VIBRATE] = vibrate
        }
    }

    override suspend fun setPoiSearchRadius(radiusKm: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_POI_RADIUS] = radiusKm.coerceIn(1, 10)
        }
    }

    override suspend fun setPoiDetectionRadiusM(radiusM: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_POI_DETECTION_RADIUS_M] = radiusM.coerceIn(150, 2000)
        }
    }

    override suspend fun setRollingExitRadiusM(radiusM: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_ROLLING_EXIT_RADIUS_M] = radiusM.coerceIn(1000, 10000)
        }
    }

    override suspend fun setGeofenceCooldownSeconds(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_GEOFENCE_COOLDOWN_SECONDS] = seconds.coerceIn(5, 300)
        }
    }

    override suspend fun setAnnouncePlaceByVoice(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_ANNOUNCE_PLACE_BY_VOICE] = enabled
        }
    }

    override suspend fun setReadTextRemindersAloud(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_READ_TEXT_REMINDERS_ALOUD] = enabled
        }
    }

    override suspend fun setCommuteStart(lat: Double, lng: Double, label: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_COMMUTE_START_LAT] = lat
            preferences[PreferencesKeys.KEY_COMMUTE_START_LNG] = lng
            preferences[PreferencesKeys.KEY_COMMUTE_START_LABEL] = label
        }
    }

    override suspend fun setCommuteEnd(lat: Double, lng: Double, label: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_COMMUTE_END_LAT] = lat
            preferences[PreferencesKeys.KEY_COMMUTE_END_LNG] = lng
            preferences[PreferencesKeys.KEY_COMMUTE_END_LABEL] = label
        }
    }

    override suspend fun setCommuteRoute(
        startLat: Double,
        startLng: Double,
        startLabel: String,
        endLat: Double,
        endLng: Double,
        endLabel: String,
        polyline: String?
    ) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_COMMUTE_START_LAT] = startLat
            preferences[PreferencesKeys.KEY_COMMUTE_START_LNG] = startLng
            preferences[PreferencesKeys.KEY_COMMUTE_START_LABEL] = startLabel
            preferences[PreferencesKeys.KEY_COMMUTE_END_LAT] = endLat
            preferences[PreferencesKeys.KEY_COMMUTE_END_LNG] = endLng
            preferences[PreferencesKeys.KEY_COMMUTE_END_LABEL] = endLabel
            if (polyline != null) {
                preferences[PreferencesKeys.KEY_COMMUTE_ROUTE_POLYLINE] = polyline
            } else {
                preferences.remove(PreferencesKeys.KEY_COMMUTE_ROUTE_POLYLINE)
            }
        }
    }

    override suspend fun clearCommuteRoute() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.KEY_COMMUTE_START_LAT)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_START_LNG)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_START_LABEL)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_END_LAT)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_END_LNG)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_END_LABEL)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_ROUTE_POLYLINE)
        }
    }
}
