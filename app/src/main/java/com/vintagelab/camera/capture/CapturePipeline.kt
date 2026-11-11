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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import kotlin.math.sqrt

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

/** Immutable snapshot of everything frozen at shutter time, after the timer (K17a). */
data class CaptureSettings(
    val preset: Preset,
    val state: LookState,
    val time: LocalDateTime,
    /** EXIF Software (app_name). */
    val software: String,
)

/** K4: typed capture failures, mapped to localized snackbars by the UI. */
enum class CaptureErrorKind { SaveFailed, StorageFull, DevelopFailed }

class CaptureException(val kind: CaptureErrorKind, cause: Throwable?) : Exception(kind.name, cause)

/**
 * Capture path (SPEC §6.5): decode sRGB → same render graph at the memory-budgeted cap →
 * JPEG q92 streamed into the pending MediaStore row + EXIF. The whole render/compress/save
 * attempt is retried once at [RETRY_LONG_SIDE] on OutOfMemoryError. Frozen interface: [process].
 */
class CapturePipeline(
    private val renderer: OffscreenRenderer,
    private val saver: MediaStoreSaver,
    /** ActivityManager.memoryClass (or largeMemoryClass with largeHeap), MB. */
    private val memoryClassMb: Int,
) {
    suspend fun process(input: CaptureInput, settings: CaptureSettings): Result<Uri> {
        val exif = ExifWriter.plan(ExifWriter.readSource(input.jpeg), settings.software, settings.time)
        val limits = try {
            renderer.limits()
        } catch (e: Exception) {
            return Result.failure(CaptureException(CaptureErrorKind.DevelopFailed, e))
        }
        val cap = minOf(limits.maxLongSide, capForMemory(memoryClassMb))
        return try {
            Result.success(attempt(input, settings, exif, cap, limits.maxTexture))
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "OOM during capture at cap $cap; retrying at $RETRY_LONG_SIDE")
            renderer.trim()
            System.gc()
            try {
                Result.success(attempt(input, settings, exif, RETRY_LONG_SIDE, RETRY_LONG_SIDE))
            } catch (oom2: OutOfMemoryError) {
                Result.failure(CaptureException(CaptureErrorKind.DevelopFailed, oom2))
            } catch (e: Exception) {
                Result.failure(classify(e))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(classify(e))
        }
    }

    private suspend fun attempt(
        input: CaptureInput,
        settings: CaptureSettings,
        exif: Map<String, String>,
        cap: Int,
        maxTexture: Int,
    ): Uri {
        val out = develop(input, settings, cap, maxTexture)
        // The decoded input is recycled and the readback buffer dropped inside develop(); only
        // the output bitmap is alive while JPEG bytes stream into the MediaStore row.
        return withContext(Dispatchers.IO) {
            try {
                saver.save(
                    settings.time,
                    writeJpeg = { os ->
                        if (!out.compress(Bitmap.CompressFormat.JPEG, StorageConfig.JPEG_QUALITY, os)) {
                            throw IOException("JPEG encode failed")
                        }
                    },
                    writeExif = { fd -> ExifWriter.write(fd, exif) },
                )
            } finally {
                out.recycle()
            }
        }
    }

    private suspend fun develop(input: CaptureInput, settings: CaptureSettings, cap: Int, maxTexture: Int): Bitmap {
        val bitmap = try {
            withContext(Dispatchers.Default) { decode(input.jpeg, cap, maxTexture) }
        } catch (e: IllegalStateException) {
            throw CaptureException(CaptureErrorKind.DevelopFailed, e)
        }
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
        return try {
            renderer.render(bitmap, transform, settings.preset, settings.state, cap)
        } catch (e: IllegalStateException) {
            throw CaptureException(CaptureErrorKind.DevelopFailed, e)
        }
    }

    companion object {
        private const val TAG = "CapturePipeline"
        const val RETRY_LONG_SIDE = 2048
        const val MAX_LONG_SIDE = 4096

        /**
         * Render cap from the heap budget: half of memoryClass for the two full-size buffers that
         * coexist (readback + output bitmap, 8 B/px), 4:3 canvas, clamped to [RETRY_LONG_SIDE, 4096].
         */
        fun capForMemory(memoryClassMb: Int): Int {
            val budget = memoryClassMb.toLong() * 1024 * 1024 / 2
            val pixels = budget / 8
            val long = sqrt(pixels * 4.0 / 3.0).toInt()
            return long.coerceIn(RETRY_LONG_SIDE, MAX_LONG_SIDE)
        }

        fun classify(e: Throwable): CaptureException = when {
            e is CaptureException -> e
            e is IOException && (e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true) ->
                CaptureException(CaptureErrorKind.StorageFull, e)
            e is IOException || e is SecurityException || e is IllegalArgumentException -> CaptureException(CaptureErrorKind.SaveFailed, e)
            else -> CaptureException(CaptureErrorKind.DevelopFailed, e)
        }

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
