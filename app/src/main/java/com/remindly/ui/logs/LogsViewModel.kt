package com.remindly.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remindly.data.db.dao.ReminderLogDao
import com.remindly.data.db.entity.ReminderLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogsUiState(
    val logs: List<ReminderLogEntity> = emptyList(),
    val filterTag: String? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val reminderLogDao: ReminderLogDao
) : ViewModel() {

    private val _filterTag = MutableStateFlow<String?>(null)
    val filterTag: StateFlow<String?> = _filterTag.asStateFlow()

    val uiState: StateFlow<LogsUiState> = combine(
        reminderLogDao.observeRecentLogs(),
        _filterTag
    ) { logs, tag ->
        val filtered = if (tag != null) {
            logs.filter { it.tag.equals(tag, ignoreCase = true) || it.level.equals(tag, ignoreCase = true) }
        } else logs
        LogsUiState(logs = filtered, filterTag = tag, isLoading = false)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LogsUiState(isLoading = true)
    )

    fun setFilter(tag: String?) {
        _filterTag.value = tag
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            reminderLogDao.clearAll()
        }
    }

    fun exportLogsAsJson(): String {
        val currentLogs = uiState.value.logs
        val jsonArray = org.json.JSONArray()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US)

        for (log in currentLogs) {
            val obj = org.json.JSONObject()
            obj.put("id", log.id)
            obj.put("reminderId", log.reminderId ?: org.json.JSONObject.NULL)
            obj.put("timestamp", log.timestamp)
            obj.put("dateTime", dateFormat.format(java.util.Date(log.timestamp)))
            obj.put("level", log.level)
            obj.put("tag", log.tag)
            obj.put("message", log.message)
            jsonArray.put(obj)
        }
        return jsonArray.toString(2)
    }
}
