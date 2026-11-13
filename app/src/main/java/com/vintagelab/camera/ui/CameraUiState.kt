package com.vintagelab.camera.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.vintagelab.camera.camera.FlashMode
import com.vintagelab.camera.camera.Lens
import com.vintagelab.camera.capture.CaptureErrorKind
import com.vintagelab.camera.capture.FlashGel
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.capture.MultiExposure
import com.vintagelab.camera.capture.MultiMode
import com.vintagelab.camera.capture.MultiShotState
import com.vintagelab.camera.capture.MultiShotRules
import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.PrismMode
import com.vintagelab.camera.video.RecordingPhase
import com.vintagelab.camera.video.RecordingState

enum class CameraMode { Photo, Video }

enum class TimerMode(val seconds: Int) { OFF(0), S3(3), S10(10) }

enum class PermissionState { Granted, Denied, PermanentlyDenied }

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Countdown(val n: Int) : CaptureState

    /** The shutter cannot accept a shot (a picture is being taken, or the develop queue is full). */
    data object Processing : CaptureState
    data class Saved(val uri: Uri) : CaptureState

    /** K4: typed, shown once per [seq]. */
    data class Error(val kind: CaptureErrorKind, val seq: Long) : CaptureState
}

/** K3. */
enum class CameraErrorKind { InUse, Disabled, Fatal, GpuUnsupported }

sealed interface CameraStatus {
    data object Opening : CameraStatus
    data object Ready : CameraStatus
    data class Error(val kind: CameraErrorKind) : CameraStatus
}

/** K5: what the bound camera supports. */
@Immutable
data class CameraCaps(
    val hasBack: Boolean = true,
    val hasFront: Boolean = true,
    /** Flash unit on the current lens. */
    val hasFlashUnit: Boolean = false,
    /** Front lens: screen flash available (app-drawn overlay, K12). */
    val screenFlash: Boolean = false,
)

/** K6: one entry of the look picker, in presets.json order. */
@Immutable
data class LookUi(val id: String, val displayNameRes: String, val hasRandomFx: Boolean)

enum class FocusPhase { Focusing, Locked, Failed }

/** K2: last tap-to-focus, normalized image-area coordinates. */
@Immutable
data class FocusUi(val nx: Float, val ny: Float, val phase: FocusPhase, val seq: Long)

/** K7. */
enum class RenderMode { Normal, Passthrough }

/** SPEC §6.2 (+ K1–K17, DESIGN §10.4). Frozen after Phase A: fields may be added, not renamed. */
@Immutable
data class CameraUiState(
    val mode: CameraMode = CameraMode.Photo,
    val lookId: String = "classic35",
    val lens: Lens = Lens.BACK,
    val flash: FlashMode = FlashMode.OFF,
    val ratio: AspectRatio = AspectRatio.R4_3,
    val timer: TimerMode = TimerMode.OFF,
    val frame: FrameId = FrameId.NONE,
    val dateStamp: Boolean = false,
    /** CameraX linear zoom [0,1]. */
    val zoom: Float = 0f,
    val lookSeed: Long = 0L,
    val captureState: CaptureState = CaptureState.Idle,
    val permission: PermissionState = PermissionState.Denied,
    val lastShot: Uri? = null,
    /** K1 */
    val zoomRatio: Float = 1f,
    val zoomRange: ClosedFloatingPointRange<Float> = 1f..1f,
    /** K2 */
    val focus: FocusUi? = null,
    /** K3 */
    val camera: CameraStatus = CameraStatus.Opening,
    /** K5 */
    val caps: CameraCaps = CameraCaps(),
    /** K6 */
    val looks: List<LookUi> = emptyList(),
    /** K7 */
    val renderMode: RenderMode = RenderMode.Normal,
    /** K14: upright camera image is taller than wide (from the display rotation used for the ViewPort). */
    val imagePortrait: Boolean = true,
    /** Shots taken but not yet saved (0..2); drives the thumbnail "developing" pulse. */
    val developing: Int = 0,
    /** Bumped at every shutter press that takes a picture: drives the viewfinder blink. */
    val shutterSeq: Long = 0L,
    /** SPEC 7.4: armed multi-shot mode and its progress (Off only with MultiMode.None). */
    val multiMode: MultiMode = MultiMode.None,
    val multiExposure: MultiExposure = MultiExposure.Off,
    /** Bumped each time a stored first exposure was lost (background, trim, OOM); shows a notice once per value. */
    val multiDropSeq: Long = 0L,
    /** SPEC 7.4: user-selected flash gel (persisted); applied only when a flash fires. */
    val flashGel: FlashGel = FlashGel.NONE,
    /** SPEC 7.4: grid burst progress, pictures taken so far (0..4). */
    val gridShots: Int = 0,
    /** SPEC 7.4: prism lens (persisted). */
    val prism: PrismMode = PrismMode.OFF,
    /** SPEC 6.9: keep a re-developable negative with every saved photo (persisted, default on). */
    val keepNegatives: Boolean = true,
    /** Video recording lifecycle; elapsed time is capped at three minutes. */
    val recording: RecordingState = RecordingState(),
    /** Incremented when AppRoot must launch the RECORD_AUDIO runtime dialog. */
    val audioPermissionRequestSeq: Long = 0L,
    /** One-shot notices consumed by CameraScreen. */
    val audioDeniedNoticeSeq: Long = 0L,
    val videoFallbackNoticeSeq: Long = 0L,
    val videoErrorSeq: Long = 0L,
    val videoStorageFull: Boolean = false,
)

