package com.vintagelab.camera.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.vintagelab.camera.App
import com.vintagelab.camera.R
import com.vintagelab.camera.camera.CameraController
import com.vintagelab.camera.camera.CameraEvent
import com.vintagelab.camera.camera.FlashMode
import com.vintagelab.camera.camera.Lens
import com.vintagelab.camera.capture.CaptureErrorKind
import com.vintagelab.camera.capture.CaptureException
import com.vintagelab.camera.capture.CaptureInput
import com.vintagelab.camera.capture.CaptureSettings
import com.vintagelab.camera.capture.DateStamp
import com.vintagelab.camera.capture.FirstExposureMissing
import com.vintagelab.camera.capture.FlashGel
import com.vintagelab.camera.capture.MultiExposure
import com.vintagelab.camera.capture.MultiMode
import com.vintagelab.camera.capture.MultiShotRules
import com.vintagelab.camera.capture.MultiShotStep
import com.vintagelab.camera.capture.ShotKind
import com.vintagelab.camera.gl.GlPreviewRenderer
import com.vintagelab.camera.gl.PreviewEvent
import com.vintagelab.camera.gl.PreviewHost
import com.vintagelab.camera.gl.PreviewLook
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.PrismMode
import com.vintagelab.camera.looks.PresetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

private val Context.settings: DataStore<Preferences> by preferencesDataStore(name = "camera_settings")

