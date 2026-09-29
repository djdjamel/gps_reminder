package com.remindly.di

import android.content.Context
import androidx.room.Room
import com.remindly.data.db.RemindlyDatabase
import com.remindly.data.db.dao.AttachmentDao
import com.remindly.data.db.dao.CollaboratorDao
import com.remindly.data.db.dao.ReminderDao
import com.remindly.data.db.dao.ReminderLogDao
import com.remindly.data.db.dao.SavedPlaceDao
import com.remindly.data.db.dao.SharedListDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RemindlyDatabase {
        return Room.databaseBuilder(
            context,
            RemindlyDatabase::class.java,
            "remindly_db"
        )
        .addMigrations(RemindlyDatabase.MIGRATION_6_7, RemindlyDatabase.MIGRATION_7_8)
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideReminderDao(database: RemindlyDatabase): ReminderDao {
        return database.reminderDao()
    }

    @Provides
    fun provideSharedListDao(database: RemindlyDatabase): SharedListDao {
        return database.sharedListDao()
    }

    @Provides
    fun provideAttachmentDao(database: RemindlyDatabase): AttachmentDao {
        return database.attachmentDao()
    }

    @Provides
    fun provideSavedPlaceDao(database: RemindlyDatabase): SavedPlaceDao {
        return database.savedPlaceDao()
    }

    @Provides
    fun provideCollaboratorDao(database: RemindlyDatabase): CollaboratorDao {
        return database.collaboratorDao()
    }

    @Provides
    fun provideReminderLogDao(database: RemindlyDatabase): ReminderLogDao {
        return database.reminderLogDao()
    }
}
