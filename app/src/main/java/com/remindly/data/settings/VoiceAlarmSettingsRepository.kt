package com.remindly.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
}

class VoiceAlarmSettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : VoiceAlarmSettingsRepository {

    private object PreferencesKeys {
        val KEY_VOLUME = floatPreferencesKey("voice_volume")
        val KEY_REPEAT_COUNT = intPreferencesKey("voice_repeat_count")
        val KEY_VIBRATE = booleanPreferencesKey("voice_vibrate")
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
                vibrate = preferences[PreferencesKeys.KEY_VIBRATE] ?: true
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
}
