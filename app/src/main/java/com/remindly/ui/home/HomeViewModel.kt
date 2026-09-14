package com.remindly.ui.home

import android.content.Context
import android.location.Location
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseUser
import com.remindly.auth.AuthManager
import com.remindly.auth.WorkspaceManager
import com.remindly.data.db.entity.CollaboratorEntity
import com.remindly.data.repo.CollaboratorRepository
import com.remindly.data.repo.ReminderRepository
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import com.remindly.location.DiagnosticLocationTracker
import com.remindly.location.DiagnosticState
import com.remindly.location.GeofenceManager
import com.remindly.time.AlarmScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class ReminderSection(
    val title: String,
    val dateMillis: Long,
    val remainingCount: Int,
    val reminders: List<Reminder>,
    val isNearbySection: Boolean = false
)

data class HomeUiState(
    val sections: List<ReminderSection> = emptyList(),
    val isLoading: Boolean = true,
    val allReminders: List<Reminder> = emptyList(), // flat list for reorder
    val userLocation: Location? = null,
    val playingReminderId: Long? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val reminderRepository: ReminderRepository,
    private val alarmScheduler: AlarmScheduler,
    private val geofenceManager: GeofenceManager,
    private val workspaceManager: WorkspaceManager,
    private val authManager: AuthManager,
    private val collaboratorRepository: CollaboratorRepository,
    private val diagnosticTracker: DiagnosticLocationTracker,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val diagnosticState: StateFlow<DiagnosticState> = diagnosticTracker.state

    fun toggleDiagnostic(reminder: Reminder) {
        if (diagnosticTracker.isDiagnosticActiveFor(reminder.id)) {
            diagnosticTracker.stopDiagnostic(context)
        } else {
            diagnosticTracker.startDiagnostic(context, reminder)
        }
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _playingReminderId = MutableStateFlow<Long?>(null)
    val playingReminderId: StateFlow<Long?> = _playingReminderId.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    init {
        refreshLocation()
    }

    fun refreshLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    _currentLocation.value = loc
                }
            }
        } catch (e: SecurityException) {
            // Permission non accordée
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val currentUser: StateFlow<FirebaseUser?> = authManager.currentUserState

    fun signOut() {
        authManager.signOut()
    }

    val currentWorkspaceEmail: StateFlow<String?> = workspaceManager.currentWorkspaceEmail

    val collaborators: StateFlow<List<CollaboratorEntity>> = collaboratorRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun addCollaborator(name: String, email: String) {
        viewModelScope.launch {
            collaboratorRepository.save(name, email)
        }
    }

    fun switchToPersonalWorkspace() {
        workspaceManager.switchToPersonalWorkspace()
    }

    fun switchToCollaboratorWorkspace(email: String) {
        workspaceManager.switchToCollaboratorWorkspace(email)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        reminderRepository.observePersonalActive(),
        _currentLocation,
        _playingReminderId
    ) { reminders, loc, playingId ->
        buildSections(reminders, loc, playingId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(isLoading = true)
    )

    private fun buildSections(
        reminders: List<Reminder>,
        userLoc: Location?,
        playingId: Long?
    ): HomeUiState {
        if (reminders.isEmpty()) {
            return HomeUiState(
                sections = emptyList(),
                isLoading = false,
                allReminders = emptyList(),
                userLocation = userLoc,
                playingReminderId = playingId
            )
        }

        val allSections = mutableListOf<ReminderSection>()

        // 1. Détection des rappels à proximité immédiate (< 2000m / 2 km)
        if (userLoc != null) {
            val now = System.currentTimeMillis()
            val nearbyReminders = reminders.filter { r ->
                if (r.status != ReminderStatus.ACTIVE) return@filter false
                if (r.placeActiveFromMillis != null && r.placeActiveFromMillis > now) return@filter false
                if (r.placeLat != null && r.placeLng != null) {
                    val dist = FloatArray(1)
                    Location.distanceBetween(
                        userLoc.latitude, userLoc.longitude,
                        r.placeLat, r.placeLng,
                        dist
                    )
                    dist[0] < 2000f
                } else false
            }.sortedBy { r ->
                val dist = FloatArray(1)
                Location.distanceBetween(
                    userLoc.latitude, userLoc.longitude,
                    r.placeLat!!, r.placeLng!!,
                    dist
                )
                dist[0]
            }

            if (nearbyReminders.isNotEmpty()) {
                allSections.add(
                    ReminderSection(
                        title = "🎯 À proximité en ce moment (< 2 km)",
                        dateMillis = Long.MAX_VALUE,
                        remainingCount = nearbyReminders.size,
                        reminders = nearbyReminders,
                        isNearbySection = true
                    )
                )
            }
        }

        // 2. Sections temporelles habituelles (par jour de création)
        val calendar = Calendar.getInstance()
        val todayStart = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

        val grouped = reminders.groupBy { reminder ->
            val cal = Calendar.getInstance().apply { timeInMillis = reminder.createdAt }
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }.toSortedMap(compareByDescending { it })

        for ((dayMillis, dayReminders) in grouped) {
            val activeCount = dayReminders.count { it.status == ReminderStatus.ACTIVE }
            val title = when {
                dayMillis >= todayStart -> "Aujourd'hui"
                dayMillis >= yesterdayStart -> if (activeCount > 0) "Hier — $activeCount tâche(s) restante(s)" else "Hier"
                else -> {
                    val dateStr = sdf.format(Date(dayMillis))
                    if (activeCount > 0) "$dateStr — $activeCount tâche(s) restante(s)" else dateStr
                }
            }
            allSections.add(
                ReminderSection(
                    title = title,
                    dateMillis = dayMillis,
                    remainingCount = activeCount,
                    reminders = dayReminders,
                    isNearbySection = false
                )
            )
        }

        return HomeUiState(
            sections = allSections,
            isLoading = false,
            allReminders = reminders,
            userLocation = userLoc,
            playingReminderId = playingId
        )
    }

    fun playAudio(reminderId: Long, localPath: String) {
        if (_playingReminderId.value == reminderId) {
            stopAudio()
            return
        }

        stopAudio()
        try {
            val player = MediaPlayer().apply {
                setDataSource(localPath)
                setOnCompletionListener {
                    _playingReminderId.value = null
                    it.release()
                    mediaPlayer = null
                }
                prepare()
                start()
            }
            mediaPlayer = player
            _playingReminderId.value = reminderId
        } catch (e: Exception) {
            e.printStackTrace()
            _playingReminderId.value = null
        }
    }

    fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
            _playingReminderId.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }

    fun onReminderCompleted(id: Long, isCompleted: Boolean) {
        viewModelScope.launch {
            val status = if (isCompleted) ReminderStatus.COMPLETED else ReminderStatus.ACTIVE
            reminderRepository.setStatus(id, status)
            
            if (isCompleted) {
                alarmScheduler.cancel(id)
                geofenceManager.removeGeofence(id)
                if (diagnosticTracker.isDiagnosticActiveFor(id)) {
                    diagnosticTracker.stopDiagnostic(context)
                }
            } else {
                val reminder = reminderRepository.getById(id)
                if (reminder != null) {
                    if (reminder.triggerType == TriggerType.TIME || reminder.triggerType == TriggerType.BOTH) {
                        reminder.triggerTimeMillis?.let { time ->
                            alarmScheduler.schedule(reminder, time)
                        }
                    }
                    if (reminder.triggerType == TriggerType.PLACE || reminder.triggerType == TriggerType.BOTH) {
                        geofenceManager.addGeofence(reminder)
                    }
                }
            }
        }
    }

    fun moveUp(reminder: Reminder) {
        val allReminders = uiState.value.allReminders
        val index = allReminders.indexOfFirst { it.id == reminder.id }
        if (index <= 0) return

        val above = allReminders[index - 1]
        viewModelScope.launch {
            reminderRepository.updateSortOrder(reminder.id, above.sortOrder)
            reminderRepository.updateSortOrder(above.id, reminder.sortOrder)
        }
    }

    fun moveDown(reminder: Reminder) {
        val allReminders = uiState.value.allReminders
        val index = allReminders.indexOfFirst { it.id == reminder.id }
        if (index < 0 || index >= allReminders.size - 1) return

        val below = allReminders[index + 1]
        viewModelScope.launch {
            reminderRepository.updateSortOrder(reminder.id, below.sortOrder)
            reminderRepository.updateSortOrder(below.id, reminder.sortOrder)
        }
    }
}
