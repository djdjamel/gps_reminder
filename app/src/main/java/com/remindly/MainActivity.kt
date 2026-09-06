package com.remindly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.remindly.auth.AuthManager
import com.remindly.ui.components.PermissionsWrapper
import com.remindly.ui.navigation.RemindlyNavHost
import com.remindly.ui.theme.RemindlyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemindlyTheme {
                PermissionsWrapper {
                    RemindlyNavHost(authManager = authManager)
                }
            }
        }
    }
}