/** The multi-shot slice of the UI state as a pure value. */
val CameraUiState.multi: MultiShotState get() = MultiShotState(multiMode, multiExposure, gridShots)

sealed interface CameraIntent {
    data class SetMode(val mode: CameraMode) : CameraIntent
    data class SelectLook(val id: String) : CameraIntent
    data object ToggleLens : CameraIntent
    data object CycleFlash : CameraIntent
    data class SetRatio(val ratio: AspectRatio) : CameraIntent
    data class SetTimer(val timer: TimerMode) : CameraIntent
    data object CancelTimer : CameraIntent
    data class SetFrame(val frame: FrameId) : CameraIntent
    data object ToggleDateStamp : CameraIntent

    /** SPEC 7.4 */
    data object ToggleDoubleExposure : CameraIntent
    data object ToggleHalfFrame : CameraIntent
    data object ToggleGrid : CameraIntent
    data object CyclePrism : CameraIntent
    data object CancelFirstExposure : CameraIntent
    data object CycleFlashGel : CameraIntent
    data object ToggleKeepNegatives : CameraIntent
    data object Shuffle : CameraIntent
    data object Shutter : CameraIntent

    /** K10: normalized [0,1]² in the image area (frame margin excluded), upright display orientation. */
    data class TapFocus(val x: Float, val y: Float) : CameraIntent
    data class Zoom(val linear: Float) : CameraIntent

    /** K8 */
    data class PermissionResult(val granted: Boolean, val canAskAgain: Boolean) : CameraIntent
    data class RefreshPermission(val granted: Boolean) : CameraIntent

    /** K3 */
    data object RetryCamera : CameraIntent

    /** K9 */
    data object LastShotUnavailable : CameraIntent

    data class RecordAudioPermissionResult(val granted: Boolean) : CameraIntent
}

/** Pure reducers shared by the ViewModel and tests. */
object CameraRules {
    /** DESIGN §5.1: back OFF › AUTO › ON › OFF; front (screen flash) skips AUTO, a stored AUTO reads as ON. */
    fun nextFlash(current: FlashMode, lens: Lens): FlashMode = when (lens) {
        Lens.BACK -> when (current) {
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
        }
        Lens.FRONT -> if (current == FlashMode.OFF) FlashMode.ON else FlashMode.OFF
    }

    /** K8: launcher results set any state; refreshes only move to/from Granted. */
    fun onPermissionResult(granted: Boolean, canAskAgain: Boolean): PermissionState = when {
        granted -> PermissionState.Granted
        canAskAgain -> PermissionState.Denied
        else -> PermissionState.PermanentlyDenied
    }

    fun onRefreshPermission(current: PermissionState, granted: Boolean): PermissionState = when {
        granted -> PermissionState.Granted
        current == PermissionState.Granted -> PermissionState.Denied
        else -> current
    }

    /** DESIGN §6.1 enablement matrix. */
    fun lensRatioEnabled(s: CameraUiState): Boolean =
        s.camera == CameraStatus.Ready && s.captureState !is CaptureState.Countdown && s.captureState != CaptureState.Processing &&
            s.recording.phase !in setOf(RecordingPhase.Starting, RecordingPhase.Recording, RecordingPhase.Stopping)

    /** Ratio is locked in half-frame (forced 4:3) and between the shots of a pair. */
    fun ratioEnabled(s: CameraUiState): Boolean = lensRatioEnabled(s) && !MultiShotRules.ratioLocked(s.multi)

    fun frameEnabled(s: CameraUiState): Boolean = !MultiShotRules.frameLocked(s.multi)

    /** Frame cycle order; half-frame and grid skip frames that force a ratio (the composite has its own shape). */
    fun nextFrame(current: FrameId, half: Boolean): FrameId {
        var f = current
        repeat(FrameId.entries.size) {
            f = FrameId.entries[(f.ordinal + 1) % FrameId.entries.size]
            if (!half || FrameGeometry.forcedAspect(f, true) == null) return f
        }
        return current
    }

    /** Gel only means something when a flash can fire. */
    fun gelVisible(s: CameraUiState): Boolean = s.flash != FlashMode.OFF && (if (s.lens == Lens.FRONT) s.caps.screenFlash else s.caps.hasFlashUnit)

    fun timerEnabled(s: CameraUiState): Boolean = s.captureState !is CaptureState.Countdown

    fun shutterEnabled(s: CameraUiState): Boolean = s.camera == CameraStatus.Ready &&
        if (s.mode == CameraMode.Video) s.recording.phase != RecordingPhase.Stopping
        else s.captureState != CaptureState.Processing
}
