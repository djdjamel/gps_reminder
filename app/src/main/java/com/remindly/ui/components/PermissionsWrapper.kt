package com.remindly.ui.components

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.TextButton
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsWrapper(content: @Composable () -> Unit) {
    val permissions = mutableListOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.CAMERA
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    val permissionState = rememberMultiplePermissionsState(permissions = permissions)
    var skipped by remember { mutableStateOf(false) }

    val context = LocalContext.current
    
    val backgroundLocationState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        rememberPermissionState(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        null
    }
    
    var backgroundSkipped by remember { mutableStateOf(false) }

    if ((permissionState.allPermissionsGranted || skipped) && 
        (backgroundLocationState == null || backgroundLocationState.status.isGranted || backgroundSkipped)) {
        content()
    } else if (!permissionState.allPermissionsGranted && !skipped) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Remindly nécessite certaines permissions (Position, Micro, Appareil Photo) pour fonctionner de manière optimale.",
                modifier = Modifier.padding(bottom = 16.dp),
                textAlign = TextAlign.Center
            )
            Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                Text("Autoriser")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { skipped = true }) {
                Text("Continuer quand même")
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Pour que les rappels GPS fonctionnent même quand l'application est fermée, vous devez choisir 'Toujours autoriser' dans les paramètres de localisation.",
                modifier = Modifier.padding(bottom = 16.dp),
                textAlign = TextAlign.Center
            )
            Button(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }) {
                Text("Ouvrir les paramètres")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { backgroundSkipped = true }) {
                Text("Continuer sans l'arrière-plan")
            }
        }
    }
}
