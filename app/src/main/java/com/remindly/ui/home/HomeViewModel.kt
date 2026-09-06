package com.remindly.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remindly.data.repo.ReminderRepository
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import com.remindly.time.AlarmScheduler
import com.remindly.auth.WorkspaceManager
import com.remindly.auth.AuthManager
import com.remindly.data.repo.CollaboratorRepository
import com.remindly.data.db.entity.CollaboratorEntity
import com.remindly.location.GeofenceManager
import com.google.firebase.auth.FirebaseUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

data class ReminderSection(
    val title: String,
    val dateMillis: Long,
    val remainingCount: Int,
    val reminders: List<Reminder>
)

data class HomeUiState(
    val sections: List<ReminderSection> = emptyList(),
    val isLoading: Boolean = true,
    val allReminders: List<Reminder> = emptyList() // flat list for reorder
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val reminderRepository: ReminderRepository,
    private val alarmScheduler: AlarmScheduler,
    private val geofenceManager: GeofenceManager,
    private val workspaceManager: WorkspaceManager,
    private val authManager: AuthManager,
    private val collaboratorRepository: CollaboratorRepository
) : ViewModel() {

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

    val uiState: StateFlow<HomeUiState> = reminderRepository.observePersonalActive()
        .map { reminders -> buildSections(reminders) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HomeUiState(isLoading = true)
        )

    private fun buildSections(reminders: List<Reminder>): HomeUiState {
        if (reminders.isEmpty()) return HomeUiState(sections = emptyList(), isLoading = false, allReminders = emptyList())

        val calendar = Calendar.getInstance()
        val todayStart = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)

        // Grouper par jour de création
        val grouped = reminders.groupBy { reminder ->
            val cal = Calendar.getInstance().apply { timeInMillis = reminder.createdAt }
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }.toSortedMap(compareByDescending { it }) // plus récent en premier

        val sections = grouped.map { (dayMillis, dayReminders) ->
            val activeCount = dayReminders.count { it.status == ReminderStatus.ACTIVE }
            val title = when {
                dayMillis >= todayStart -> "Aujourd'hui"
                dayMillis >= yesterdayStart -> if (activeCount > 0) "Hier — $activeCount tâche(s) restante(s)" else "Hier"
                else -> {
                    val dateStr = sdf.format(Date(dayMillis))
                    if (activeCount > 0) "$dateStr — $activeCount tâche(s) restante(s)" else dateStr
                }
            }
            ReminderSection(
                title = title,
                dateMillis = dayMillis,
                remainingCount = activeCount,
                reminders = dayReminders
            )
        }

        return HomeUiState(sections = sections, isLoading = false, allReminders = reminders)
    }

    fun onReminderCompleted(id: Long, isCompleted: Boolean) {
        viewModelScope.launch {
            val status = if (isCompleted) ReminderStatus.COMPLETED else ReminderStatus.ACTIVE
            reminderRepository.setStatus(id, status)
            
            if (isCompleted) {
                alarmScheduler.cancel(id)
                geofenceManager.removeGeofence(id)
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
