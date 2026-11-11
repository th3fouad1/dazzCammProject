package com.vintagelab.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Routes (DESIGN §3.2). v1.1 reserves Gallery and Import. */
enum class Screen { Camera, Licenses }

@Composable
fun AppRoot(vm: CameraViewModel, onScreen: (Screen) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Camera) }
    val owner = LocalLifecycleOwner.current
    onScreen(screen)
    when (screen) {
        Screen.Licenses -> LicensesScreen(onBack = { screen = Screen.Camera })
        Screen.Camera -> PermissionGate(state.permission, vm::onIntent, onOpenLicenses = { screen = Screen.Licenses }) {
            CameraScreen(
                state = state,
                onIntent = vm::onIntent,
                previewHost = vm.previewHost,
                onAttach = { vm.attach(owner) },
                onDetach = { vm.detach(owner) },
                onScreenFlash = vm::setScreenFlash,
                onOpenLicenses = { screen = Screen.Licenses },
            )
        }
    }
}
