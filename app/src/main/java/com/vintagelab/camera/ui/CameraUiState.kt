package com.vintagelab.camera.ui

import android.net.Uri
import com.vintagelab.camera.camera.FlashMode
import com.vintagelab.camera.camera.Lens
import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.FrameId

enum class TimerMode(val seconds: Int) { OFF(0), S3(3), S10(10) }

enum class PermissionState { Granted, Denied, PermanentlyDenied }

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Countdown(val n: Int) : CaptureState
    data object Processing : CaptureState
    data class Saved(val uri: Uri) : CaptureState
    data class Error(val msg: String) : CaptureState
}

/** One entry of the look picker. */
data class LookItem(val id: String, val displayNameRes: String)

/** SPEC §6.2. Frozen after Phase A (fields may be added, not renamed). */
data class CameraUiState(
    val lookId: String = "classic35",
    val lens: Lens = Lens.BACK,
    val flash: FlashMode = FlashMode.OFF,
    val ratio: AspectRatio = AspectRatio.R4_3,
    val timer: TimerMode = TimerMode.OFF,
    val frame: FrameId = FrameId.NONE,
    val dateStamp: Boolean = false,
    val zoom: Float = 0f,
    val lookSeed: Long = 0L,
    val captureState: CaptureState = CaptureState.Idle,
    val permission: PermissionState = PermissionState.Denied,
    val lastShot: Uri? = null,
    // Not in §6.2's list; needed by the UI:
    val looks: List<LookItem> = emptyList(),
    val hasFlash: Boolean = true,
    val hasFront: Boolean = true,
    val cameraError: String? = null,
)

sealed interface CameraIntent {
    data class SelectLook(val id: String) : CameraIntent
    data object ToggleLens : CameraIntent
    data object CycleFlash : CameraIntent
    data class SetRatio(val ratio: AspectRatio) : CameraIntent
    data class SetTimer(val timer: TimerMode) : CameraIntent
    data object CancelTimer : CameraIntent
    data class SetFrame(val frame: FrameId) : CameraIntent
    data object ToggleDateStamp : CameraIntent
    data object Shuffle : CameraIntent
    data object Shutter : CameraIntent

    /** View pixels, origin top-left. */
    data class TapFocus(val x: Float, val y: Float) : CameraIntent
    data class Zoom(val linear: Float) : CameraIntent
}
