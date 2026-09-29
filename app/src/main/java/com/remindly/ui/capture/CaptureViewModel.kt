package com.remindly.ui.capture

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remindly.data.repo.ReminderRepository
import com.remindly.domain.model.Attachment
import com.remindly.domain.model.AttachmentType
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import com.remindly.location.GeofenceManager
import com.remindly.media.AttachmentStore
import com.remindly.media.AudioRecorderManager
import com.remindly.time.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CaptureUiState(
    val text: String = "",
    val isSaving: Boolean = false,
    // Trigger temporel
    val triggerTimeMillis: Long? = null,
    // Trigger géographique
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    val placeRadiusM: Float? = null,
    val placeLabel: String? = null,
    val placeCategory: String? = null,
    val categoryKeyword: String? = null,
    val categoryRefType: String? = null,
    val commuteDirection: String? = null,
    val placeActiveFromMillis: Long? = null,
    // Médias
    val imageUris: List<Uri> = emptyList(),
    val audioPath: String? = null,
    val isRecording: Boolean = false,
    val isRepeating: Boolean = false,
    val errorMessage: String? = null,
    val voiceFeedbackMessage: String? = null
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val reminderRepository: ReminderRepository,
    private val attachmentStore: AttachmentStore,
    private val alarmScheduler: AlarmScheduler,
    private val geofenceManager: GeofenceManager,
    private val savedPlaceRepository: com.remindly.data.repo.SavedPlaceRepository,
    private val settingsRepository: com.remindly.data.settings.VoiceAlarmSettingsRepository,
    private val appLogger: com.remindly.util.AppLogger,
    @ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    val savedPlaces: StateFlow<List<com.remindly.domain.model.SavedPlace>> = savedPlaceRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val settings: StateFlow<com.remindly.data.settings.VoiceAlarmSettings> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = com.remindly.data.settings.VoiceAlarmSettings()
        )

    private val audioRecorder = AudioRecorderManager(context)

    fun updateText(newText: String) {
        _uiState.update { it.copy(text = newText) }
    }

    /**
     * Traite la transcription vocale en extrayant automatiquement l'action et la catégorie de lieu,
     * et en adaptant les messages de feedback à la langue active.
     */
    fun onVoiceTranscribed(rawText: String, languageCode: String? = null) {
        if (rawText.isBlank()) return
        val parsed = com.remindly.util.VoiceIntentParser.parse(rawText)

        _uiState.update { current ->
            val newText = if (current.text.isBlank()) {
                parsed.cleanedReminderText
            } else {
                "${current.text} - ${parsed.cleanedReminderText}"
            }

            var updatedCategory = current.placeCategory
            var updatedLabel = current.placeLabel
            var feedback: String? = null

            val localizedCategoryName = parsed.detectedCategory?.getLocalizedDisplayName(languageCode)

            if (parsed.detectedCategory != null) {
                updatedCategory = parsed.detectedCategory.id
                updatedLabel = localizedCategoryName
                val prefix = when (languageCode?.lowercase()) {
                    "ar" -> "تم تحديد الفئة: "
                    "en" -> "Detected category: "
                    else -> "Catégorie détectée : "
                }
                feedback = "$prefix$localizedCategoryName"
                appLogger.i("VOICE_INTENT", "Voix analysée: '$rawText' -> Catégorie: ${parsed.detectedCategory.name}")
            } else {
                feedback = when (languageCode?.lowercase()) {
                    "ar" -> "تمت إضافة النص المنطوق"
                    "en" -> "Dictated text added"
                    else -> "Texte dicté ajouté"
                }
            }

            current.copy(
                text = newText,
                placeCategory = updatedCategory,
                placeLabel = updatedLabel,
                voiceFeedbackMessage = feedback
            )
        }
    }

    fun clearVoiceFeedback() {
        _uiState.update { it.copy(voiceFeedbackMessage = null) }
    }

    fun setTriggerTime(millis: Long) {
        _uiState.update { it.copy(triggerTimeMillis = millis) }
    }

    fun clearTriggerTime() {
        _uiState.update { it.copy(triggerTimeMillis = null) }
    }

    fun setPlace(
        lat: Double,
        lng: Double,
        label: String?,
        category: String? = null,
        categoryRefType: String? = null,
        commuteDirection: String? = null,
        radiusM: Float? = null,
        activeFromMillis: Long? = null,
        categoryKeyword: String? = null
    ) {
        _uiState.update {
            it.copy(
                placeLat = lat,
                placeLng = lng,
                placeLabel = label ?: "Lieu sélectionné",
                placeCategory = category,
                categoryKeyword = categoryKeyword,
                categoryRefType = categoryRefType,
                commuteDirection = commuteDirection,
                placeRadiusM = radiusM,
                placeActiveFromMillis = activeFromMillis
            )
        }
    }

    fun clearPlace() {
        _uiState.update {
            it.copy(
                placeLat = null,
                placeLng = null,
                placeLabel = null,
                placeCategory = null,
                categoryKeyword = null,
                categoryRefType = null,
                commuteDirection = null,
                placeRadiusM = null,
                placeActiveFromMillis = null
            )
        }
    }

    fun applySavedPlace(savedPlace: com.remindly.domain.model.SavedPlace) {
        setPlace(
            lat = savedPlace.latitude,
            lng = savedPlace.longitude,
            label = savedPlace.name,
            radiusM = savedPlace.radiusMeters
        )
    }

    fun applyCategoryShortcut(category: String, defaultLabel: String) {
        val defaultRadius = settings.value.poiDetectionRadiusM.toFloat()
        setPlace(
            lat = 0.0,
            lng = 0.0,
            label = defaultLabel,
            category = category,
            categoryRefType = "AROUND_ME",
            radiusM = defaultRadius
        )
    }

    fun applyThisEveningShortcut() {
        val cal = java.util.Calendar.getInstance().apply {
            if (get(java.util.Calendar.HOUR_OF_DAY) >= 19) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            set(java.util.Calendar.HOUR_OF_DAY, 19)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        setTriggerTime(cal.timeInMillis)
    }

    fun applyTomorrowMorningShortcut() {
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 8)
            set(java.util.Calendar.MINUTE, 30)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        setTriggerTime(cal.timeInMillis)
    }

    fun setIsRepeating(repeating: Boolean) {
        _uiState.update { it.copy(isRepeating = repeating) }
    }

    fun addImage(uri: Uri) {
        _uiState.update { it.copy(imageUris = it.imageUris + uri) }
    }

    fun removeImage(uri: Uri) {
        _uiState.update { it.copy(imageUris = it.imageUris - uri) }
    }

    fun startRecording() {
        try {
            audioRecorder.startRecording()
            _uiState.update { it.copy(isRecording = true) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRecording() {
        val path = audioRecorder.stopRecording()
        _uiState.update { it.copy(isRecording = false, audioPath = path) }
    }

    fun deleteRecording() {
        _uiState.value.audioPath?.let { path ->
            viewModelScope.launch { attachmentStore.deleteFile(path) }
        }
        _uiState.update { it.copy(audioPath = null) }
    }

    fun saveReminder(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.text.isBlank() && state.audioPath == null && state.imageUris.isEmpty()) {
                _uiState.update { it.copy(errorMessage = "Veuillez entrer un texte, un enregistrement audio ou une photo.") }
                return@launch
            }

            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            val hasPlace = state.placeLat != null || state.placeCategory != null

            // Déterminer le type de trigger
            val triggerType = when {
                state.triggerTimeMillis != null && hasPlace -> TriggerType.BOTH
                state.triggerTimeMillis != null -> TriggerType.TIME
                hasPlace -> TriggerType.PLACE
                else -> TriggerType.NONE
            }

            val reminder = Reminder(
                text = state.text.trim().ifBlank { null },
                triggerType = triggerType,
                triggerTimeMillis = state.triggerTimeMillis,
                placeLat = state.placeLat,
                placeLng = state.placeLng,
                placeRadiusM = if (hasPlace) (state.placeRadiusM ?: 450f) else null,
                placeLabel = state.placeLabel,
                placeCategory = state.placeCategory,
                categoryKeyword = state.categoryKeyword,
                categoryRefType = state.categoryRefType,
                commuteDirection = state.commuteDirection,
                placeActiveFromMillis = state.placeActiveFromMillis,
                isRepeating = state.isRepeating
            )
            
            try {
                val reminderId = reminderRepository.save(reminder)
                appLogger.i(
                    "CREATION",
                    "Rappel créé: '${reminder.text ?: "Sans texte"}' (Type: ${reminder.triggerType}, Catégorie: ${reminder.placeCategory ?: "Aucune"}, Lieu: ${reminder.placeLabel ?: "Non défini"})",
                    reminderId
                )

                // En espace collaborateur, save() écrit sur Firestore et renvoie 0 :
                // pas de ligne Room locale, donc pas de pièces jointes (non synchronisées sur Spark).
                if (reminderId > 0L) {
                    // Sauvegarder les images
                    for (uri in state.imageUris) {
                        val localPath = attachmentStore.copyImageToStorage(uri)
                        if (localPath != null) {
                            reminderRepository.addAttachment(
                                reminderId,
                                Attachment(
                                    reminderId = reminderId,
                                    type = AttachmentType.IMAGE,
                                    localPath = localPath,
                                    mimeType = "image/jpeg"
                                )
                            )
                        }
                    }

                    // Sauvegarder l'audio
                    if (state.audioPath != null) {
                        val localPath = attachmentStore.moveAudioToStorage(state.audioPath)
                        if (localPath != null) {
                            reminderRepository.addAttachment(
                                reminderId,
                                Attachment(
                                    reminderId = reminderId,
                                    type = AttachmentType.AUDIO,
                                    localPath = localPath,
                                    mimeType = "audio/mp4"
                                )
                            )
                        }
                    }
                }

                // Gérer l'alarme
                if (reminder.triggerType == TriggerType.TIME || reminder.triggerType == TriggerType.BOTH) {
                    reminder.triggerTimeMillis?.let { time ->
                        val savedReminder = reminderRepository.getById(reminderId)
                        if (savedReminder != null) {
                            alarmScheduler.schedule(savedReminder, time)
                        }
                    }
                }
                
                // Gérer le geofence
                if (reminder.triggerType == TriggerType.PLACE || reminder.triggerType == TriggerType.BOTH) {
                    val savedReminder = reminderRepository.getById(reminderId)
                    if (savedReminder != null) {
                        val now = System.currentTimeMillis()
                        if (savedReminder.placeActiveFromMillis != null && savedReminder.placeActiveFromMillis > now) {
                            // Option 1 : Réveil différé silencieux pour armer le géofence plus tard
                            alarmScheduler.scheduleDeferredGeofence(savedReminder, savedReminder.placeActiveFromMillis)
                            appLogger.i("DEFERRED_GEOFENCE_SCHEDULED", "Armement différé programmé pour ${savedReminder.placeActiveFromMillis}", reminderId)
                        } else {
                            // Armement immédiat
                            geofenceManager.addGeofence(savedReminder)
                        }
                    }
                }

                _uiState.update { it.copy(isSaving = false) }
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isRecording) {
            audioRecorder.stopRecording()
        }
    }
}
