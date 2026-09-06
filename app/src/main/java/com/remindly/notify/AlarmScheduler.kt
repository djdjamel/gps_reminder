package com.remindly.notify

import com.remindly.domain.model.Reminder

interface AlarmScheduler {
    fun schedule(reminder: Reminder, timeInMillis: Long)
    fun cancel(reminderId: Long)
}

class AlarmSchedulerImpl(private val context: android.content.Context) : AlarmScheduler {
    override fun schedule(reminder: Reminder, timeInMillis: Long) {
        // TODO: Implémenter avec AlarmManager
    }

    override fun cancel(reminderId: Long) {
        // TODO: Implémenter avec AlarmManager
    }
}
