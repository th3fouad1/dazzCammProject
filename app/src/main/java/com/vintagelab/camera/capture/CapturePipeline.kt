package com.vintagelab.camera.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import com.vintagelab.camera.gl.OffscreenRenderer
import com.vintagelab.camera.looks.CropMath
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
    /** [LookState.gel] carries the gel the user selected; the pipeline applies it only if a flash fired. */
    val state: LookState,
    val time: LocalDateTime,
    /** EXIF Software (app_name). */
    val software: String,
    /** Front lens with the screen flash enabled at the shutter (EXIF cannot report it). */
    val screenFlash: Boolean = false,
)

/** K4: typed capture failures, mapped to localized snackbars by the UI. */
enum class CaptureErrorKind { SaveFailed, StorageFull, DevelopFailed }

class CaptureException(val kind: CaptureErrorKind, cause: Throwable?) : Exception(kind.name, cause)

/** The stored first exposure is gone (trim, background, OOM): the pair restarts (SPEC 7.4). */
class FirstExposureMissing : Exception("first exposure missing")

/**
 * Capture path (SPEC §6.5): decode sRGB → same render graph at the memory-budgeted cap →
 * JPEG q92 streamed into the pending MediaStore row + EXIF. The whole render/compress/save
 * attempt is retried once at [RETRY_LONG_SIDE] on OutOfMemoryError. Frozen interface: [process].
 *
 * Multi-shot modes (SPEC 7.4): [processFirst] develops and keeps shot 1 in memory (never saved),
 * [processSecond] combines it with shot 2 and saves once. The stored first exposure is a single
 * bitmap capped at [STORE_LONG_SIDE]; [clearFirst] drops it (background, trim, OOM, cancel).
 */
