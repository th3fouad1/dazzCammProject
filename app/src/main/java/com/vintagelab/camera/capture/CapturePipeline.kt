package com.vintagelab.camera.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.content.ContentUris
import android.net.Uri
import android.util.Log
import com.vintagelab.camera.gl.MergeRenderer
import com.vintagelab.camera.gl.OffscreenRenderer
import com.vintagelab.camera.looks.CropMath
import com.vintagelab.camera.looks.IntRect
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.Preset
import com.vintagelab.camera.looks.SourceTransform
import com.vintagelab.camera.negatives.NegativeSettings
import com.vintagelab.camera.negatives.NegativeStore
import com.vintagelab.camera.perf.PerfLog
import com.vintagelab.camera.storage.MediaStoreSaver
import com.vintagelab.camera.storage.StorageConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    /** Where EXIF / flash state is read from when [jpeg] was produced by the app (boost merge): the reference frame's header. */
    val exifJpeg: ByteArray? = null,
) {
    val meta: ByteArray get() = exifJpeg ?: jpeg
}

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

/** The grid burst was dropped (failure, background, new burst): later cells of it are ignored (SPEC 7.4). */
class GridAborted : Exception("grid burst aborted")

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
    /** Where negatives are kept (SPEC 6.9); null disables them (tests). */
    private val negatives: NegativeStore? = null,
    /** The "Keep negatives" setting, read at each save. */
    private val keepNegatives: () -> Boolean = { true },
) {
    /** A neutral source ready to be stored as a negative: JPEG q95 bytes and their size. */
    private class NegSource(val bytes: ByteArray, val width: Int, val height: Int)

    private class First(val mode: MultiMode, val bitmap: Bitmap) {
        var busy = false
    }

    private val lock = Any()
    private var first: First? = null

    /** Grid burst generations and cells (SPEC 7.4); shared by every ViewModel through this singleton. */
    private val bursts = GridBursts<Bitmap> { it.recycle() }

    /** New globally unique burst generation; supersedes (and frees) every older one. */
    fun beginGrid(): Long = bursts.begin()

    /** True while [burstId] is the newest, non-aborted generation. */
    fun isGridLive(burstId: Long): Boolean = bursts.isLive(burstId)

    /** Aborts [burstId] (tombstone: late cells are rejected) and frees its cells. */
    fun abortGrid(burstId: Long) = bursts.abort(burstId)

    /** Background: aborts the current burst, whatever its owner, and drops every grid bitmap. */
    fun abortAllGrids() = bursts.abortCurrent()

    /**
     * One picture of a multi-lens grid (SPEC 7.4). Cells 0..2 are developed into small upright bitmaps and kept;
     * the last one composes the 2x2 grid (gaps [GridComposite.GAP] x S), runs the graph once over it (look, grain, frame,
     * stamp) and saves. Success carries the saved Uri for the last cell, null otherwise.
     * Cells of a burst that was superseded or aborted fail with [GridAborted] and never touch the store.
     */
    suspend fun processGridCell(input: CaptureInput, settings: CaptureSettings, burstId: Long, index: Int): Result<Uri?> {
        if (!bursts.isLive(burstId)) return Result.failure(GridAborted())
        val last = index == GridComposite.COLS * GridComposite.ROWS - 1
        val exifSource = if (last) ExifWriter.readSource(input.meta) else emptyMap()
        val exif = if (last) ExifWriter.plan(exifSource, settings.software, settings.time) else emptyMap()
        val state = if (last) withGel(input, settings) else settings.state
        var neg: NegSource? = null
        val r: Result<Uri?> = if (!last) {
            // The guarded body only decodes (idempotent); the store is mutated once, after success.
            guarded { cap, maxTexture ->
                gridCell(input, minOf(cap, STORE_LONG_SIDE), maxTexture, bursts.aspectOf(burstId))
            }.mapCatching { cell ->
                bursts.add(burstId, index, cell.second, cell.first)
                null
            }
        } else {
            // Stored cells stay immutable sources through every attempt; composed and rendered afresh at each cap.
            guarded { cap, maxTexture ->
                val storeCap = minOf(cap, STORE_LONG_SIDE)
                val sources = bursts.snapshot(burstId, index)
                val aspect = bursts.aspectOf(burstId) ?: throw GridAborted()
                val cell = gridCell(input, storeCap, maxTexture, aspect).first
                val whole = try {
                    composeGrid(sources + cell, aspect, storeCap)
                } finally {
                    cell.recycle()
                }
                neg = if (keepNegatives()) compressNeg(whole) else null
                save(renderComposed(whole, settings.preset, state, cap), settings.time, exif)
            }
        }
        if (last) r.onSuccess { uri -> if (uri != null) recordNegative(uri, settings, state, NegativeSettings.MODE_GRID, exifSource, neg, null) }
        if (r.isFailure || last) bursts.finish(burstId)
        return r.recoverCatching { if (it is CaptureException && it.cause is GridAborted) throw it.cause!! else throw it }
    }

    /** One upright cell at the grid's cell size for [storeCap]; returns the bitmap and the cell aspect (w/h). */
    private suspend fun gridCell(input: CaptureInput, storeCap: Int, maxTexture: Int, aspect: Float?): Pair<Bitmap, Float> {
        val bitmap = decodeBitmap(input, storeCap, maxTexture)
        val rot = ((input.rotationDegrees % 360) + 360) % 360
        val swaps = rot == 90 || rot == 270
        val crop0 = scaledCrop(bitmap, input)
        val a = aspect ?: (if (swaps) crop0.height.toFloat() / crop0.width else crop0.width.toFloat() / crop0.height)
        val c = CropMath.constrainToAspect(crop0, rot, a)
        val uprightH = if (swaps) c.width else c.height
        val layout = GridComposite.layout(a, storeCap)
        val s = minOf(1f, layout.cellH.toFloat() / uprightH)
        val m = Matrix().apply { postRotate(rot.toFloat()); postScale(s, s) }
        val cell = Bitmap.createBitmap(bitmap, c.left, c.top, c.width, c.height, m, true)
        if (cell !== bitmap) bitmap.recycle()
        return cell to a
    }

    /** Draws the cells row-major on a dark base; the cells stay owned by the store (a retry composes them again). */
    private fun composeGrid(cells: List<Bitmap>, aspect: Float, cap: Int): Bitmap {
        val l = GridComposite.layout(aspect, cap)
        val whole = Bitmap.createBitmap(l.canvasW, l.canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(whole)
        canvas.drawColor(0xFF000000.toInt() or Diptych.GAP_COLOR)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        bursts.withLock {
            cells.forEachIndexed { i, b ->
                if (b.isRecycled) { whole.recycle(); throw GridAborted() }
                canvas.drawBitmap(b, null, Rect(l.cellX(i), l.cellY(i), l.cellX(i) + l.cellW, l.cellY(i) + l.cellH), paint)
            }
        }
        return whole
    }

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

    /**
     * SPEC 7.7: merges a boost burst into one camera-like input (JPEG q100 of the merge, EXIF header
     * of the reference frame) for the normal [process] path. Falls back to the reference frame when
     * the GPU cannot merge or memory runs out, so a boost shot never fails harder than a normal one.
     */
    suspend fun mergeBurst(inputs: List<CaptureInput>, plan: BurstPlan): CaptureInput {
        val ref = inputs.first()
        if (inputs.size < 2) return ref
        val t0 = android.os.SystemClock.elapsedRealtime()
        return try {
            if (!renderer.mergeSupported()) {
                Log.w(TAG, "boost merge unsupported (no RGBA16F targets); using the reference frame")
                return ref
            }
            val limits = renderer.limits()
            val cap = renderCap()
            val tA = android.os.SystemClock.elapsedRealtime()
            var tLuma = 0L
            val (alignW, shifts) = withContext(Dispatchers.Default) {
                var t = android.os.SystemClock.elapsedRealtime()
                val refL = alignLuma(ref.jpeg)
                tLuma += android.os.SystemClock.elapsedRealtime() - t
                refL.w to inputs.drop(1).map {
                    t = android.os.SystemClock.elapsedRealtime()
                    val l = alignLuma(it.jpeg)
                    tLuma += android.os.SystemClock.elapsedRealtime() - t
                    Align.estimate(refL, l)
                }
            }
            val tB = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "boost align ${tB - tA} ms (luma decode $tLuma ms)")
            val refBmp = withContext(Dispatchers.Default) { decode(ref.jpeg, cap, limits.maxTexture) }
            val scale = refBmp.width.toFloat() / alignW
            val outW = refBmp.width
            val outH = refBmp.height
            val frames = inputs.drop(1).asSequence().mapIndexed { i, f ->
                val s = shifts[i]
                MergeRenderer.Frame(decode(f.jpeg, cap, limits.maxTexture), Align.Shift(s.dx * scale, s.dy * scale))
            }
            val tC = android.os.SystemClock.elapsedRealtime()
            val (merged, meanWeight) = renderer.merge(refBmp, frames, plan.gainEv)
            Log.i(TAG, "boost gpu merge (incl. full decodes) ${android.os.SystemClock.elapsedRealtime() - tC} ms")
            val jpeg = withContext(Dispatchers.Default) {
                try {
                    java.io.ByteArrayOutputStream(8 shl 20).also { merged.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
                } finally {
                    merged.recycle()
                }
            }
            Log.i(TAG, "boost merge ${inputs.size} frames ${outW}x$outH shifts=${shifts.map { "%.1f,%.1f".format(it.dx * scale, it.dy * scale) }} " +
                "merged=%.0f in ${android.os.SystemClock.elapsedRealtime() - t0} ms".format(meanWeight))
            val sx = outW.toFloat() / ref.width
            val sy = outH.toFloat() / ref.height
            val c = ref.cropRect
            CaptureInput(
                jpeg, ref.rotationDegrees,
                IntRect((c.left * sx).toInt(), (c.top * sy).toInt(), (c.right * sx).toInt(), (c.bottom * sy).toInt()),
                outW, outH,
                exifJpeg = ref.jpeg.copyOf(minOf(ref.jpeg.size, EXIF_HEADER_BYTES)),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "boost merge failed; using the reference frame", t)
            runCatching { renderer.trim() }
            ref
        }
    }

    private fun alignLuma(jpeg: ByteArray): Luma {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ALIGN_LONG_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("JPEG decode failed")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val v = FloatArray(px.size) { i ->
            val p = px[i]
            (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
        }
        return Luma(bmp.width, bmp.height, v).also { bmp.recycle() }
    }

    suspend fun process(input: CaptureInput, settings: CaptureSettings): Result<Uri> {
        val exifSource = ExifWriter.readSource(input.meta)
        val exif = ExifWriter.plan(exifSource, settings.software, settings.time)
        val state = withGel(input, settings)
        return guarded { cap, maxTexture ->
            val out = develop(input, settings.preset, state, cap, maxTexture)
            save(out, settings.time, exif).also { PerfLog.shotSaved(settings.preset.id) }
        }.onSuccess { uri ->
            if (keepNegatives()) recordNegative(uri, settings, state, NegativeSettings.MODE_NORMAL, exifSource, uprightSource(input), null)
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
        val exifSource = ExifWriter.readSource(input.meta)
        val exif = ExifWriter.plan(exifSource, settings.software, settings.time)
        val state = withGel(input, settings)
        var neg: NegSource? = null
        var negBlend: ByteArray? = null
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
                    neg = if (keepNegatives()) compressNeg(whole) else null
                    renderComposed(whole, settings.preset, state, cap)
                } else {
                    // Double exposure has no neutral composite (the blend happens after colour): keep shot 2 and the developed first apart.
                    negBlend = if (keepNegatives()) compressNeg(f.bitmap, NEGATIVE_BLEND_QUALITY)?.bytes else null
                    develop(input, settings.preset, state, cap, maxTexture, blend = f.bitmap)
                }
                save(out, settings.time, exif)
            }
            saved = r.isSuccess
            r.onSuccess { uri ->
                if (keepNegatives()) {
                    if (mode == MultiMode.Half) recordNegative(uri, settings, state, NegativeSettings.MODE_HALF, exifSource, neg, null)
                    else recordNegative(uri, settings, state, NegativeSettings.MODE_DOUBLE, exifSource, uprightSource(input), negBlend)
                }
            }
            // An OOM that survived the retry loses the pair (SPEC 7.4): free the memory for the next attempt.
            if (r.exceptionOrNull()?.let { it is CaptureException && it.cause is OutOfMemoryError } == true) clearFirst()
            return r
        } finally {
            giveBack(f, consume = saved)
        }
    }

    private fun withGel(input: CaptureInput, settings: CaptureSettings): LookState =
        settings.state.copy(
            gel = FlashGel.effective(settings.state.gel, ExifWriter.flashFired(input.meta), settings.screenFlash),
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
            Result.success(
                retryOnOom(cap, RETRY_LONG_SIDE, onOom = {
                    Log.w(TAG, "OOM during capture at cap $cap; retrying at $RETRY_LONG_SIDE")
                    renderer.trim()
                    System.gc()
                }) { c -> body(c, if (c == cap) limits.maxTexture else RETRY_LONG_SIDE) },
            )
        } catch (oom: OutOfMemoryError) {
            Result.failure(CaptureException(CaptureErrorKind.DevelopFailed, oom))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(classify(e))
        }
    }

    private suspend fun save(out: Bitmap, time: LocalDateTime, exif: Map<String, String>): Uri {
        // The decoded input is recycled and the readback buffer dropped inside develop(); only
        // the output bitmap is alive while JPEG bytes stream into the MediaStore row.
        return withContext(Dispatchers.IO) {
            val t0 = PerfLog.nowNs()
            try {
                saver.save(
                    time,
                    writeJpeg = { os ->
                        if (!out.compress(Bitmap.CompressFormat.JPEG, StorageConfig.JPEG_QUALITY, os)) {
                            throw IOException("JPEG encode failed")
                        }
                    },
                    writeExif = { fd -> ExifWriter.write(fd, exif) },
                )
            } finally {
                out.recycle()
                PerfLog.shotEncode(t0)
            }
        }
    }

    private suspend fun decodeBitmap(input: CaptureInput, cap: Int, maxTexture: Int): Bitmap =
        try {
            val t0 = PerfLog.nowNs()
            withContext(Dispatchers.Default) { decode(input.jpeg, cap, maxTexture) }.also { PerfLog.shotDecode(t0) }
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

    // ---- negatives (SPEC 6.9) ----

    private fun compressNeg(b: Bitmap, quality: Int = StorageConfig.NEGATIVE_JPEG_QUALITY): NegSource? = try {
        val bos = ByteArrayOutputStream(maxOf(64 * 1024, b.byteCount / 12))
        if (b.compress(Bitmap.CompressFormat.JPEG, quality, bos)) NegSource(bos.toByteArray(), b.width, b.height) else null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "negative source not compressed (OOM)")
        null
    }

    /** Upright camera crop (before any frame crop), at most [NEGATIVE_LONG_SIDE], as JPEG q95; null on failure. */
    private suspend fun uprightSource(input: CaptureInput): NegSource? = try {
        val limits = renderer.limits()
        withContext(Dispatchers.Default) {
            val bitmap = decode(input.jpeg, NEGATIVE_LONG_SIDE, limits.maxTexture)
            val rot = ((input.rotationDegrees % 360) + 360) % 360
            val crop = scaledCrop(bitmap, input)
            val s = minOf(1f, NEGATIVE_LONG_SIDE.toFloat() / maxOf(crop.width, crop.height))
            val m = Matrix().apply { postRotate(rot.toFloat()); postScale(s, s) }
            val up = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height, m, true)
            if (up !== bitmap) bitmap.recycle()
            try { compressNeg(up) } finally { up.recycle() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "negative source failed", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "negative source failed (OOM)")
        null
    }

    /** Stores the negative of a photo that was just saved. A failure here never fails the save. */
    private suspend fun recordNegative(
        uri: Uri, settings: CaptureSettings, state: LookState, mode: String, exifSource: Map<String, String>,
        source: NegSource?, blend: ByteArray?,
    ) {
        val store = negatives ?: return
        if (source == null || !keepNegatives()) return
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return
        val draft = NegativeSettings(
            lookId = settings.preset.id, seed = state.seed, grainTime = state.grainTime, frame = state.frame.id,
            dateStamp = state.dateStampOn, stampTime = NegativeSettings.formatStamp(settings.time), prism = state.prism.name,
            gel = state.gel.name, captureMode = mode, sourceWidth = source.width, sourceHeight = source.height, exif = exifSource,
        )
        withContext(Dispatchers.IO) { store.write(id, draft, source.bytes, blend) }
    }

    // ---- import / re-develop (SPEC 6.7) ----

    /** What the editor needs to develop a stored source: [sourceJpeg] is upright, [blendJpeg] a developed first exposure. */
    class EditRequest(
        val sourceJpeg: ByteArray,
        val blendJpeg: ByteArray?,
        /** Look with the editor adjustments already applied ([com.vintagelab.camera.looks.EditAdjust]). */
        val preset: Preset,
        val state: LookState,
        /** Half-frame / grid sources keep their shape: no forced-ratio crop. */
        val composite: Boolean,
        val exifSource: Map<String, String>,
        val software: String,
        /** Negative to keep for the new photo (source/blend bytes are written as they are). */
        val negative: NegativeSettings,
    )

    /** Render cap for imports and edits: the same memory-budgeted cap as the camera path. */
    suspend fun renderCap(): Int = renderer.limits().let { minOf(it.maxLongSide, capForMemory(memoryClassMb)) }

    /** Renders [source] (kept alive) through the same graph at most [maxLongSide] long, for the editor preview. */
    suspend fun renderEditPreview(
        source: Bitmap, blend: Bitmap?, preset: Preset, state: LookState, composite: Boolean, maxLongSide: Int,
    ): Bitmap {
        val transform = uprightTransform(source, state.frame, composite)
        return try {
            renderer.render(source, transform, preset, state, maxLongSide, blend = blend, recycleInput = false)
        } catch (e: IllegalStateException) {
            throw CaptureException(CaptureErrorKind.DevelopFailed, e)
        }
    }

    private fun uprightTransform(bitmap: Bitmap, frame: com.vintagelab.camera.looks.FrameId, composite: Boolean): SourceTransform {
        var crop = IntRect(0, 0, bitmap.width, bitmap.height)
        if (!composite) crop = FrameGeometry.constrainCrop(crop, 0, frame)
        return SourceTransform(bitmap.width, bitmap.height, crop, 0, mirror = false)
    }

    /**
     * Full-resolution develop of an edit, saved as a NEW photo (the original is never touched), then its negative.
     * The source is decoded afresh for every attempt (the renderer consumes it, and the OOM retry needs a smaller one).
     */
    suspend fun processEdit(req: EditRequest): Result<Uri> {
        val stamp = NegativeSettings.parseStamp(req.negative.stampTime) ?: LocalDateTime.now()
        val exif = ExifWriter.plan(req.exifSource, req.software, stamp)
        return guarded { cap, maxTexture ->
            val bitmap = withContext(Dispatchers.Default) { decode(req.sourceJpeg, cap, maxTexture) }
            val blend = req.blendJpeg?.let { b -> withContext(Dispatchers.Default) { decode(b, cap, maxTexture) } }
            try {
                val transform = uprightTransform(bitmap, req.state.frame, req.composite)
                val out = try {
                    renderer.render(bitmap, transform, req.preset, req.state, cap, blend = blend)
                } catch (e: IllegalStateException) {
                    throw CaptureException(CaptureErrorKind.DevelopFailed, e)
                }
                // Sorted by save time, not by the photo's original date; the stamp and EXIF keep the original date.
                save(out, LocalDateTime.now(), exif)
            } finally {
                blend?.recycle()
            }
        }.onSuccess { uri ->
            val store = negatives
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
            if (store != null && id != null && keepNegatives()) {
                withContext(Dispatchers.IO) { store.write(id, req.negative, req.sourceJpeg, req.blendJpeg) }
            }
        }
    }

    companion object {
        private const val TAG = "CapturePipeline"
        const val RETRY_LONG_SIDE = 2048

        /** Long-side cap of a stored negative source (SPEC 6.9). */
        const val NEGATIVE_LONG_SIDE = 3072
        const val NEGATIVE_BLEND_QUALITY = 92
        const val MAX_LONG_SIDE = 4096

        /** Alignment works on a ~1000 px decode (sub-pixel there is ~0.5 px at 12 MP). */
        const val ALIGN_LONG_SIDE = 1000

        /** The reference frame's EXIF lives in its first segments; keep only this much of it. */
        const val EXIF_HEADER_BYTES = 192 * 1024

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
