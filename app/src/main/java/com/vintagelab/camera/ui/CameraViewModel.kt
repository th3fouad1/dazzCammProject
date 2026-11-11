package com.vintagelab.camera.ui

import android.app.Application
import android.content.Context
import android.util.Log
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
import com.vintagelab.camera.camera.FlashMode
import com.vintagelab.camera.camera.Lens
import com.vintagelab.camera.capture.CaptureSettings
import com.vintagelab.camera.capture.DateStamp
import com.vintagelab.camera.gl.GlPreviewRenderer
import com.vintagelab.camera.gl.PreviewLook
import com.vintagelab.camera.looks.AspectRatio
import com.vintagelab.camera.looks.FrameId
import com.vintagelab.camera.looks.LookState
import com.vintagelab.camera.looks.PresetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/** Single owner of camera UI state (SPEC §6.2) and of the look seed (SPEC §5.2). */
class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val controller = CameraController(application)
    private var presets: PresetRepository? = null

    private val _state = MutableStateFlow(CameraUiState(lookSeed = Random.nextLong()))
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _previewLook = MutableStateFlow<PreviewLook?>(null)
    val previewLook: StateFlow<PreviewLook?> = _previewLook.asStateFlow()

    private var owner: LifecycleOwner? = null
    private var renderer: GlPreviewRenderer? = null
    private var displayRotation = 0
    private var boundKey: Any? = null
    private var countdownJob: Job? = null
    private var captureJob: Job? = null

    init {
        controller.onCameraError = { msg -> _state.update { it.copy(cameraError = msg) } }
        viewModelScope.launch {
            val repo = withContext(Dispatchers.IO) { app.presets }
            presets = repo
            val prefs = withContext(Dispatchers.IO) { app.settings.data.first() }
            val lookId = prefs[KEY_LOOK]?.takeIf { id -> repo.presets.any { it.id == id } } ?: repo.default.id
            val preset = repo.get(lookId)
            _state.update { s ->
                s.copy(
                    looks = repo.presets.map { LookItem(it.id, it.displayNameRes) },
                    lookId = lookId,
                    ratio = prefs[KEY_RATIO]?.let { r -> AspectRatio.entries.firstOrNull { it.name == r } } ?: s.ratio,
                    flash = prefs[KEY_FLASH]?.let { f -> FlashMode.entries.firstOrNull { it.name == f } } ?: s.flash,
                    timer = prefs[KEY_TIMER]?.let { t -> TimerMode.entries.firstOrNull { it.name == t } } ?: s.timer,
                    frame = prefs[KEY_FRAME]?.let { FrameId.fromId(it) } ?: preset.defaultFrame,
                    dateStamp = prefs[KEY_STAMP] ?: preset.dateStamp.enabledByDefault,
                )
            }
            refreshPreviewLook()
            rebindIfNeeded()
        }
    }

    fun onIntent(intent: CameraIntent) {
        when (intent) {
            is CameraIntent.SelectLook -> {
                val p = presets?.get(intent.id) ?: return
                _state.update { it.copy(lookId = p.id, frame = p.defaultFrame, dateStamp = p.dateStamp.enabledByDefault) }
                persist()
            }
            CameraIntent.ToggleLens -> {
                _state.update { it.copy(lens = if (it.lens == Lens.BACK) Lens.FRONT else Lens.BACK, zoom = 0f) }
                rebindIfNeeded()
            }
            CameraIntent.CycleFlash -> {
                val next = when (_state.value.flash) {
                    FlashMode.OFF -> FlashMode.ON
                    FlashMode.ON -> FlashMode.AUTO
                    FlashMode.AUTO -> FlashMode.OFF
                }
                _state.update { it.copy(flash = next) }
                controller.setFlash(next)
                persist()
            }
            is CameraIntent.SetRatio -> {
                _state.update { it.copy(ratio = intent.ratio) }
                persist()
                rebindIfNeeded()
            }
            is CameraIntent.SetTimer -> {
                _state.update { it.copy(timer = intent.timer) }
                persist()
            }
            CameraIntent.CancelTimer -> cancelCountdown()
            is CameraIntent.SetFrame -> {
                _state.update { it.copy(frame = intent.frame) }
                persist()
            }
            CameraIntent.ToggleDateStamp -> {
                _state.update { it.copy(dateStamp = !it.dateStamp) }
                persist()
            }
            CameraIntent.Shuffle -> _state.update { it.copy(lookSeed = Random.nextLong()) }
            CameraIntent.Shutter -> shutter()
            is CameraIntent.TapFocus -> {
                val t = renderer?.mapTap(intent.x, intent.y) ?: return
                controller.focus(t.x, t.y, t.bufWidth, t.bufHeight)
            }
            is CameraIntent.Zoom -> {
                val z = intent.linear.coerceIn(0f, 1f)
                _state.update { it.copy(zoom = z) }
                controller.setLinearZoom(z)
            }
        }
        refreshPreviewLook()
    }

    // ---- camera attachment (from CameraScreen) ----

    fun attach(owner: LifecycleOwner, renderer: GlPreviewRenderer) {
        this.owner = owner
        this.renderer = renderer
        renderer.onContextLost = { controller.resetSurfaceProvider() }
        boundKey = null
        refreshPreviewLook()
        rebindIfNeeded()
    }

    fun detach(renderer: GlPreviewRenderer) {
        if (this.renderer !== renderer) return
        renderer.onContextLost = null
        this.renderer = null
        this.owner = null
        boundKey = null
        controller.unbind()
    }

    fun setDisplayRotation(rotation: Int) {
        if (rotation == displayRotation) return
        displayRotation = rotation
        rebindIfNeeded()
    }

    fun onPermission(p: PermissionState) {
        _state.update { it.copy(permission = p) }
        rebindIfNeeded()
    }

    /** Camera error → retry on resume (SPEC §8). */
    fun onResume() {
        if (_state.value.cameraError != null) {
            _state.update { it.copy(cameraError = null) }
            boundKey = null
            rebindIfNeeded()
        }
    }

    private fun rebindIfNeeded() {
        val s = _state.value
        val o = owner ?: return
        val r = renderer ?: return
        if (s.permission != PermissionState.Granted || presets == null) return
        val key = listOf(s.lens, s.ratio, displayRotation, r)
        if (key == boundKey) return
        boundKey = key
        viewModelScope.launch {
            try {
                val caps = controller.bind(o, s.lens, s.ratio, s.flash, displayRotation, r)
                controller.setLinearZoom(_state.value.zoom)
                _state.update { it.copy(hasFlash = caps.hasFlash, hasFront = caps.hasFront && caps.hasBack, cameraError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                boundKey = null
                _state.update { it.copy(cameraError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    // ---- shutter / capture ----

    private fun shutter() {
        val s = _state.value
        when (s.captureState) {
            is CaptureState.Countdown -> { cancelCountdown(); return }
            CaptureState.Processing -> return // single in-flight capture
            else -> Unit
        }
        if (captureJob?.isActive == true) return
        if (s.timer == TimerMode.OFF) {
            capture()
            return
        }
        countdownJob = viewModelScope.launch {
            for (n in s.timer.seconds downTo 1) {
                _state.update { it.copy(captureState = CaptureState.Countdown(n)) }
                delay(1000)
            }
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
        val s = _state.value
        val now = LocalDateTime.now()
        // Freeze seed + grain time + stamp at shutter (after the timer).
        val lookState = LookState(
            seed = s.lookSeed,
            frame = s.frame,
            dateStampOn = s.dateStamp,
            stampCodes = DateStamp.codes(now).toList(),
            grainTime = renderer?.lastGrainTime ?: 0f,
        )
        val settings = CaptureSettings(repo.get(s.lookId), lookState, now, app.getString(R.string.app_name))
        _state.update { it.copy(captureState = CaptureState.Processing) }
        captureJob = app.appScope.launch {
            val result = try {
                val input = withContext(Dispatchers.Main) { controller.takePicture() }
                app.capturePipeline.process(input, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onSuccess { uri ->
                _state.update { it.copy(captureState = CaptureState.Saved(uri), lastShot = uri, lookSeed = Random.nextLong()) }
            }.onFailure { e ->
                Log.e(TAG, "capture failed", e)
                _state.update { it.copy(captureState = CaptureState.Error(e.message ?: e.javaClass.simpleName)) }
            }
            withContext(Dispatchers.Main) { refreshPreviewLook() }
            delay(STATUS_MS)
            _state.update {
                if (it.captureState is CaptureState.Saved || it.captureState is CaptureState.Error) it.copy(captureState = CaptureState.Idle) else it
            }
        }
    }

    private fun refreshPreviewLook() {
        val repo = presets ?: return
        val s = _state.value
        val look = PreviewLook(
            repo.get(s.lookId),
            LookState(s.lookSeed, s.frame, s.dateStamp, DateStamp.codes(LocalDate.now().atStartOfDay()).toList()),
        )
        _previewLook.value = look
        renderer?.look = look
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
            }
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        controller.unbind()
    }

    private companion object {
        const val TAG = "CameraViewModel"
        const val STATUS_MS = 1500L
        val KEY_LOOK = stringPreferencesKey("lookId")
        val KEY_RATIO = stringPreferencesKey("ratio")
        val KEY_FLASH = stringPreferencesKey("flash")
        val KEY_TIMER = stringPreferencesKey("timer")
        val KEY_FRAME = stringPreferencesKey("frame")
        val KEY_STAMP = booleanPreferencesKey("dateStamp")
    }
}
