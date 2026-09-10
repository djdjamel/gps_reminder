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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val categoryRefType: String? = null,
    val commuteDirection: String? = null,
    // Médias
    val imageUris: List<Uri> = emptyList(),
    val audioPath: String? = null,
    val isRecording: Boolean = false,
    val errorMessage: String? = null,
    val voiceFeedbackMessage: String? = null
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val reminderRepository: ReminderRepository,
    private val attachmentStore: AttachmentStore,
    private val alarmScheduler: AlarmScheduler,
    private val geofenceManager: GeofenceManager,
    private val appLogger: com.remindly.util.AppLogger,
    @ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

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
        radiusM: Float? = null
    ) {
        _uiState.update {
            it.copy(
                placeLat = lat,
                placeLng = lng,
                placeLabel = label ?: "Lieu sélectionné",
                placeCategory = category,
                categoryRefType = categoryRefType,
                commuteDirection = commuteDirection,
                placeRadiusM = radiusM
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
                categoryRefType = null,
                commuteDirection = null,
                placeRadiusM = null
            )
        }
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
                categoryRefType = state.categoryRefType,
                commuteDirection = state.commuteDirection
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
                        geofenceManager.addGeofence(savedReminder)
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
