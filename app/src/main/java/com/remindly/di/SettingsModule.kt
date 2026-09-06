package com.remindly.di

import android.content.Context
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.data.settings.VoiceAlarmSettingsRepositoryImpl
import com.remindly.data.settings.settingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideVoiceAlarmSettingsRepository(
        @ApplicationContext context: Context
    ): VoiceAlarmSettingsRepository {
        return VoiceAlarmSettingsRepositoryImpl(context.settingsDataStore)
    }
}
