package com.remindly.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.remindly.auth.AuthManager
import com.remindly.data.settings.VoiceAlarmSettings
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.ui.auth.LoginScreen
import com.remindly.ui.capture.CaptureViewModel
import com.remindly.ui.capture.PlacePickerScreen
import com.remindly.ui.capture.QuickCaptureSheet
import com.remindly.ui.detail.DetailViewModel
import com.remindly.ui.detail.ReminderDetailScreen
import com.remindly.ui.home.HomeScreen
import com.remindly.ui.logs.LogsScreen
import com.remindly.ui.onboarding.LanguageOnboardingScreen
import com.remindly.ui.settings.SettingsScreen

@Composable
fun RemindlyNavHost(
    authManager: AuthManager,
    settingsRepository: VoiceAlarmSettingsRepository
) {
    val navController = rememberNavController()
    val currentUser by authManager.currentUserState.collectAsStateWithLifecycle()
    val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(initialValue = VoiceAlarmSettings())

    // Redirection réactive selon l'onboarding langue et l'état de connexion
    LaunchedEffect(currentUser, settings.hasSelectedLanguage) {
        if (!settings.hasSelectedLanguage) {
            navController.navigate(Routes.LanguageOnboarding.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        } else if (currentUser == null) {
            navController.navigate(Routes.Login.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        } else {
            navController.navigate(Routes.Home.route) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val startDest = if (!settings.hasSelectedLanguage) {
        Routes.LanguageOnboarding.route
    } else if (currentUser == null) {
        Routes.Login.route
    } else {
        Routes.Home.route
    }

    NavHost(
        navController = navController,
        startDestination = startDest
    ) {
        composable(Routes.LanguageOnboarding.route) {
            LanguageOnboardingScreen(
                onComplete = {
                    val nextRoute = if (currentUser == null) Routes.Login.route else Routes.Home.route
                    navController.navigate(nextRoute) {
                        popUpTo(Routes.LanguageOnboarding.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Routes.Login.route) {
            LoginScreen()
        }

        composable(Routes.Home.route) {
            HomeScreen(
                onNavigateToCapture = {
                    navController.navigate(Routes.Capture.route)
                },
                onNavigateToDetail = { id ->
                    navController.navigate("detail/$id")
                },
                onNavigateToSettings = {
                    navController.navigate(Routes.Settings.route)
                }
            )
        }

        composable(Routes.Capture.route) { backStackEntry ->
            val captureViewModel: CaptureViewModel = hiltViewModel(backStackEntry)

            val savedStateHandle = backStackEntry.savedStateHandle
            LaunchedEffect(Unit) {
                savedStateHandle.getStateFlow("place_lat", 0.0).collect { lat ->
                    val lng = savedStateHandle.get<Double>("place_lng") ?: return@collect
                    val label = savedStateHandle.get<String>("place_label")
                    val category = savedStateHandle.get<String>("place_category")
                    val categoryRef = savedStateHandle.get<String>("place_category_ref")
                    val commuteDirection = savedStateHandle.get<String>("place_commute_direction")
                    if (lat != 0.0 && lng != 0.0) {
                        captureViewModel.setPlace(lat, lng, label, category, categoryRef, commuteDirection)
                        savedStateHandle.remove<Double>("place_lat")
                        savedStateHandle.remove<Double>("place_lng")
                        savedStateHandle.remove<String>("place_label")
                        savedStateHandle.remove<String>("place_category")
                        savedStateHandle.remove<String>("place_category_ref")
                        savedStateHandle.remove<String>("place_commute_direction")
                    }
                }
            }

            QuickCaptureSheet(
                onDismissRequest = {
                    navController.popBackStack()
                },
                onNavigateToPlacePicker = {
                    navController.navigate(Routes.PlacePicker.route)
                },
                viewModel = captureViewModel
            )
        }

        composable(Routes.Detail.route) { backStackEntry ->
            val detailViewModel: DetailViewModel = hiltViewModel(backStackEntry)

            val savedStateHandle = backStackEntry.savedStateHandle
            LaunchedEffect(Unit) {
                savedStateHandle.getStateFlow("place_lat", 0.0).collect { lat ->
                    val lng = savedStateHandle.get<Double>("place_lng") ?: return@collect
                    val label = savedStateHandle.get<String>("place_label")
                    val category = savedStateHandle.get<String>("place_category")
                    val categoryRef = savedStateHandle.get<String>("place_category_ref")
                    val commuteDirection = savedStateHandle.get<String>("place_commute_direction")
                    if (lat != 0.0 && lng != 0.0) {
                        detailViewModel.setPlace(lat, lng, label, category, categoryRef, commuteDirection)
                        savedStateHandle.remove<Double>("place_lat")
                        savedStateHandle.remove<Double>("place_lng")
                        savedStateHandle.remove<String>("place_label")
                        savedStateHandle.remove<String>("place_category")
                        savedStateHandle.remove<String>("place_category_ref")
                        savedStateHandle.remove<String>("place_commute_direction")
                    }
                }
            }

            ReminderDetailScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPlacePicker = {
                    navController.navigate(Routes.PlacePickerFromDetail.route)
                },
                viewModel = detailViewModel
            )
        }

        composable(Routes.PlacePicker.route) {
            PlacePickerScreen(
                onPlaceSelected = { latLng, label, category, categoryRef, commuteDirection ->
                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                        set("place_lat", latLng.latitude)
                        set("place_lng", latLng.longitude)
                        set("place_label", label ?: "Lat: ${"%.4f".format(latLng.latitude)}, Lng: ${"%.4f".format(latLng.longitude)}")
                        set("place_category", category)
                        set("place_category_ref", categoryRef)
                        set("place_commute_direction", commuteDirection)
                    }
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.PlacePickerFromDetail.route) {
            PlacePickerScreen(
                onPlaceSelected = { latLng, label, category, categoryRef, commuteDirection ->
                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                        set("place_lat", latLng.latitude)
                        set("place_lng", latLng.longitude)
                        set("place_label", label ?: "Lat: ${"%.4f".format(latLng.latitude)}, Lng: ${"%.4f".format(latLng.longitude)}")
                        set("place_category", category)
                        set("place_category_ref", categoryRef)
                        set("place_commute_direction", commuteDirection)
                    }
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToCommuteRoute = {
                    navController.navigate(Routes.CommuteRoute.route)
                },
                onNavigateToLogs = {
                    navController.navigate(Routes.Logs.route)
                }
            )
        }

        composable(Routes.CommuteRoute.route) {
            com.remindly.ui.settings.CommuteRouteScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.Logs.route) {
            LogsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
