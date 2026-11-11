package com.vintagelab.camera.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vintagelab.camera.App
import com.vintagelab.camera.R
import com.vintagelab.camera.capture.CaptureErrorKind
import com.vintagelab.camera.capture.CaptureException
import com.vintagelab.camera.capture.CapturePipeline
import com.vintagelab.camera.capture.DateStamp
import com.vintagelab.camera.capture.FlashGel
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.looks.EditAdjust
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.PresetRepository
import com.vintagelab.camera.looks.PrismMode
import com.vintagelab.camera.negatives.NegativeSettings
import com.vintagelab.camera.ui.CameraRules
import com.vintagelab.camera.ui.LookUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.random.Random

enum class EditorPhase { Idle, Loading, Ready, Saving, Failed }

enum class EditorError { OpenFailed, NegativeMissing, SaveFailed, StorageFull, DevelopFailed }

/** The editor's visible state (SPEC 6.7). [preview] is the latest render of the downsampled copy. */
@Immutable
data class EditorState(
    val phase: EditorPhase = EditorPhase.Idle,
    /** True when the screen was opened from a stored negative ("Re-develop"). */
    val redevelop: Boolean = false,
    val looks: List<LookUi> = emptyList(),
    val lookId: String = "classic35",
    val frame: FrameId = FrameId.NONE,
    val dateStamp: Boolean = false,
    val prism: PrismMode = PrismMode.OFF,
    val exposureEv: Float = 0f,
    val temperature: Float = 0f,
    val preview: Bitmap? = null,
    /** Half-frame / grid source: ratio-forcing frames are skipped. */
    val composite: Boolean = false,
    val error: EditorError? = null,
    /** Bumped for every error so the UI shows each once. */
    val errorSeq: Long = 0L,
    /** Bumped by "shuffle" so the renderer notices the new seed. */
    val shuffles: Int = 0,
)

