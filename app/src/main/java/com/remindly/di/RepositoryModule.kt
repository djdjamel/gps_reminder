package com.remindly.di

import android.content.Context
import com.remindly.data.db.dao.AttachmentDao
import com.remindly.data.db.dao.ReminderDao
import com.remindly.data.repo.ReminderRepository
import com.remindly.data.repo.ReminderRepositoryImpl
import com.remindly.data.repo.SharedListRepository
import com.remindly.data.repo.SharedListRepositoryImpl
import com.remindly.data.db.dao.SharedListDao
import com.remindly.data.repo.SavedPlaceRepository
import com.remindly.data.repo.SavedPlaceRepositoryImpl
import com.remindly.data.db.dao.SavedPlaceDao
import com.remindly.data.db.dao.CollaboratorDao
import com.remindly.auth.AuthManager
import com.remindly.auth.WorkspaceManager
import com.remindly.data.remote.FirestoreDataSource
import com.remindly.data.repo.CollaboratorRepository
import com.remindly.data.repo.CollaboratorRepositoryImpl
import com.remindly.media.AttachmentStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun provideReminderRepository(
        reminderDao: ReminderDao,
        attachmentDao: AttachmentDao,
        attachmentStore: AttachmentStore,
        workspaceManager: WorkspaceManager,
        authManager: AuthManager,
        firestoreDataSource: FirestoreDataSource,
        @ApplicationContext context: Context
    ): ReminderRepository {
        return ReminderRepositoryImpl(
            reminderDao, 
            attachmentDao, 
            attachmentStore, 
            workspaceManager,
            authManager,
            firestoreDataSource,
            context
        )
    }

    @Provides
    fun provideSharedListRepository(sharedListDao: SharedListDao): SharedListRepository {
        return SharedListRepositoryImpl(sharedListDao)
    }

    @Provides
    fun provideSavedPlaceRepository(savedPlaceDao: SavedPlaceDao): SavedPlaceRepository {
        return SavedPlaceRepositoryImpl(savedPlaceDao)
    }

    @Provides
    fun provideCollaboratorRepository(collaboratorDao: CollaboratorDao): CollaboratorRepository {
        return CollaboratorRepositoryImpl(collaboratorDao)
    }
}
