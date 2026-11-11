package com.vintagelab.camera.camera

import android.content.Context
import android.util.Log
import android.util.Rational
import android.util.Size
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

enum class FlashMode(val cameraX: Int) {
    OFF(ImageCapture.FLASH_MODE_OFF), ON(ImageCapture.FLASH_MODE_ON), AUTO(ImageCapture.FLASH_MODE_AUTO)
}

/** What the current binding supports (for hiding controls). */
data class CameraCapabilities(val hasFlash: Boolean, val hasFront: Boolean, val hasBack: Boolean)

/**
 * CameraX wrapper (SPEC §5.2, §6.5, §8): Preview + ImageCapture in one UseCaseGroup with a shared
 * ViewPort for the selected ratio; focus, zoom, flash. All calls on the main thread.
 */
class CameraController(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val captureExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "capture-io") }

    /** Called with a user-facing reason when the camera reports an error. */
    var onCameraError: ((String) -> Unit)? = null

    suspend fun bind(
        owner: LifecycleOwner,
        lens: Lens,
        ratio: AspectRatio,
        flash: FlashMode,
        displayRotation: Int,
        surfaceProvider: Preview.SurfaceProvider,
    ): CameraCapabilities {
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
            .setFlashMode(if (useLens == Lens.FRONT) ImageCapture.FLASH_MODE_OFF else flash.cameraX)
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
        val cam = p.bindToLifecycle(owner, selector, group)
        camera = cam
        preview = previewUseCase
        imageCapture = captureUseCase
        this.surfaceProvider = surfaceProvider
        cam.cameraInfo.cameraState.removeObservers(owner)
        cam.cameraInfo.cameraState.observe(owner) { state ->
            val err = state.error ?: return@observe
            Log.w(TAG, "camera error ${err.code} (${state.type})")
            if (state.type == CameraState.Type.CLOSED || err.code == CameraState.ERROR_CAMERA_DISABLED ||
                err.code == CameraState.ERROR_CAMERA_FATAL_ERROR || err.code == CameraState.ERROR_CAMERA_IN_USE ||
                err.code == CameraState.ERROR_MAX_CAMERAS_IN_USE || err.code == CameraState.ERROR_OTHER_RECOVERABLE_ERROR
            ) {
                onCameraError?.invoke("camera error ${err.code}")
            }
        }
        return CameraCapabilities(cam.cameraInfo.hasFlashUnit() && useLens == Lens.BACK, hasFront, hasBack)
    }

    /** After a GL context loss: makes CameraX issue a fresh SurfaceRequest. */
    fun resetSurfaceProvider() {
        val sp = surfaceProvider ?: return
        preview?.setSurfaceProvider(mainExecutor, sp)
    }

    fun setFlash(flash: FlashMode) {
        imageCapture?.flashMode = flash.cameraX
    }

    fun setLinearZoom(z: Float) {
        camera?.cameraControl?.setLinearZoom(z.coerceIn(0f, 1f))
    }

    /** Tap-to-focus at a point in preview buffer pixels; auto-cancels after 3 s. */
    fun focus(x: Float, y: Float, bufWidth: Int, bufHeight: Int) {
        val cam = camera ?: return
        val pv = preview ?: return
        val factory = SurfaceOrientedMeteringPointFactory(bufWidth.toFloat(), bufHeight.toFloat(), pv)
        val action = FocusMeteringAction.Builder(factory.createPoint(x, y), FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    /** In-memory JPEG capture; the ImageProxy is closed as soon as its bytes are copied. */
    suspend fun takePicture(): CaptureInput {
        val ic = imageCapture ?: throw IllegalStateException("camera not bound")
        return suspendCancellableCoroutine { cont ->
            ic.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val result = try {
                            val buf = image.planes[0].buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            val c = image.cropRect
                            CaptureInput(bytes, image.imageInfo.rotationDegrees, IntRect(c.left, c.top, c.right, c.bottom), image.width, image.height)
                        } catch (e: Exception) {
                            image.close()
                            cont.resumeWithException(e)
                            return
                        }
                        image.close()
                        cont.resume(result)
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
    }

    private companion object {
        const val TAG = "CameraController"
    }
}
