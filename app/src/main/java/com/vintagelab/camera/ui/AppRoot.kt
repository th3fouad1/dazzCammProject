package com.vintagelab.camera.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vintagelab.camera.editor.EditorPhase
import com.vintagelab.camera.editor.EditorScreen
import com.vintagelab.camera.editor.EditorViewModel
import com.vintagelab.camera.gallery.GalleryScreen
import com.vintagelab.camera.gallery.GalleryViewModel

/** Routes (DESIGN section 3.2). */
enum class Screen { Camera, Licenses, Gallery, Editor }

@Composable
fun AppRoot(vm: CameraViewModel, editor: EditorViewModel, gallery: GalleryViewModel, onScreen: (Screen) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editorState by editor.state.collectAsStateWithLifecycle()
    val galleryState by gallery.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.Camera) }
    var editorReturn by rememberSaveable { mutableStateOf(Screen.Camera) }
    var galleryFocus by remember { mutableStateOf<Uri?>(null) }
    val owner = LocalLifecycleOwner.current
    onScreen(screen)

    fun openGallery(focus: Uri?) {
        galleryFocus = focus
        gallery.refresh()
        screen = Screen.Gallery
    }

    // The picker is registered here (not inside a menu): it must outlive the menu that launched it.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            editorReturn = if (screen == Screen.Gallery) Screen.Gallery else Screen.Camera
            editor.openImport(uri)
            screen = Screen.Editor
        }
    }
    val launchPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    // After process death the editor has no photo: go back to the camera.
    LaunchedEffect(screen, editorState.phase) {
        if (screen == Screen.Editor && editorState.phase == EditorPhase.Idle) screen = Screen.Camera
    }
    // Back on the camera: the newest photo may have changed (saved by the editor, deleted in the gallery).
    LaunchedEffect(screen) { if (screen == Screen.Camera) vm.refreshLastShot() }
    // A photo developed by the editor opens in the viewer.
    LaunchedEffect(editor) {
        editor.savedPhotos.collect { uri ->
            editor.close()
            openGallery(uri)
        }
    }

    when (screen) {
        Screen.Licenses -> LicensesScreen(onBack = { screen = Screen.Camera })
        Screen.Gallery -> GalleryScreen(
            gallery, galleryState, galleryFocus,
            onBack = { screen = Screen.Camera },
            onImport = launchPicker,
            onRedevelop = { id ->
                editorReturn = Screen.Gallery
                editor.openNegative(id)
                screen = Screen.Editor
            },
            onPhotoDeleted = {},
        )
        Screen.Editor -> EditorScreen(editor, editorState, onBack = {
            editor.close()
            screen = editorReturn
        })
        Screen.Camera -> {
            val nav = CameraNav(
                onLicenses = { screen = Screen.Licenses },
                onGallery = ::openGallery,
                onImport = launchPicker,
            )
            PermissionGate(state.permission, vm::onIntent, onOpenLicenses = { screen = Screen.Licenses }) {
                CameraScreen(
                    state = state,
                    onIntent = vm::onIntent,
                    previewHost = vm.previewHost,
                    onAttach = { vm.attach(owner) },
                    onDetach = { vm.detach(owner) },
                    onScreenFlash = vm::setScreenFlash,
                    nav = nav,
                )
            }
        }
    }
}
