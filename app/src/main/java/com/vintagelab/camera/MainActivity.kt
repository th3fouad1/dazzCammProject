package com.vintagelab.camera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vintagelab.camera.ui.CameraScreen
import com.vintagelab.camera.ui.CameraViewModel
import com.vintagelab.camera.ui.LicensesScreen
import com.vintagelab.camera.ui.PermissionGate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val vm: CameraViewModel = viewModel()
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    if (showLicenses) {
        BackHandler { showLicenses = false }
        LicensesScreen(onBack = { showLicenses = false })
    } else {
        PermissionGate(vm) {
            CameraScreen(vm, onOpenLicenses = { showLicenses = true })
        }
    }
}
