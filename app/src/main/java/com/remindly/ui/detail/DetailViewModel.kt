package com.remindly.ui.detail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
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
import com.remindly.notify.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val reminder: Reminder? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val text: String = "",
    val triggerTimeMillis: Long? = null,
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    val placeLabel: String? = null,
    val placeCategory: String? = null,
    val categoryRefType: String? = null,
    val isRecording: Boolean = false,
    val audioPath: String? = null
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val reminderRepository: ReminderRepository,
    private val attachmentStore: AttachmentStore,
    private val alarmScheduler: AlarmScheduler,
    private val geofenceManager: GeofenceManager,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val reminderId: Long = savedStateHandle.get<String>("reminderId")?.toLongOrNull() ?: 0L
    private val audioRecorder = AudioRecorderManager(context)

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        loadReminder()
    }

    private fun loadReminder() {
        if (reminderId == 0L) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }

        viewModelScope.launch {
            reminderRepository.observeById(reminderId).collect { reminder ->
                _uiState.update {
                    it.copy(
                        reminder = reminder,
                        isLoading = false,
                        text = if (it.reminder == null) reminder?.text ?: "" else it.text,
                        triggerTimeMillis = if (it.reminder == null) reminder?.triggerTimeMillis else it.triggerTimeMillis,
                        placeLat = if (it.reminder == null) reminder?.placeLat else it.placeLat,
                        placeLng = if (it.reminder == null) reminder?.placeLng else it.placeLng,
                        placeLabel = if (it.reminder == null) reminder?.placeLabel else it.placeLabel,
                        placeCategory = if (it.reminder == null) reminder?.placeCategory else it.placeCategory,
                        categoryRefType = if (it.reminder == null) reminder?.categoryRefType else it.categoryRefType,
                        audioPath = it.audioPath
                            ?: reminder?.attachments?.firstOrNull { a -> a.type == AttachmentType.AUDIO }?.localPath
                    )
                }
            }
        }
    }

    fun updateText(newText: String) {
        _uiState.update { it.copy(text = newText) }
    }

    fun setTriggerTime(millis: Long) {
        _uiState.update { it.copy(triggerTimeMillis = millis) }
    }

    fun clearTriggerTime() {
        _uiState.update { it.copy(triggerTimeMillis = null) }
    }

    fun setPlace(lat: Double, lng: Double, label: String?, category: String? = null, categoryRefType: String? = null) {
        _uiState.update {
            it.copy(
                placeLat = lat,
                placeLng = lng,
                placeLabel = label ?: "Lieu sélectionné",
                placeCategory = category,
                categoryRefType = categoryRefType
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
                categoryRefType = null
            )
        }
    }

    fun addImage(uri: Uri) {
        viewModelScope.launch {
            val localPath = attachmentStore.copyImageToStorage(uri) ?: return@launch
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

    fun removeAttachment(attachmentId: Long, localPath: String) {
        viewModelScope.launch {
            reminderRepository.removeAttachment(attachmentId, localPath)
        }
    }

    fun save(onDone: () -> Unit) {
        val state = _uiState.value
        if (state.text.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            val hasPlace = state.placeLat != null || state.placeCategory != null

            val triggerType = when {
                state.triggerTimeMillis != null && hasPlace -> TriggerType.BOTH
                state.triggerTimeMillis != null -> TriggerType.TIME
                hasPlace -> TriggerType.PLACE
                else -> TriggerType.NONE
            }

            val reminder = (state.reminder ?: Reminder(id = reminderId)).copy(
                text = state.text.trim(),
                triggerType = triggerType,
                triggerTimeMillis = state.triggerTimeMillis,
                placeLat = state.placeLat,
                placeLng = state.placeLng,
                placeRadiusM = if (state.placeLat != null) 120f else null,
                placeLabel = state.placeLabel,
                placeCategory = state.placeCategory,
                categoryRefType = state.categoryRefType
            )
            val savedId = reminderRepository.save(reminder)
            
            // Gérer l'alarme
            if (reminder.triggerType == TriggerType.TIME || reminder.triggerType == TriggerType.BOTH) {
                reminder.triggerTimeMillis?.let { time ->
                    val savedReminder = reminderRepository.getById(savedId)
                    if (savedReminder != null) {
                        alarmScheduler.schedule(savedReminder, time)
                    }
                }
            } else {
                alarmScheduler.cancel(savedId)
            }
            
            // Gérer le geofence
            if (reminder.triggerType == TriggerType.PLACE || reminder.triggerType == TriggerType.BOTH) {
                val savedReminder = reminderRepository.getById(savedId)
                if (savedReminder != null) {
                    geofenceManager.addGeofence(savedReminder)
                }
            } else {
                geofenceManager.removeGeofence(savedId)
            }

            _uiState.update { it.copy(isSaving = false) }
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            if (reminderId > 0) {
                alarmScheduler.cancel(reminderId)
                geofenceManager.removeGeofence(reminderId)
                reminderRepository.delete(reminderId)
            }
            onDone()
        }
    }

    fun toggleStatus() {
        val current = _uiState.value.reminder ?: return
        val newStatus = if (current.status == ReminderStatus.ACTIVE) ReminderStatus.COMPLETED else ReminderStatus.ACTIVE
        viewModelScope.launch {
            reminderRepository.setStatus(current.id, newStatus)
            if (newStatus == ReminderStatus.COMPLETED) {
                alarmScheduler.cancel(current.id)
                geofenceManager.removeGeofence(current.id)
            }
        }
    }

    fun complete(onDone: () -> Unit) {
        val current = _uiState.value.reminder ?: return
        viewModelScope.launch {
            reminderRepository.setStatus(current.id, ReminderStatus.COMPLETED)
            alarmScheduler.cancel(current.id)
            geofenceManager.removeGeofence(current.id)
            onDone()
        }
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
        if (path != null) {
            viewModelScope.launch {
                val localPath = attachmentStore.moveAudioToStorage(path)
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
                    _uiState.update { it.copy(isRecording = false, audioPath = localPath) }
                }
            }
        } else {
            _uiState.update { it.copy(isRecording = false) }
        }
    }

    fun deleteRecording() {
        val path = _uiState.value.audioPath
        if (path != null) {
            viewModelScope.launch {
                val audioAttachment = _uiState.value.reminder?.attachments?.firstOrNull { it.type == AttachmentType.AUDIO }
                if (audioAttachment != null) {
                    reminderRepository.removeAttachment(audioAttachment.id, path)
                } else {
                    attachmentStore.deleteFile(path)
                }
                _uiState.update { it.copy(audioPath = null) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isRecording) {
            audioRecorder.stopRecording()
        }
    }
}
