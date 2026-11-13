package com.vintagelab.camera.camera

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vintagelab.camera.capture.CaptureInput
import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.IntRect
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.camera.core.AspectRatio as CxAspectRatio

enum class Lens { BACK, FRONT }

enum class FlashMode { OFF, ON, AUTO }

/** What the current binding supports (for hiding controls). */
data class CameraCapabilities(val hasFlash: Boolean, val hasFront: Boolean, val hasBack: Boolean, val screenFlash: Boolean, val lens: Lens)

/** Camera-layer status, mapped to UI CameraStatus by the ViewModel. */
sealed interface CameraEvent {
    data object Opening : CameraEvent
    data object Ready : CameraEvent
    data class Failed(val inUse: Boolean, val disabled: Boolean) : CameraEvent
    data class ZoomChanged(val linear: Float, val ratio: Float, val min: Float, val max: Float) : CameraEvent
}

/**
 * CameraX wrapper (SPEC §5.2, §6.5, §8): Preview + ImageCapture in one UseCaseGroup with a shared
 * ViewPort for the selected ratio; focus, zoom, flash (incl. screen flash, K12), and
 * 180° display flips (K16). All calls on the main thread.
 */
class CameraController(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var bindGeneration = 0
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var owner: LifecycleOwner? = null
    private var lens = Lens.BACK
    private var flash = FlashMode.OFF
    private var screenFlash: ImageCapture.ScreenFlash? = null
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val captureExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "capture-io") }
    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    var onEvent: ((CameraEvent) -> Unit)? = null

    /** K16: 180° flips don't change configuration; follow the display rotation directly. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val r = displayManager.getDisplay(displayId)?.rotation ?: return
            preview?.targetRotation = r
            imageCapture?.targetRotation = r
        }
    }

    init {
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    suspend fun bind(
        owner: LifecycleOwner,
        lens: Lens,
        ratio: AspectRatio,
        flash: FlashMode,
        displayRotation: Int,
        surfaceProvider: Preview.SurfaceProvider,
    ): CameraCapabilities {
        onEvent?.invoke(CameraEvent.Opening)
        val p = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
        p.unbindAll()
        val hasBack = p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFront = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        val useLens = when {
            lens == Lens.FRONT && hasFront -> Lens.FRONT
            hasBack -> Lens.BACK
            else -> Lens.FRONT
        }
        val selector = if (useLens == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val cxRatio = if (ratio == AspectRatio.R16_9) CxAspectRatio.RATIO_16_9 else CxAspectRatio.RATIO_4_3
        val aspectStrategy = AspectRatioStrategy(cxRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val previewUseCase = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy).build())
            .setTargetRotation(displayRotation)
            .build()
        previewUseCase.setSurfaceProvider(mainExecutor, surfaceProvider)
        val captureUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
            // The app decodes and re-encodes once, so the camera JPEG is only an intermediate: keep it lossless-ish.
            .setJpegQuality(100)
            .setTargetRotation(displayRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectStrategy)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER),
                    )
                    .build(),
            )
            .build()
        screenFlash?.let { captureUseCase.screenFlash = it }
        val portrait = displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180
        val (rw, rh) = ratio.upright(portrait)
        val viewPort = ViewPort.Builder(Rational(rw, rh), displayRotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(previewUseCase)
            .addUseCase(captureUseCase)
            .build()
        // Detach observers of the previous camera before binding the new one (lens switch).
        camera?.cameraInfo?.let { old ->
            this.owner?.let { prevOwner ->
                old.cameraState.removeObservers(prevOwner)
                old.zoomState.removeObservers(prevOwner)
            }
        }
        val bindGen = ++bindGeneration
        val cam = p.bindToLifecycle(owner, selector, group)
        this.owner = owner
        this.lens = useLens
        camera = cam
        preview = previewUseCase
        imageCapture = captureUseCase
        this.surfaceProvider = surfaceProvider
        setFlash(flash)
        cam.cameraInfo.cameraState.removeObservers(owner)
        cam.cameraInfo.cameraState.observe(owner) { state ->
            if (bindGen == bindGeneration) onEvent?.invoke(map(state))
        }
        cam.cameraInfo.zoomState.removeObservers(owner)
        cam.cameraInfo.zoomState.observe(owner) { z ->
            if (bindGen != bindGeneration) return@observe
            onEvent?.invoke(CameraEvent.ZoomChanged(z.linearZoom, z.zoomRatio, z.minZoomRatio, z.maxZoomRatio))
        }
        return CameraCapabilities(
            hasFlash = cam.cameraInfo.hasFlashUnit() && useLens == Lens.BACK,
            hasFront = hasFront,
            hasBack = hasBack,
            screenFlash = useLens == Lens.FRONT,
            lens = useLens,
        )
    }

    private fun map(state: CameraState): CameraEvent {
        val err = state.error
        if (err != null) {
            Log.w(TAG, "camera error ${err.code} (${state.type})")
            val inUse = err.code == CameraState.ERROR_CAMERA_IN_USE || err.code == CameraState.ERROR_MAX_CAMERAS_IN_USE
            val disabled = err.code == CameraState.ERROR_CAMERA_DISABLED
            // Recoverable errors while still opening are transient; report them as Opening.
            if (!inUse && !disabled && err.type == CameraState.ErrorType.RECOVERABLE && state.type != CameraState.Type.CLOSED) {
                return CameraEvent.Opening
            }
            return CameraEvent.Failed(inUse, disabled)
        }
        return if (state.type == CameraState.Type.OPEN) CameraEvent.Ready else CameraEvent.Opening
    }

    /** After a GL context loss: makes CameraX issue a fresh SurfaceRequest. */
    fun resetSurfaceProvider() {
        val sp = surfaceProvider ?: return
        preview?.setSurfaceProvider(mainExecutor, sp)
    }

    /** K12: the UI-owned ScreenFlash used for the front lens when flash ≠ OFF. */
    fun setScreenFlash(flash: ImageCapture.ScreenFlash?) {
        screenFlash = flash
        imageCapture?.screenFlash = flash
        setFlash(this.flash)
    }

    fun setFlash(mode: FlashMode) {
        flash = mode
        val ic = imageCapture ?: return
        ic.flashMode = when {
            lens == Lens.FRONT && mode != FlashMode.OFF && screenFlash != null -> ImageCapture.FLASH_MODE_SCREEN
            lens == Lens.FRONT -> ImageCapture.FLASH_MODE_OFF
            mode == FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            mode == FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    fun setLinearZoom(z: Float) {
        camera?.cameraControl?.setLinearZoom(z.coerceIn(0f, 1f))
    }

    /** Tap-to-focus at a point in preview buffer pixels; auto-cancels after 3 s. Result → [onResult]. */
    fun focus(x: Float, y: Float, bufWidth: Int, bufHeight: Int, onResult: (Boolean) -> Unit) {
        val cam = camera ?: return
        val pv = preview ?: return
        val factory = SurfaceOrientedMeteringPointFactory(bufWidth.toFloat(), bufHeight.toFloat(), pv)
        val action = FocusMeteringAction.Builder(factory.createPoint(x, y), FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
            onResult(ok)
        }, mainExecutor)
    }

    /**
     * In-memory JPEG capture; returns once the bytes are copied and the ImageProxy is closed.
     * Plays the platform shutter click (U7: always on).
     */
    suspend fun takePicture(): CaptureInput {
        val ic = imageCapture ?: throw IllegalStateException("camera not bound")
        com.vintagelab.camera.perf.PerfLog.shotTap()
        // Click at the tap like the system camera; only when flash may fire does it wait for the
        // real capture start (precapture can take a while and the click marks the flash).
        val clickAtTap = ic.flashMode == ImageCapture.FLASH_MODE_OFF
        if (clickAtTap) sound.play(MediaActionSound.SHUTTER_CLICK)
        return suspendCancellableCoroutine { cont ->
            ic.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureStarted() {
                        if (!clickAtTap) sound.play(MediaActionSound.SHUTTER_CLICK)
                    }

                    override fun onCaptureSuccess(image: ImageProxy) {
                        com.vintagelab.camera.perf.PerfLog.shotCaptured()
                        val result = try {
                            val buf = image.planes[0].buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            val c = image.cropRect
                            Result.success(
                                CaptureInput(bytes, image.imageInfo.rotationDegrees, IntRect(c.left, c.top, c.right, c.bottom), image.width, image.height),
                            )
                        } catch (t: Throwable) {
                            // Includes OutOfMemoryError on the JPEG copy: surface as a capture failure.
                            Result.failure(t)
                        } finally {
                            image.close()
                        }
                        result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
        preview = null
        imageCapture = null
    }

    fun release() {
        unbind()
        displayManager.unregisterDisplayListener(displayListener)
        sound.release()
        captureExecutor.shutdown()
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