/**
 * Single owner of camera UI state (SPEC §6.2) and of the look seed (SPEC §5.2).
 *
 * Capture flow (SPEC §5.3 as amended, K17): at the shutter an immutable [CaptureSettings]
 * snapshot is taken and the seed is re-rolled. The next shot is accepted as soon as the previous
 * ImageProxy is closed; at most one render job runs and at most one decoded job waits. When the
 * queue is full the shutter shows "developing".
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val controller = CameraController(application)
    private var presets: PresetRepository? = null
    val previewHost: PreviewHost = app.previewHost

    private val _state = MutableStateFlow(CameraUiState(lookSeed = Random.nextLong()))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private var owner: LifecycleOwner? = null
    private var renderer: GlPreviewRenderer? = null
    private var displayRotation = 0
    private var boundKey: Any? = null
    private var countdownJob: Job? = null

    private class ShotJob(
        val input: CaptureInput, val settings: CaptureSettings, val kind: ShotKind, val mode: MultiMode, val bgEpoch: Int,
        val burstId: Long = 0L, val index: Int = 0,
    )

    private val jobs = Channel<ShotJob>(capacity = 1)
    /** Bumped when the app goes to the background: a first exposure that finishes later is dropped (SPEC 7.4). */
    @Volatile private var bgEpoch = 0
    private var ghost: Bitmap? = null
    private var taking = false
    private var gridSeq = 0L

    /** Burst id that failed or was dropped; the burst loop and its late cells stop on it. */
    private var gridAbortedId = 0L
    private var outstanding = 0
    private var errorSeq = 0L
    private var focusSeq = 0L

    init {
        controller.onEvent = ::onCameraEvent
        previewHost.onRendererReady = { r ->
            renderer = r
            r.ghost = ghost
            boundKey = null
            refreshPreviewLook()
            rebindIfNeeded()
        }
        previewHost.onContextLost = { controller.resetSurfaceProvider() }
        viewModelScope.launch {
            previewHost.events.collect { e ->
                when (e) {
                    PreviewEvent.GpuUnsupported -> _state.update { it.copy(camera = CameraStatus.Error(CameraErrorKind.GpuUnsupported)) }
                    PreviewEvent.Passthrough -> _state.update { it.copy(renderMode = RenderMode.Passthrough) }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            val repo = withContext(Dispatchers.IO) { app.presets }
            presets = repo
            val prefs = withContext(Dispatchers.IO) { app.settings.data.first() }
            val lastShot = withContext(Dispatchers.IO) { app.saver.newestOwn() }
            val lookId = prefs[KEY_LOOK]?.takeIf { id -> repo.presets.any { it.id == id } } ?: repo.default.id
            val preset = repo.get(lookId)
            _state.update { s ->
                s.copy(
                    looks = repo.presets.map { LookUi(it.id, it.displayNameRes, it.lightLeak.probability > 0f || it.dust > 0f) },
                    lookId = lookId,
                    ratio = prefs[KEY_RATIO]?.let { r -> AspectRatio.entries.firstOrNull { it.name == r } } ?: s.ratio,
                    flash = prefs[KEY_FLASH]?.let { f -> FlashMode.entries.firstOrNull { it.name == f } } ?: s.flash,
                    timer = prefs[KEY_TIMER]?.let { t -> TimerMode.entries.firstOrNull { it.name == t } } ?: s.timer,
                    frame = prefs[KEY_FRAME]?.let { FrameId.fromId(it) } ?: preset.defaultFrame,
                    dateStamp = prefs[KEY_STAMP] ?: preset.dateStamp.enabledByDefault,
                    flashGel = prefs[KEY_GEL]?.let { g -> FlashGel.entries.firstOrNull { it.name == g } } ?: s.flashGel,
                    prism = prefs[KEY_PRISM]?.let { g -> PrismMode.entries.firstOrNull { it.name == g } } ?: s.prism,
                    lastShot = s.lastShot ?: lastShot,
                )
            }
            refreshPreviewLook()
            rebindIfNeeded()
        }
        app.appScope.launch { for (job in jobs) develop(job) }
    }

    fun onIntent(intent: CameraIntent) {
        when (intent) {
            is CameraIntent.SelectLook -> {
                val p = presets?.get(intent.id) ?: return
                _state.update { it.copy(lookId = p.id, frame = MultiShotRules.enforceFrame(it.multi, it.frame, p.defaultFrame), dateStamp = p.dateStamp.enabledByDefault) }
                persist()
            }
            CameraIntent.ToggleLens -> {
                if (!CameraRules.lensRatioEnabled(_state.value)) return
                _state.update { it.copy(lens = if (it.lens == Lens.BACK) Lens.FRONT else Lens.BACK, zoom = 0f) }
                rebindIfNeeded()
            }
            CameraIntent.CycleFlash -> {
                val next = CameraRules.nextFlash(_state.value.flash, _state.value.lens)
                _state.update { it.copy(flash = next) }
                controller.setFlash(next)
                persist()
            }
            is CameraIntent.SetRatio -> {
                if (!CameraRules.ratioEnabled(_state.value)) return
                _state.update { it.copy(ratio = intent.ratio) }
                persist()
                rebindIfNeeded()
            }
            is CameraIntent.SetTimer -> {
                if (!CameraRules.timerEnabled(_state.value)) return
                _state.update { it.copy(timer = intent.timer) }
                persist()
            }
            CameraIntent.CancelTimer -> cancelCountdown()
            is CameraIntent.SetFrame -> {
                if (!CameraRules.frameEnabled(_state.value)) return
                _state.update { it.copy(frame = MultiShotRules.enforceFrame(it.multi, it.frame, intent.frame)) }
                persist()
            }
            CameraIntent.ToggleDateStamp -> {
                _state.update { it.copy(dateStamp = !it.dateStamp) }
                persist()
            }
            CameraIntent.ToggleDoubleExposure -> toggleMulti(MultiMode.Double)
            CameraIntent.ToggleHalfFrame -> toggleMulti(MultiMode.Half)
            CameraIntent.ToggleGrid -> toggleMulti(MultiMode.Grid4)
            CameraIntent.CyclePrism -> {
                _state.update { it.copy(prism = it.prism.next()) }
                persist()
                refreshPreviewLook()
            }
            CameraIntent.CancelFirstExposure -> {
                if (!MultiShotRules.canChangeMode(_state.value.multi)) return
                applyMulti(MultiShotRules.cancel(_state.value.multi))
            }
            CameraIntent.CycleFlashGel -> {
                _state.update { it.copy(flashGel = it.flashGel.next()) }
                persist()
            }
            CameraIntent.Shuffle -> _state.update { it.copy(lookSeed = Random.nextLong()) }
            CameraIntent.Shutter -> shutter()
            is CameraIntent.TapFocus -> focus(intent.x, intent.y)
            is CameraIntent.Zoom -> {
                val z = intent.linear.coerceIn(0f, 1f)
                _state.update { it.copy(zoom = z) }
                controller.setLinearZoom(z)
            }
            is CameraIntent.PermissionResult -> {
                _state.update { it.copy(permission = CameraRules.onPermissionResult(intent.granted, intent.canAskAgain)) }
                rebindIfNeeded()
            }
            is CameraIntent.RefreshPermission -> {
                _state.update { it.copy(permission = CameraRules.onRefreshPermission(it.permission, intent.granted)) }
                rebindIfNeeded()
            }
            CameraIntent.RetryCamera -> {
                if ((_state.value.camera as? CameraStatus.Error)?.kind == CameraErrorKind.GpuUnsupported) return
                _state.update { it.copy(camera = CameraStatus.Opening) }
                boundKey = null
                rebindIfNeeded()
            }
            CameraIntent.LastShotUnavailable -> _state.update { it.copy(lastShot = null) }
        }
        refreshPreviewLook()
    }

    // ---- camera attachment (from CameraScreen) ----

    fun attach(owner: LifecycleOwner) {
        this.owner = owner
        verifyFirst()
        boundKey = null
        rebindIfNeeded()
    }

    fun detach(owner: LifecycleOwner) {
        if (this.owner !== owner) return
        this.owner = null
        renderer = null
        boundKey = null
        controller.unbind()
        _state.update { it.copy(camera = CameraStatus.Opening) }
    }

    /** K14: display rotation (Surface.ROTATION_*) and whether the natural orientation is portrait. */
    fun setDisplay(rotation: Int, naturalPortrait: Boolean) {
        val imagePortrait = (rotation == 0 || rotation == 2) == naturalPortrait
        _state.update { it.copy(imagePortrait = imagePortrait) }
        // 180° flips keep the ViewPort aspect: the controller's DisplayListener handles them.
        val needsRebind = (rotation % 2) != (displayRotation % 2)
        displayRotation = rotation
        if (needsRebind) rebindIfNeeded()
    }

    /** K12 */
    fun setScreenFlash(flash: ImageCapture.ScreenFlash?) = controller.setScreenFlash(flash)

    /** K15: hardware shutter key released (shutter, or cancel during a countdown). */
    fun onHardwareShutter() {
        if (!acceptsHardwareShutter()) return
        onIntent(CameraIntent.Shutter)
    }

    /** Keys keep their system role unless the camera is shown, granted and Ready. */
    fun acceptsHardwareShutter(): Boolean =
        _state.value.permission == PermissionState.Granted && owner != null && _state.value.camera == CameraStatus.Ready

    private fun onCameraEvent(e: CameraEvent) {
        when (e) {
            CameraEvent.Opening -> _state.update { if (it.camera is CameraStatus.Error) it else it.copy(camera = CameraStatus.Opening) }
            CameraEvent.Ready -> _state.update { it.copy(camera = CameraStatus.Ready) }
            is CameraEvent.Failed -> _state.update {
                val kind = when {
                    e.disabled -> CameraErrorKind.Disabled
                    e.inUse -> CameraErrorKind.InUse
                    else -> CameraErrorKind.Fatal
                }
                it.copy(camera = CameraStatus.Error(kind))
            }
            is CameraEvent.ZoomChanged -> _state.update { it.copy(zoom = e.linear, zoomRatio = e.ratio, zoomRange = e.min..e.max) }
        }
    }

    private fun rebindIfNeeded() {
        val s = _state.value
        val o = owner ?: return
        val r = renderer ?: return
        if (s.permission != PermissionState.Granted || presets == null) return
        if ((s.camera as? CameraStatus.Error)?.kind == CameraErrorKind.GpuUnsupported) return
        val key = listOf(s.lens, s.ratio, displayRotation % 2, r)
        if (key == boundKey) return
        boundKey = key
        viewModelScope.launch {
            try {
                val caps = controller.bind(o, s.lens, s.ratio, s.flash, displayRotation, r)
                controller.setLinearZoom(_state.value.zoom)
                _state.update {
                    it.copy(
                        lens = caps.lens,
                        caps = CameraCaps(caps.hasBack, caps.hasFront, caps.hasFlash, caps.screenFlash),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                boundKey = null
                _state.update { it.copy(camera = CameraStatus.Error(CameraErrorKind.Fatal)) }
            }
        }
    }

    private fun focus(nx: Float, ny: Float) {
        val t = renderer?.mapNormalized(nx, ny) ?: return
        val seq = ++focusSeq
        _state.update { it.copy(focus = FocusUi(nx, ny, FocusPhase.Focusing, seq)) }
        controller.focus(t.x, t.y, t.bufWidth, t.bufHeight) { ok ->
            _state.update { s ->
                if (s.focus?.seq == seq) s.copy(focus = s.focus.copy(phase = if (ok) FocusPhase.Locked else FocusPhase.Failed)) else s
            }
        }
    }

    // ---- shutter / capture ----

    private fun canAccept() = !taking && outstanding < MultiShotRules.maxOutstanding(_state.value.multi) &&
        _state.value.multiExposure != MultiExposure.Burst

    private fun shutter() {
        val s = _state.value
        if (s.captureState is CaptureState.Countdown) {
            cancelCountdown()
            return
        }
        if (!CameraRules.shutterEnabled(s) || !canAccept()) return
        if (s.timer == TimerMode.OFF) {
            capture()
            return
        }
        countdownJob = viewModelScope.launch {
            for (n in s.timer.seconds downTo 1) {
                _state.update { it.copy(captureState = CaptureState.Countdown(n)) }
                delay(1000)
            }
            _state.update { it.copy(captureState = CaptureState.Idle) }
            capture()
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        if (_state.value.captureState is CaptureState.Countdown) {
            _state.update { it.copy(captureState = CaptureState.Idle) }
        }
    }

    private fun capture() {
        val repo = presets ?: return
        if (!canAccept()) return
        val s = _state.value
        val now = LocalDateTime.now()
        // K17a: immutable snapshot at the shutter (after the timer); later UI changes can't touch it.
        val settings = CaptureSettings(
            preset = repo.get(s.lookId),
            state = LookState(
                seed = s.lookSeed,
                frame = s.frame,
                dateStampOn = s.dateStamp,
                stampCodes = DateStamp.codes(now).toList(),
                grainTime = renderer?.lastGrainTime ?: 0f,
                gel = s.flashGel,
                prism = MultiShotRules.effectivePrism(s.multiMode, s.prism),
            ),
            time = now,
            software = app.getString(R.string.app_name),
            screenFlash = s.lens == Lens.FRONT && s.flash != FlashMode.OFF && s.caps.screenFlash,
        )
        val kind = MultiShotRules.nextShot(s.multi)
        val mode = s.multiMode
        val epoch = bgEpoch
        if (kind == ShotKind.Grid) {
            startGridBurst(settings, epoch)
            return
        }
        if (kind == ShotKind.Second) {
            // Reserve the pair atomically at the shutter: discard/toggles are rejected from now on.
            val m = MultiShotRules.beginSecond(s.multi)
            _state.update { it.copy(multiExposure = m.phase) }
        }
        taking = true
        _state.update { it.copy(lookSeed = Random.nextLong()) }
        publishCaptureState()
        refreshPreviewLook()
        viewModelScope.launch {
            val input = try {
                controller.takePicture()
            } catch (e: CancellationException) {
                taking = false
                abortSecond(kind)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "takePicture failed", e)
                taking = false
                abortSecond(kind)
                fail(CaptureErrorKind.DevelopFailed)
                return@launch
            }
            taking = false
            outstanding++
            publishCaptureState()
            jobs.send(ShotJob(input, settings, kind, mode, epoch))
        }
    }

    private suspend fun develop(job: ShotJob) {
        val pipeline = app.capturePipeline
        // Per-job failure boundary: a throwing job must never kill the queue consumer.
        val result: Result<Any?> = try {
            when (job.kind) {
                ShotKind.Normal -> pipeline.process(job.input, job.settings)
                ShotKind.First -> pipeline.processFirst(job.input, job.settings, job.mode)
                ShotKind.Second -> pipeline.processSecond(job.input, job.settings, job.mode)
                ShotKind.Grid -> pipeline.processGridCell(job.input, job.settings, job.burstId, job.index)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
        withContext(Dispatchers.Main) {
            outstanding--
            val m = _state.value.multi
            when (job.kind) {
                ShotKind.Normal -> result.onSuccess { saved(it as Uri) }.onFailure { failed(it) }
                ShotKind.First -> result.onSuccess { g ->
                    if (job.bgEpoch != bgEpoch) {
                        // Backgrounded while developing: free everything now (SPEC 7.4).
                        pipeline.clearFirst()
                        applyMulti(MultiShotRules.onFirstFailed(m), notify = true)
                    } else {
                        ghost = g as Bitmap?
                        renderer?.ghost = ghost
                        applyMulti(MultiShotRules.onFirstStored(m, job.mode))
                    }
                }.onFailure {
                    applyMulti(MultiShotRules.onFirstFailed(m))
                    failed(it)
                }
                ShotKind.Grid -> result.onSuccess { uri ->
                    if (job.index == MultiShotRules.GRID_SHOTS - 1 && uri is Uri) {
                        endGrid(job.burstId)
                        saved(uri)
                    }
                }.onFailure {
                    // Only the first failure of a burst is reported; later cells of it fail silently.
                    if (abortGrid(job.burstId)) failed(it)
                }
                ShotKind.Second -> result.onSuccess {
                    applyMulti(MultiShotRules.onSecondSaved(m))
                    saved(it as Uri)
                }.onFailure {
                    val dropped = !pipeline.hasFirst(job.mode)
                    applyMulti(MultiShotRules.onSecondFailed(m, dropped), notify = true)
                    // A vanished first exposure is reported by the drop notice, not as a develop error.
                    if (it !is FirstExposureMissing) failed(it)
                }
            }
            publishCaptureState()
        }
    }

    private fun saved(uri: Uri) {
        _state.update { it.copy(lastShot = uri, captureState = CaptureState.Saved(uri)) }
    }

    private fun failed(e: Throwable) {
        Log.e(TAG, "capture failed", e)
        fail((e as? CaptureException)?.kind ?: CaptureErrorKind.DevelopFailed)
    }

    // ---- multi-shot modes (SPEC 7.4) ----

    /**
     * Grid burst: [MultiShotRules.GRID_SHOTS] pictures, each accepted as soon as the previous ImageProxy is closed
     * (takePicture returns after that), [MultiShotRules.GRID_INTERVAL_MS] apart. Cells are developed by the normal job
     * queue. No cancel once started; any failure aborts the whole grid.
     */
    private fun startGridBurst(settings: CaptureSettings, epoch: Int) {
        val burst = app.capturePipeline.beginGrid().also { gridSeq = it }
        val m = MultiShotRules.beginBurst(_state.value.multi)
        _state.update { it.copy(multiExposure = m.phase, gridShots = m.gridShots, lookSeed = Random.nextLong()) }
        publishCaptureState()
        refreshPreviewLook()
        viewModelScope.launch {
            for (i in 0 until MultiShotRules.GRID_SHOTS) {
                if (gridAbortedId == burst) return@launch
                taking = true
                val input = try {
                    controller.takePicture()
                } catch (e: CancellationException) {
                    taking = false
                    abortGrid(burst)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "grid takePicture failed", e)
                    taking = false
                    if (abortGrid(burst)) fail(CaptureErrorKind.DevelopFailed)
                    return@launch
                }
                taking = false
                // Backgrounded/aborted/superseded while the picture was being taken: drop it (no storage is created).
                if (gridAbortedId == burst || !app.capturePipeline.isGridLive(burst)) {
                    abortGrid(burst)
                    return@launch
                }
                outstanding++
                val next = MultiShotRules.burstShotTaken(_state.value.multi)
                _state.update { it.copy(gridShots = next.gridShots) }
                publishCaptureState()
                try {
                    jobs.send(ShotJob(input, settings, ShotKind.Grid, MultiMode.Grid4, epoch, burst, i))
                } catch (e: CancellationException) {
                    outstanding--
                    abortGrid(burst)
                    throw e
                } catch (e: Exception) {
                    outstanding--
                    if (abortGrid(burst)) fail(CaptureErrorKind.DevelopFailed)
                    return@launch
                }
                if (i < MultiShotRules.GRID_SHOTS - 1) delay(MultiShotRules.GRID_INTERVAL_MS)
            }
        }
    }

    /** Grid finished (saved or aborted): back to armed. */
    private fun endGrid(burst: Long) {
        if (burst != gridSeq) return
        val m = MultiShotRules.endBurst(_state.value.multi)
        _state.update { it.copy(multiExposure = m.phase, gridShots = m.gridShots) }
    }

    /** Fails the burst once; returns true for the call that aborted it (the one that reports the error). */
    private fun abortGrid(burst: Long): Boolean {
        if (burst != gridSeq || gridAbortedId == burst) return false
        gridAbortedId = burst
        app.capturePipeline.abortGrid(burst)
        endGrid(burst)
        publishCaptureState()
        return true
    }

    private fun abortSecond(kind: ShotKind) {
        if (kind == ShotKind.Second) {
            val m = MultiShotRules.abortSecond(_state.value.multi)
            _state.update { it.copy(multiExposure = m.phase) }
        }
    }

    private fun toggleMulti(mode: MultiMode) {
        if (!MultiShotRules.canChangeMode(_state.value.multi)) return
        val prevMode = _state.value.multi.mode
        val step = MultiShotRules.toggle(_state.value.multi, mode)
        applyMulti(step)
        refreshPreviewLook()
        if (step.state.mode == MultiMode.Grid4) {
            // The composite has its own shape: ratio-forcing frames are skipped while armed.
            _state.update { it.copy(frame = MultiShotRules.enforceFrame(it.multi, it.frame, it.frame)) }
            persist()
        }
        if (step.state.mode == MultiMode.Half) {
            // Each half is cropped to 0.72 from a 4:3 frame; ratio and ratio-forcing frames are not used.
            _state.update {
                it.copy(
                    ratio = AspectRatio.R4_3,
                    frame = MultiShotRules.enforceFrame(it.multi, it.frame, it.frame),
                )
            }
            persist()
            rebindIfNeeded()
        }
        if (step.state.mode == MultiMode.None && (prevMode == MultiMode.Half || prevMode == MultiMode.Grid4)) {
            // Leaving Half/Grid: give back a ratio-forcing preset frame that arming replaced with NONE.
            val def = presets?.get(_state.value.lookId)?.defaultFrame
            if (def != null && _state.value.frame == FrameId.NONE && MultiShotRules.enforceFrame(_state.value.multi, FrameId.NONE, def) == def) {
                _state.update { it.copy(frame = def) }
                persist()
                refreshPreviewLook()
                rebindIfNeeded()
            }
        }
    }

    /** Applies a reducer step; anything that leaves no first exposure frees the stored one and the ghost. */
    private fun applyMulti(step: MultiShotStep, notify: Boolean = false) {
        _state.update {
            it.copy(
                multiMode = step.state.mode,
                multiExposure = step.state.phase,
                gridShots = step.state.gridShots,
                multiDropSeq = if (notify && step.discardedFirst) it.multiDropSeq + 1 else it.multiDropSeq,
            )
        }
        if (!MultiShotRules.holdsFirst(step.state)) {
            app.capturePipeline.clearFirst()
            ghost = null
            renderer?.ghost = null
        }
    }

    /** MainActivity.onStop: drop the first exposure and the ghost so nothing stays in memory in the background. */
    fun onBackground() {
        bgEpoch++
        app.capturePipeline.clearFirst()
        ghost = null
        renderer?.ghost = null
        val m = _state.value.multi
        if (m.phase == MultiExposure.HasFirst) applyMulti(MultiShotRules.onDropped(m), notify = true)
        if (m.phase == MultiExposure.Burst) abortGrid(gridSeq)
        app.capturePipeline.abortAllGrids()
    }

    /** Trim or OOM may have emptied the store behind our back. */
    private fun verifyFirst() {
        val m = _state.value.multi
        if (m.phase == MultiExposure.HasFirst && !app.capturePipeline.hasFirst(m.mode)) {
            applyMulti(MultiShotRules.onDropped(m), notify = true)
        }
    }

    private fun fail(kind: CaptureErrorKind) {
        _state.update { it.copy(captureState = CaptureState.Error(kind, ++errorSeq)) }
        publishCaptureState()
    }

    /** Processing ⇔ the shutter cannot accept a shot; otherwise keep Saved/Error/Idle. */
    private fun publishCaptureState() {
        _state.update { s ->
            val cs = when {
                s.captureState is CaptureState.Countdown -> s.captureState
                !canAccept() -> CaptureState.Processing
                s.captureState == CaptureState.Processing -> CaptureState.Idle
                else -> s.captureState
            }
            s.copy(captureState = cs, developing = outstanding)
        }
    }

    private fun refreshPreviewLook() {
        val repo = presets ?: return
        val s = _state.value
        renderer?.look = PreviewLook(
            repo.get(s.lookId),
            LookState(s.lookSeed, s.frame, s.dateStamp, DateStamp.codes(LocalDate.now().atStartOfDay()).toList(), prism = MultiShotRules.effectivePrism(s.multiMode, s.prism)),
        )
    }

    private fun persist() {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            app.settings.edit { p ->
                p[KEY_LOOK] = s.lookId
                p[KEY_RATIO] = s.ratio.name
                p[KEY_FLASH] = s.flash.name
                p[KEY_TIMER] = s.timer.name
                p[KEY_FRAME] = s.frame.id
                p[KEY_STAMP] = s.dateStamp
                p[KEY_GEL] = s.flashGel.name
                p[KEY_PRISM] = s.prism.name
            }
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        jobs.close() // queued jobs still finish in appScope
        previewHost.onRendererReady = null
        previewHost.onContextLost = null
        controller.release()
    }

    private companion object {
        const val TAG = "CameraViewModel"
        val KEY_LOOK = stringPreferencesKey("lookId")
        val KEY_RATIO = stringPreferencesKey("ratio")
        val KEY_FLASH = stringPreferencesKey("flash")
        val KEY_TIMER = stringPreferencesKey("timer")
        val KEY_FRAME = stringPreferencesKey("frame")
        val KEY_STAMP = booleanPreferencesKey("dateStamp")
        val KEY_GEL = stringPreferencesKey("flashGel")
        val KEY_PRISM = stringPreferencesKey("prism")
    }
}
