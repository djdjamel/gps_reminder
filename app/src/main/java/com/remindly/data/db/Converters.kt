package com.remindly.data.db

import androidx.room.TypeConverter
import com.remindly.domain.model.AttachmentType
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.RepeatRule
import com.remindly.domain.model.SyncState
import com.remindly.domain.model.TriggerType

class Converters {

    @TypeConverter
    fun fromTriggerType(value: TriggerType): String = value.name

    @TypeConverter
    fun toTriggerType(value: String): TriggerType = enumValueOf(value)

    @TypeConverter
    fun fromReminderStatus(value: ReminderStatus): String = value.name

    @TypeConverter
    fun toReminderStatus(value: String): ReminderStatus = enumValueOf(value)

    @TypeConverter
    fun fromRepeatRule(value: RepeatRule?): String? = value?.name

    @TypeConverter
    fun toRepeatRule(value: String?): RepeatRule? = value?.let { enumValueOf<RepeatRule>(it) }

    @TypeConverter
    fun fromSyncState(value: SyncState): String = value.name

    @TypeConverter
    fun toSyncState(value: String): SyncState = enumValueOf(value)

    @TypeConverter
    fun fromAttachmentType(value: AttachmentType): String = value.name

    @TypeConverter
    fun toAttachmentType(value: String): AttachmentType = enumValueOf(value)
}
