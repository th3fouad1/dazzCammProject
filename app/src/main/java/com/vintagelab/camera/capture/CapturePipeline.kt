package com.vintagelab.camera.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.util.Log
import com.vintagelab.camera.gl.OffscreenRenderer
import com.vintagelab.camera.looks.IntRect
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.Preset
import com.vintagelab.camera.looks.SourceTransform
import com.vintagelab.camera.storage.MediaStoreSaver
import com.vintagelab.camera.storage.StorageConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

/** In-memory camera JPEG plus the metadata CameraX reports for it (ImageProxy already closed). */
class CaptureInput(
    val jpeg: ByteArray,
    /** ImageProxy.imageInfo.rotationDegrees */
    val rotationDegrees: Int,
    /** ImageProxy.cropRect, in the coordinates of the (unrotated) JPEG. */
    val cropRect: IntRect,
    val width: Int,
    val height: Int,
)

/** Everything frozen at shutter time (after the timer). */
data class CaptureSettings(
    val preset: Preset,
    val state: LookState,
    val time: LocalDateTime,
    /** EXIF Software (app_name). */
    val software: String,
)

/**
 * Capture path (SPEC §6.5): decode sRGB → same render graph at full (capped) resolution →
 * JPEG q92 + EXIF → MediaStore. Frozen interface: [process].
 */
class CapturePipeline(
    private val renderer: OffscreenRenderer,
    private val saver: MediaStoreSaver,
) {
    suspend fun process(input: CaptureInput, settings: CaptureSettings): Result<Uri> = runCatching {
        val limits = renderer.limits()
        val out = try {
            develop(input, settings, limits.maxLongSide, limits.maxTexture)
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "OOM during capture; retrying at long side $RETRY_LONG_SIDE")
            renderer.trim()
            System.gc()
            develop(input, settings, RETRY_LONG_SIDE, RETRY_LONG_SIDE)
        }
        val jpeg = withContext(Dispatchers.Default) {
            try {
                ByteArrayOutputStream(4 shl 20).also { out.compress(Bitmap.CompressFormat.JPEG, StorageConfig.JPEG_QUALITY, it) }
                    .toByteArray()
            } finally {
                out.recycle()
            }
        }
        val exif = ExifWriter.plan(ExifWriter.readSource(input.jpeg), settings.software, settings.time)
        withContext(Dispatchers.IO) {
            saver.save(jpeg, settings.time) { fd -> ExifWriter.write(fd, exif) }
        }
    }

    private suspend fun develop(input: CaptureInput, settings: CaptureSettings, cap: Int, maxTexture: Int): Bitmap {
        val bitmap = withContext(Dispatchers.Default) { decode(input.jpeg, maxOf(cap, 1), maxTexture) }
        // Bitmap may be subsampled: scale the crop rect into its coordinates.
        val sx = bitmap.width.toFloat() / input.width
        val sy = bitmap.height.toFloat() / input.height
        val c = input.cropRect
        val crop = IntRect(
            (c.left * sx).toInt().coerceIn(0, bitmap.width - 1),
            (c.top * sy).toInt().coerceIn(0, bitmap.height - 1),
            (c.right * sx).toInt().coerceIn(1, bitmap.width),
            (c.bottom * sy).toInt().coerceIn(1, bitmap.height),
        )
        // Saved photos are never mirrored (SPEC §6.3).
        val transform = SourceTransform(bitmap.width, bitmap.height, crop, input.rotationDegrees, mirror = false)
        return renderer.render(bitmap, transform, settings.preset, settings.state, cap)
    }

    companion object {
        private const val TAG = "CapturePipeline"
        const val RETRY_LONG_SIDE = 2048

        /** Power-of-two subsampling so the decoded long side ≤ max(texture limit, ~2× render cap). */
        fun sampleSize(w: Int, h: Int, cap: Int, maxTexture: Int): Int {
            val limit = minOf(maxTexture, cap * 2)
            var s = 1
            while (maxOf(w, h) / s > limit) s *= 2
            return s
        }

        fun decode(jpeg: ByteArray, cap: Int, maxTexture: Int): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, cap, maxTexture)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                ?: throw IllegalStateException("JPEG decode failed")
        }
    }
}
