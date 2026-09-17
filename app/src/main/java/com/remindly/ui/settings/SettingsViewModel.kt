package com.remindly.ui.settings

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.remindly.auth.AuthManager
import com.remindly.data.settings.VoiceAlarmSettings
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: VoiceAlarmSettingsRepository,
    private val authManager: AuthManager,
    private val passiveLocationManager: com.remindly.location.PassiveLocationManager,
    private val vehicleModeManager: com.remindly.location.VehicleModeManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val settings: StateFlow<VoiceAlarmSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VoiceAlarmSettings()
        )

    val currentUser: StateFlow<FirebaseUser?> = authManager.currentUserState

    private val _isTestingVolume = MutableStateFlow(false)
    val isTestingVolume: StateFlow<Boolean> = _isTestingVolume.asStateFlow()

    private var previewPlayer: MediaPlayer? = null

    fun updateVolume(volume: Float) {
        viewModelScope.launch {
            settingsRepository.setVolume(volume)
        }
    }

    fun updateRepeatCount(repeatCount: Int) {
        viewModelScope.launch {
            settingsRepository.setRepeatCount(repeatCount)
        }
    }

    fun updateVibrate(vibrate: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVibrate(vibrate)
        }
    }

    fun updateAppLanguage(languageCode: String) {
        viewModelScope.launch {
            settingsRepository.setAppLanguage(languageCode)
        }
    }

    fun setHasSelectedLanguage(selected: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHasSelectedLanguage(selected)
        }
    }

    fun updateAppTheme(theme: String) {
        viewModelScope.launch {
            settingsRepository.setAppTheme(theme)
        }
    }

    fun updatePoiRadius(radiusKm: Int) {
        viewModelScope.launch {
            settingsRepository.setPoiSearchRadius(radiusKm)
        }
    }

    fun updatePoiDetectionRadiusM(radiusM: Int) {
        viewModelScope.launch {
            settingsRepository.setPoiDetectionRadiusM(radiusM)
        }
    }

    fun updateRollingExitRadiusM(radiusM: Int) {
        viewModelScope.launch {
            settingsRepository.setRollingExitRadiusM(radiusM)
        }
    }

    fun updateGeofenceCooldownSeconds(seconds: Int) {
        viewModelScope.launch {
            settingsRepository.setGeofenceCooldownSeconds(seconds)
        }
    }

    fun updateSmartGeofenceFiltering(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSmartGeofenceFiltering(enabled)
        }
    }

    fun updateMaxFilterSpeedKmh(speedKmh: Int) {
        viewModelScope.launch {
            settingsRepository.setMaxFilterSpeedKmh(speedKmh)
        }
    }

    fun updateAnnouncePlaceByVoice(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAnnouncePlaceByVoice(enabled)
        }
    }

    fun updateReadTextRemindersAloud(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setReadTextRemindersAloud(enabled)
        }
    }

    fun setCommuteStart(lat: Double, lng: Double, label: String) {
        viewModelScope.launch {
            settingsRepository.setCommuteStart(lat, lng, label)
        }
    }

    fun setCommuteEnd(lat: Double, lng: Double, label: String) {
        viewModelScope.launch {
            settingsRepository.setCommuteEnd(lat, lng, label)
        }
    }

    fun clearCommuteRoute() {
        viewModelScope.launch {
            settingsRepository.clearCommuteRoute()
        }
    }

    fun testVolume() {
        if (_isTestingVolume.value) {
            stopPreview()
            return
        }

        viewModelScope.launch {
            _isTestingVolume.value = true
            try {
                previewPlayer?.release()
                previewPlayer = null

                val currentVolume = settings.value.volume
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

                if (uri != null) {
                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(context, uri)
                        prepare()
                        setVolume(currentVolume, currentVolume)
                        setOnCompletionListener {
                            it.release()
                            previewPlayer = null
                            _isTestingVolume.value = false
                        }
                        start()
                    }
                    previewPlayer = player
                } else {
                    playToneFallback(currentVolume)
                }
            } catch (e: Exception) {
                playToneFallback(settings.value.volume)
            }
        }
    }

    private fun playToneFallback(volume: Float) {
        try {
            val volumePercent = (volume * 100).toInt().coerceIn(1, 100)
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, volumePercent)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
        } catch (_: Exception) {}
        _isTestingVolume.value = false
    }

    private fun stopPreview() {
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
            previewPlayer = null
        } catch (_: Exception) {}
        _isTestingVolume.value = false
    }

    fun updatePassiveLocationMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPassiveLocationMonitoring(enabled)
            if (enabled) {
                passiveLocationManager.start()
            } else {
                passiveLocationManager.stop()
            }
        }
    }

    fun updateAutoVehicleDetection(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoVehicleDetection(enabled)
            if (enabled) {
                vehicleModeManager.startMonitoring()
            } else {
                vehicleModeManager.stopMonitoring()
            }
        }
    }

    fun hasActivityRecognitionPermission(): Boolean {
        return vehicleModeManager.hasActivityRecognitionPermission()
    }

    fun signOut() {
        authManager.signOut()
    }

    override fun onCleared() {
        super.onCleared()
        stopPreview()
    }
}
