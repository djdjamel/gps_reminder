package com.remindly.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.remindly.data.db.dao.AttachmentDao
import com.remindly.data.db.dao.CollaboratorDao
import com.remindly.data.db.dao.ReminderDao
import com.remindly.data.db.dao.ReminderLogDao
import com.remindly.data.db.dao.SavedPlaceDao
import com.remindly.data.db.dao.SharedListDao
import com.remindly.data.db.entity.CollaboratorEntity
import com.remindly.data.db.entity.ReminderAttachmentEntity
import com.remindly.data.db.entity.ReminderEntity
import com.remindly.data.db.entity.ReminderLogEntity
import com.remindly.data.db.entity.SavedPlaceEntity
import com.remindly.data.db.entity.SharedListEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ReminderEntity::class,
        ReminderAttachmentEntity::class,
        SavedPlaceEntity::class,
        SharedListEntity::class,
        CollaboratorEntity::class,
        ReminderLogEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class RemindlyDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun sharedListDao(): SharedListDao
    abstract fun collaboratorDao(): CollaboratorDao
    abstract fun reminderLogDao(): ReminderLogDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN placeActiveFromMillis INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN categoryKeyword TEXT DEFAULT NULL")
            }
        }
    }
}
