package com.remindly.ui.navigation

sealed class Routes(val route: String) {
    object Login : Routes("login")
    object Home : Routes("home")
    object Capture : Routes("capture")
    object Detail : Routes("detail/{reminderId}")
    object PlacePicker : Routes("place_picker")
    object PlacePickerFromDetail : Routes("place_picker_detail")
    object Settings : Routes("settings")
    object CommuteRoute : Routes("commute_route")
}
