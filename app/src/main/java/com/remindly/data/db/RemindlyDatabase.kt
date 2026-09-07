package com.remindly.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.remindly.data.db.dao.AttachmentDao
import com.remindly.data.db.dao.PendingSyncDao
import com.remindly.data.db.dao.ReminderDao
import com.remindly.data.db.dao.SavedPlaceDao
import com.remindly.data.db.dao.SharedListDao
import com.remindly.data.db.entity.GeofenceRegistrationEntity
import com.remindly.data.db.entity.PendingSyncEntity
import com.remindly.data.db.entity.ReminderAttachmentEntity
import com.remindly.data.db.entity.ReminderEntity
import com.remindly.data.db.entity.SavedPlaceEntity
import com.remindly.data.db.entity.SharedListEntity
import com.remindly.data.db.entity.CollaboratorEntity
import com.remindly.data.db.dao.CollaboratorDao

@Database(
    entities = [
        ReminderEntity::class,
        ReminderAttachmentEntity::class,
        SavedPlaceEntity::class,
        GeofenceRegistrationEntity::class,
        SharedListEntity::class,
        PendingSyncEntity::class,
        CollaboratorEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class RemindlyDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun sharedListDao(): SharedListDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun collaboratorDao(): CollaboratorDao
}
