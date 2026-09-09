package com.remindly.util

import android.util.Log
import com.remindly.data.db.dao.ReminderLogDao
import com.remindly.data.db.entity.ReminderLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogger @Inject constructor(
    private val reminderLogDao: ReminderLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun i(tag: String, message: String, reminderId: Long? = null) {
        Log.i(tag, if (reminderId != null) "[#$reminderId] $message" else message)
        writeLog(tag, message, "INFO", reminderId)
    }

    fun success(tag: String, message: String, reminderId: Long? = null) {
        Log.i(tag, "✅ " + if (reminderId != null) "[#$reminderId] $message" else message)
        writeLog(tag, message, "SUCCESS", reminderId)
    }

    fun w(tag: String, message: String, reminderId: Long? = null) {
        Log.w(tag, "⚠️ " + if (reminderId != null) "[#$reminderId] $message" else message)
        writeLog(tag, message, "WARN", reminderId)
    }

    fun e(tag: String, message: String, error: Throwable? = null, reminderId: Long? = null) {
        val fullMsg = if (error != null) "$message | ${error.javaClass.simpleName}: ${error.message}" else message
        Log.e(tag, "❌ " + if (reminderId != null) "[#$reminderId] $fullMsg" else fullMsg, error)
        writeLog(tag, fullMsg, "ERROR", reminderId)
    }

    private fun writeLog(tag: String, message: String, level: String, reminderId: Long?) {
        scope.launch {
            try {
                reminderLogDao.insert(
                    ReminderLogEntity(
                        reminderId = reminderId,
                        tag = tag,
                        message = message,
                        level = level
                    )
                )
            } catch (e: Exception) {
                Log.e("AppLogger", "Erreur écriture log: ${e.message}")
            }
        }
    }
}
