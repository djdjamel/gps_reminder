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
    suspend fun setCommuteStart(lat: Double, lng: Double, label: String)
    suspend fun setCommuteEnd(lat: Double, lng: Double, label: String)
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
        
        // Trajet habituel
        val KEY_COMMUTE_START_LAT = doublePreferencesKey("commute_start_lat")
        val KEY_COMMUTE_START_LNG = doublePreferencesKey("commute_start_lng")
        val KEY_COMMUTE_START_LABEL = stringPreferencesKey("commute_start_label")
        val KEY_COMMUTE_END_LAT = doublePreferencesKey("commute_end_lat")
        val KEY_COMMUTE_END_LNG = doublePreferencesKey("commute_end_lng")
        val KEY_COMMUTE_END_LABEL = stringPreferencesKey("commute_end_label")
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
                commuteStartLat = preferences[PreferencesKeys.KEY_COMMUTE_START_LAT],
                commuteStartLng = preferences[PreferencesKeys.KEY_COMMUTE_START_LNG],
                commuteStartLabel = preferences[PreferencesKeys.KEY_COMMUTE_START_LABEL],
                commuteEndLat = preferences[PreferencesKeys.KEY_COMMUTE_END_LAT],
                commuteEndLng = preferences[PreferencesKeys.KEY_COMMUTE_END_LNG],
                commuteEndLabel = preferences[PreferencesKeys.KEY_COMMUTE_END_LABEL]
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

    override suspend fun clearCommuteRoute() {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.KEY_COMMUTE_START_LAT)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_START_LNG)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_START_LABEL)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_END_LAT)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_END_LNG)
            preferences.remove(PreferencesKeys.KEY_COMMUTE_END_LABEL)
        }
    }
}
