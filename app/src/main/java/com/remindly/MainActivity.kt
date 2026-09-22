package com.remindly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.remindly.auth.AuthManager
import com.remindly.data.settings.VoiceAlarmSettings
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.domain.model.AppTheme
import com.remindly.location.VehicleModeManager
import com.remindly.ui.components.PermissionsWrapper
import com.remindly.ui.navigation.RemindlyNavHost
import com.remindly.ui.theme.LocalAppStrings
import com.remindly.ui.theme.RemindlyTheme
import com.remindly.ui.theme.getStringsForLanguage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var settingsRepository: VoiceAlarmSettingsRepository

    @Inject
    lateinit var vehicleModeManager: VehicleModeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(initialValue = VoiceAlarmSettings())
            val currentStrings = getStringsForLanguage(settings.appLanguage)
            val layoutDirection = if (settings.appLanguage == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

            val isDark = when (settings.appTheme) {
                AppTheme.LIGHT.name -> false
                AppTheme.DARK.name -> true
                else -> isSystemInDarkTheme()
            }

            CompositionLocalProvider(
                LocalAppStrings provides currentStrings,
                LocalLayoutDirection provides layoutDirection
            ) {
                RemindlyTheme(
                    darkTheme = isDark,
                    dynamicColor = false
                ) {
                    PermissionsWrapper(
                        onPermissionsGranted = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val currentSettings = settingsRepository.getSettings()
                                if (currentSettings.autoVehicleDetection) {
                                    vehicleModeManager.startMonitoring()
                                }
                            }
                        }
                    ) {
                        RemindlyNavHost(
                            authManager = authManager,
                            settingsRepository = settingsRepository
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (vehicleModeManager.hasActivityRecognitionPermission()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val currentSettings = settingsRepository.getSettings()
                if (currentSettings.autoVehicleDetection) {
                    vehicleModeManager.startMonitoring()
                }
            }
        }
    }
}