/**
 * Editor for imported photos and stored negatives (SPEC 6.7). The preview is the SAME render graph as the camera and
 * the save (offscreen context, [CapturePipeline.renderEditPreview] / [CapturePipeline.processEdit]) on a downsampled copy.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App

    private class Session(
        val sourceJpeg: ByteArray,
        val blendJpeg: ByteArray?,
        val previewSource: Bitmap,
        val previewBlend: Bitmap?,
        val exif: Map<String, String>,
        val stampTime: LocalDateTime,
        val captureMode: String,
        val gel: FlashGel,
        val grainTime: Float,
        val sourceW: Int,
        val sourceH: Int,
        val repo: PresetRepository,
    )

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val saved = Channel<Uri>(Channel.BUFFERED)

    /** The URI of each photo saved by the editor, once. */
    val savedPhotos: Flow<Uri> = saved.receiveAsFlow()

    private var session: Session? = null
    private var seed: Long = 0L
    private var loadJob: Job? = null
    private var renderJob: Job? = null
    private var errorSeq = 0L

    /** Opens a Photo Picker result: decode (capped like a capture), strip metadata, new look seed. */
    fun openImport(uri: Uri) {
        begin(redevelop = false)
        loadJob = viewModelScope.launch {
            try {
                val repo = withContext(Dispatchers.IO) { app.presets }
                val cap = app.capturePipeline.renderCap()
                val img = withContext(Dispatchers.IO) { ImageImporter.import(app.contentResolver, uri, cap) }
                val look = repo.default
                seed = Random.nextLong()
                start(
                    repo, img.jpeg, null, img.exif, img.taken ?: LocalDateTime.now(), NegativeSettings.MODE_IMPORT, FlashGel.NONE,
                    Random.nextFloat() * 10f, img.width, img.height,
                    look.id, look.defaultFrame, look.dateStamp.enabledByDefault, PrismMode.OFF, 0f, 0f,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "import failed", e)
                fail(EditorError.OpenFailed)
            }
        }
    }

    /** Opens the stored negative of photo [mediaId] with its saved settings. */
    fun openNegative(mediaId: Long) {
        begin(redevelop = true)
        loadJob = viewModelScope.launch {
            try {
                val repo = withContext(Dispatchers.IO) { app.presets }
                val neg = withContext(Dispatchers.IO) { app.negatives.read(mediaId) }
                if (neg == null) {
                    fail(EditorError.NegativeMissing)
                    return@launch
                }
                val s = neg.settings
                seed = s.seed
                start(
                    repo, neg.source, neg.blend, s.exif, NegativeSettings.parseStamp(s.stampTime) ?: LocalDateTime.now(), s.captureMode,
                    FlashGel.entries.firstOrNull { it.name == s.gel } ?: FlashGel.NONE, s.grainTime, s.sourceWidth, s.sourceHeight,
                    repo.presets.firstOrNull { it.id == s.lookId }?.id ?: repo.default.id,
                    FrameId.fromId(s.frame) ?: FrameId.NONE, s.dateStamp,
                    PrismMode.entries.firstOrNull { it.name == s.prism } ?: PrismMode.OFF, s.exposureEv, s.temperature,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "negative failed", e)
                fail(EditorError.OpenFailed)
            }
        }
    }

    private fun begin(redevelop: Boolean) {
        close()
        _state.value = EditorState(phase = EditorPhase.Loading, redevelop = redevelop)
    }

    private suspend fun start(
        repo: PresetRepository, source: ByteArray, blend: ByteArray?, exif: Map<String, String>, stamp: LocalDateTime,
        mode: String, gel: FlashGel, grainTime: Float, w: Int, h: Int,
        lookId: String, frame: FrameId, stampOn: Boolean, prism: PrismMode, ev: Float, temp: Float,
    ) {
        val maxTexture = 4096
        val (preview, previewBlend) = withContext(Dispatchers.Default) {
            CapturePipeline.decode(source, PREVIEW_CAP, maxTexture) to blend?.let { CapturePipeline.decode(it, PREVIEW_CAP, maxTexture) }
        }
        val composite = mode == NegativeSettings.MODE_HALF || mode == NegativeSettings.MODE_GRID
        session = Session(source, blend, preview, previewBlend, exif, stamp, mode, gel, grainTime, w.takeIf { it > 0 } ?: preview.width, h.takeIf { it > 0 } ?: preview.height, repo)
        _state.update {
            it.copy(
                phase = EditorPhase.Ready,
                looks = repo.presets.map { p -> LookUi(p.id, p.displayNameRes, p.lightLeak.probability > 0f || p.dust > 0f) },
                lookId = lookId,
                frame = if (composite && FrameGeometry.forcedAspect(frame, true) != null) FrameId.NONE else frame,
                dateStamp = stampOn, prism = prism, exposureEv = ev, temperature = temp, composite = composite,
            )
        }
        startRendering()
    }

    private fun startRendering() {
        renderJob?.cancel()
        // collectLatest: a newer parameter set cancels the wait for an older render, so the latest always wins.
        renderJob = viewModelScope.launch {
            _state.collectLatest { st ->
                if (st.phase != EditorPhase.Ready && st.phase != EditorPhase.Saving) return@collectLatest
                val key = RenderKey(st.lookId, st.frame, st.dateStamp, st.prism, st.exposureEv, st.temperature, seed)
                if (key == lastKey) return@collectLatest
                try {
                    val bmp = renderPreview(st)
                    if (session != null) {
                        lastKey = key
                        _state.update { it.copy(preview = bmp) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "preview failed", e)
                    lastKey = key // do not retry the same failing parameters in a loop
                    fail(EditorError.DevelopFailed, keepOpen = true)
                }
            }
        }
    }

    private data class RenderKey(
        val look: String, val frame: FrameId, val stamp: Boolean, val prism: PrismMode, val ev: Float, val temp: Float, val seed: Long,
    )

    private var lastKey: RenderKey? = null

    private fun lookState(st: EditorState, s: Session) = LookState(
        seed = seed, frame = st.frame, dateStampOn = st.dateStamp, stampCodes = DateStamp.codes(s.stampTime).toList(),
        grainTime = s.grainTime, gel = s.gel, prism = st.prism,
    )

    private suspend fun renderPreview(st: EditorState): Bitmap {
        val s = session ?: throw IllegalStateException("no session")
        val preset = EditAdjust.apply(s.repo.get(st.lookId), st.exposureEv, st.temperature)
        return app.capturePipeline.renderEditPreview(s.previewSource, s.previewBlend, preset, lookState(st, s), st.composite, PREVIEW_RENDER_CAP)
    }

    // ---- intents ----

    fun selectLook(id: String) {
        val s = session ?: return
        val p = s.repo.get(id)
        _state.update {
            val f = if (it.composite && FrameGeometry.forcedAspect(p.defaultFrame, true) != null) FrameId.NONE else p.defaultFrame
            it.copy(lookId = p.id, frame = f, dateStamp = p.dateStamp.enabledByDefault)
        }
    }

    fun cycleFrame() = _state.update { it.copy(frame = CameraRules.nextFrame(it.frame, half = it.composite)) }
    fun toggleStamp() = _state.update { it.copy(dateStamp = !it.dateStamp) }
    fun cyclePrism() = _state.update { it.copy(prism = it.prism.next()) }
    fun setExposure(ev: Float) = _state.update { it.copy(exposureEv = quantize(ev.coerceIn(-EditAdjust.EV_RANGE, EditAdjust.EV_RANGE))) }
    fun setTemperature(t: Float) = _state.update { it.copy(temperature = quantize(t.coerceIn(-EditAdjust.TEMP_RANGE, EditAdjust.TEMP_RANGE))) }

    /** Slider values snap to 0.05 so the baked-LUT cache sees a bounded set of looks. */
    private fun quantize(v: Float): Float = Math.round(v * 20f) / 20f
    fun resetAdjustments() = _state.update { it.copy(exposureEv = 0f, temperature = 0f) }

    /** New light-leak / dust / grain layout. */
    fun shuffle() {
        seed = Random.nextLong()
        _state.update { it.copy(shuffles = it.shuffles + 1) }
    }

    /** Develops the full-resolution photo as a NEW MediaStore entry (the original is never touched). */
    fun save() {
        val st = _state.value
        val s = session ?: return
        if (st.phase != EditorPhase.Ready) return
        _state.update { it.copy(phase = EditorPhase.Saving) }
        val preset = EditAdjust.apply(s.repo.get(st.lookId), st.exposureEv, st.temperature)
        val look = lookState(st, s)
        val negative = NegativeSettings(
            lookId = st.lookId, seed = seed, grainTime = s.grainTime, frame = st.frame.id, dateStamp = st.dateStamp,
            stampTime = NegativeSettings.formatStamp(s.stampTime), prism = st.prism.name, gel = s.gel.name,
            exposureEv = st.exposureEv, temperature = st.temperature, captureMode = s.captureMode,
            sourceWidth = s.sourceW, sourceHeight = s.sourceH, exif = s.exif,
        )
        val req = CapturePipeline.EditRequest(
            s.sourceJpeg, s.blendJpeg, preset, look, st.composite, s.exif, app.getString(R.string.app_name), negative,
        )
        // appScope: the develop finishes even if the user leaves the screen; the result is delivered to whoever listens.
        app.appScope.launch {
            val r = app.capturePipeline.processEdit(req)
            withContext(Dispatchers.Main) {
                r.onSuccess { uri ->
                    _state.update { if (it.phase == EditorPhase.Saving) it.copy(phase = EditorPhase.Ready) else it }
                    saved.trySend(uri)
                }.onFailure { e ->
                    Log.e(TAG, "edit save failed", e)
                    _state.update { if (it.phase == EditorPhase.Saving) it.copy(phase = EditorPhase.Ready) else it }
                    fail(
                        when ((e as? CaptureException)?.kind) {
                            CaptureErrorKind.StorageFull -> EditorError.StorageFull
                            CaptureErrorKind.SaveFailed -> EditorError.SaveFailed
                            else -> EditorError.DevelopFailed
                        },
                        keepOpen = true,
                    )
                }
            }
        }
    }

    private fun fail(e: EditorError, keepOpen: Boolean = false) {
        _state.update {
            it.copy(
                phase = if (keepOpen) it.phase else EditorPhase.Failed,
                error = e, errorSeq = ++errorSeq,
            )
        }
    }

    /** Drops the session and its bitmaps (leaving the editor). */
    fun close() {
        loadJob?.cancel()
        renderJob?.cancel()
        // The small preview bitmaps are not recycled: a render may still be reading them on the GL thread. GC frees them.
        session = null
        lastKey = null
        _state.value = EditorState()
    }

    override fun onCleared() {
        close()
    }

    private companion object {
        const val TAG = "EditorViewModel"

        /** The downsampled editing copy: decoded to roughly this long side (power-of-two subsampling), rendered at most [PREVIEW_RENDER_CAP]. */
        const val PREVIEW_CAP = 720
        const val PREVIEW_RENDER_CAP = 1440
    }
}