class CapturePipeline(
    private val renderer: OffscreenRenderer,
    private val saver: MediaStoreSaver,
    /** ActivityManager.memoryClass (or largeMemoryClass with largeHeap), MB. */
    private val memoryClassMb: Int,
) {
    private class First(val mode: MultiMode, val bitmap: Bitmap) {
        var busy = false
    }

    private val lock = Any()
    private var first: First? = null

    /** True while a first exposure for [mode] is held. */
    fun hasFirst(mode: MultiMode): Boolean = synchronized(lock) { first?.mode == mode }

    /** Drops the stored first exposure (frees its memory unless a second shot is using it right now). */
    fun clearFirst() {
        synchronized(lock) {
            val f = first ?: return
            first = null
            if (!f.busy) f.bitmap.recycle()
        }
    }

    private fun store(mode: MultiMode, bitmap: Bitmap) {
        clearFirst()
        synchronized(lock) { first = First(mode, bitmap) }
    }

    private fun borrow(mode: MultiMode): First {
        synchronized(lock) {
            val f = first?.takeIf { it.mode == mode && !it.bitmap.isRecycled } ?: throw FirstExposureMissing()
            f.busy = true
            return f
        }
    }

    /** [consume]: second shot saved, drop the first. Otherwise keep it for a retry (unless already dropped). */
    private fun giveBack(f: First, consume: Boolean) {
        synchronized(lock) {
            f.busy = false
            if (consume && first === f) first = null
            if (first !== f) f.bitmap.recycle()
        }
    }

    suspend fun process(input: CaptureInput, settings: CaptureSettings): Result<Uri> {
        val exif = ExifWriter.plan(ExifWriter.readSource(input.jpeg), settings.software, settings.time)
        val state = withGel(input, settings)
        return guarded { cap, maxTexture ->
            val out = develop(input, settings.preset, state, cap, maxTexture)
            save(out, settings, exif)
        }
    }

    /**
     * Shot 1 of a double exposure or half-frame: develops and stores it, saves nothing.
     * Success carries the preview ghost (double exposure, long side [GHOST_LONG_SIDE]) or null (half-frame).
     */
    suspend fun processFirst(input: CaptureInput, settings: CaptureSettings, mode: MultiMode): Result<Bitmap?> {
        require(mode != MultiMode.None)
        return guarded { cap, maxTexture ->
            val storeCap = minOf(cap, STORE_LONG_SIDE)
            if (mode == MultiMode.Half) {
                store(mode, halfBitmap(input, storeCap, maxTexture))
                null
            } else {
                val out = develop(input, settings.preset, settings.state, storeCap, maxTexture, firstExposure = true)
                store(mode, out)
                ghostOf(out)
            }
        }.onFailure { clearFirst() }
    }

    /** Shot 2: combines it with the stored first (screen blend or diptych), applies grain/frame/stamp once, saves. */
    suspend fun processSecond(input: CaptureInput, settings: CaptureSettings, mode: MultiMode): Result<Uri> {
        require(mode != MultiMode.None)
        val exif = ExifWriter.plan(ExifWriter.readSource(input.jpeg), settings.software, settings.time)
        val state = withGel(input, settings)
        val f = try {
            borrow(mode)
        } catch (e: FirstExposureMissing) {
            return Result.failure(e)
        }
        var saved = false
        try {
            val r = guarded { cap, maxTexture ->
                val out = if (mode == MultiMode.Half) {
                    val right = halfBitmap(input, cap, maxTexture)
                    val whole = compose(f.bitmap, right, cap)
                    renderComposed(whole, settings.preset, state, cap)
                } else {
                    develop(input, settings.preset, state, cap, maxTexture, blend = f.bitmap)
                }
                save(out, settings, exif)
            }
            saved = r.isSuccess
            // An OOM that survived the retry loses the pair (SPEC 7.4): free the memory for the next attempt.
            if (r.exceptionOrNull()?.let { it is CaptureException && it.cause is OutOfMemoryError } == true) clearFirst()
            return r
        } finally {
            giveBack(f, consume = saved)
        }
    }

    private fun withGel(input: CaptureInput, settings: CaptureSettings): LookState =
        settings.state.copy(
            gel = FlashGel.effective(settings.state.gel, ExifWriter.flashFired(input.jpeg), settings.screenFlash),
        )

    /** Runs [body] at the memory-budgeted cap, retrying once at [RETRY_LONG_SIDE] on OOM. */
    private suspend fun <T> guarded(body: suspend (cap: Int, maxTexture: Int) -> T): Result<T> {
        val limits = try {
            renderer.limits()
        } catch (e: Exception) {
            return Result.failure(CaptureException(CaptureErrorKind.DevelopFailed, e))
        }
        val cap = minOf(limits.maxLongSide, capForMemory(memoryClassMb))
        return try {
            Result.success(body(cap, limits.maxTexture))
        } catch (oom: OutOfMemoryError) {
            Log.w(TAG, "OOM during capture at cap $cap; retrying at $RETRY_LONG_SIDE")
            renderer.trim()
            System.gc()
            try {
                Result.success(body(RETRY_LONG_SIDE, RETRY_LONG_SIDE))
            } catch (oom2: OutOfMemoryError) {
                Result.failure(CaptureException(CaptureErrorKind.DevelopFailed, oom2))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(classify(e))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(classify(e))
        }
    }

    private suspend fun save(out: Bitmap, settings: CaptureSettings, exif: Map<String, String>): Uri {
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

    private suspend fun decodeBitmap(input: CaptureInput, cap: Int, maxTexture: Int): Bitmap =
        try {
            withContext(Dispatchers.Default) { decode(input.jpeg, cap, maxTexture) }
        } catch (e: IllegalStateException) {
            throw CaptureException(CaptureErrorKind.DevelopFailed, e)
        }

    /** Camera crop scaled into the (possibly subsampled) [bitmap]'s coordinates. */
    private fun scaledCrop(bitmap: Bitmap, input: CaptureInput): IntRect {
        val sx = bitmap.width.toFloat() / input.width
        val sy = bitmap.height.toFloat() / input.height
        val c = input.cropRect
        return IntRect(
            (c.left * sx).toInt().coerceIn(0, bitmap.width - 1),
            (c.top * sy).toInt().coerceIn(0, bitmap.height - 1),
            (c.right * sx).toInt().coerceIn(1, bitmap.width),
            (c.bottom * sy).toInt().coerceIn(1, bitmap.height),
        )
    }

    private suspend fun develop(
        input: CaptureInput,
        preset: Preset,
        state: LookState,
        cap: Int,
        maxTexture: Int,
        blend: Bitmap? = null,
        firstExposure: Boolean = false,
    ): Bitmap {
        val bitmap = decodeBitmap(input, cap, maxTexture)
        var crop = scaledCrop(bitmap, input)
        crop = FrameGeometry.constrainCrop(crop, input.rotationDegrees, state.frame)
        // Saved photos are never mirrored (SPEC §6.3).
        val transform = SourceTransform(bitmap.width, bitmap.height, crop, input.rotationDegrees, mirror = false)
        return try {
            renderer.render(bitmap, transform, preset, state, cap, blend = blend, firstExposure = firstExposure)
        } catch (e: IllegalStateException) {
            throw CaptureException(CaptureErrorKind.DevelopFailed, e)
        }
    }

    /** One upright half (aspect [Diptych.HALF_ASPECT] via the existing constrainToAspect crop), at most the half size for [cap]. */
    private suspend fun halfBitmap(input: CaptureInput, cap: Int, maxTexture: Int): Bitmap {
        val layout = Diptych.layout(cap)
        val bitmap = decodeBitmap(input, cap, maxTexture)
        val rot = ((input.rotationDegrees % 360) + 360) % 360
        val c = CropMath.constrainToAspect(scaledCrop(bitmap, input), rot, Diptych.HALF_ASPECT)
        val uprightH = if (rot == 90 || rot == 270) c.width else c.height
        val s = minOf(1f, layout.halfH.toFloat() / uprightH)
        val m = Matrix().apply { postRotate(rot.toFloat()); postScale(s, s) }
        val half = Bitmap.createBitmap(bitmap, c.left, c.top, c.width, c.height, m, true)
        if (half !== bitmap) bitmap.recycle()
        return half
    }

    /** Places the halves side by side on a dark base (SPEC 7.4); recycles [right], not [left] (owned by the store). */
    private fun compose(left: Bitmap, right: Bitmap, cap: Int): Bitmap {
        val l = Diptych.layout(cap)
        val whole = Bitmap.createBitmap(l.canvasW, l.canvasH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(whole)
            canvas.drawColor(0xFF000000.toInt() or Diptych.GAP_COLOR)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(left, null, Rect(0, 0, l.halfW, l.halfH), paint)
            canvas.drawBitmap(right, null, Rect(l.rightX, 0, l.rightX + l.halfW, l.halfH), paint)
        } finally {
            right.recycle()
        }
        return whole
    }

    private suspend fun renderComposed(whole: Bitmap, preset: Preset, state: LookState, cap: Int): Bitmap {
        // The diptych already has its final shape: no forced-ratio crop (SPEC 7.4).
        val transform = SourceTransform(
            whole.width, whole.height, IntRect(0, 0, whole.width, whole.height), 0, mirror = false,
        )
        return try {
            renderer.render(whole, transform, preset, state, cap)
        } catch (e: IllegalStateException) {
            throw CaptureException(CaptureErrorKind.DevelopFailed, e)
        }
    }

    private fun ghostOf(b: Bitmap): Bitmap {
        val k = GHOST_LONG_SIDE.toFloat() / maxOf(b.width, b.height)
        if (k >= 1f) return b.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true)
    }

    companion object {
        private const val TAG = "CapturePipeline"
        const val RETRY_LONG_SIDE = 2048
        const val MAX_LONG_SIDE = 4096

        /** Long-side cap of the stored first exposure (SPEC 7.4): keeps a third buffer affordable. */
        const val STORE_LONG_SIDE = 3072

        /** Long side of the preview ghost. */
        const val GHOST_LONG_SIDE = 512

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
