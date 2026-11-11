package com.vintagelab.camera.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vintagelab.camera.R
import com.vintagelab.camera.capture.CaptureErrorKind
import com.vintagelab.camera.capture.MultiExposure
import com.vintagelab.camera.capture.MultiMode
import com.vintagelab.camera.gl.PreviewHost
import com.vintagelab.camera.ui.theme.VlColors
import kotlinx.coroutines.launch

/** Stateless camera screen (DESIGN §10.1). */
@Composable
fun CameraScreen(
    state: CameraUiState,
    onIntent: (CameraIntent) -> Unit,
    previewHost: PreviewHost,
    onAttach: () -> Unit,
    onDetach: () -> Unit,
    onScreenFlash: (androidx.camera.core.ImageCapture.ScreenFlash?) -> Unit,
    nav: CameraNav,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    var announcement by remember { mutableStateOf("") }
    var toggle by remember { mutableStateOf(false) }
    fun announce(msg: String) { toggle = !toggle; announcement = msg + if (toggle) "​" else "" }

    DisposableEffect(lifecycleOwner) {
        view.keepScreenOn = true
        onAttach()
        onDispose { view.keepScreenOn = false; onDetach() }
    }
    if (state.captureState is CaptureState.Countdown) BackHandler { onIntent(CameraIntent.CancelTimer) }

    // Errors → snackbar once per seq (K4); render passthrough once (K7).
    val err = state.captureState as? CaptureState.Error
    LaunchedEffect(err?.seq) {
        err ?: return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        snackbar.showSnackbar(
            res.getString(
                when (err.kind) {
                    CaptureErrorKind.SaveFailed -> R.string.err_save_failed
                    CaptureErrorKind.StorageFull -> R.string.err_storage_full
                    CaptureErrorKind.DevelopFailed -> R.string.err_develop_failed
                },
            ),
        )
    }
    LaunchedEffect(state.multiDropSeq) {
        if (state.multiDropSeq > 0L) {
            announce(res.getString(R.string.msg_first_dropped))
            snackbar.showSnackbar(res.getString(R.string.msg_first_dropped))
        }
    }
    LaunchedEffect(state.multiMode) {
        when (state.multiMode) {
            MultiMode.Double -> announce(res.getString(R.string.a11y_double_on))
            MultiMode.Half -> announce(res.getString(R.string.a11y_half_on))
            MultiMode.Grid4 -> announce(res.getString(R.string.a11y_grid_on))
            MultiMode.None -> Unit
        }
    }
    LaunchedEffect(state.multiExposure) {
        if (state.multiExposure == MultiExposure.HasFirst) announce(res.getString(R.string.a11y_first_kept))
    }
    LaunchedEffect(state.renderMode) { if (state.renderMode == RenderMode.Passthrough) snackbar.showSnackbar(res.getString(R.string.err_looks_unavailable)) }
    // Announcer messages.
    val look = state.looks.firstOrNull { it.id == state.lookId }
    val lookLabel = lookName(look)
    LaunchedEffect(state.lookId) { if (look != null) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); announce(res.getString(R.string.a11y_look_selected, lookLabel)) } }
    LaunchedEffect(state.captureState) {
        when (val cs = state.captureState) {
            is CaptureState.Saved -> { haptics.performHapticFeedback(HapticFeedbackType.Confirm); announce(res.getString(R.string.a11y_photo_saved)) }
            is CaptureState.Countdown -> {
                if (cs.n == state.timer.seconds) announce(res.getString(R.string.a11y_timer_started, cs.n))
                else if (cs.n in setOf(5, 3, 2, 1)) announce(cs.n.toString())
            }
            else -> Unit
        }
    }
    LaunchedEffect(state.focus?.phase) { if (state.focus?.phase == FocusPhase.Locked) announce(res.getString(R.string.a11y_focus_locked)) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // UI-side reactions to some intents (announcements / snackbars), then forward.
    val onIntentUi: (CameraIntent) -> Unit = { i ->
        when (i) {
            CameraIntent.Shuffle -> announce(res.getString(R.string.a11y_shuffled))
            CameraIntent.CancelTimer -> announce(res.getString(R.string.a11y_timer_cancelled))
            CameraIntent.Shutter -> if (state.captureState is CaptureState.Countdown) announce(res.getString(R.string.a11y_timer_cancelled))
            CameraIntent.LastShotUnavailable -> scope.launch { snackbar.showSnackbar(res.getString(R.string.err_photo_missing)) }
            else -> Unit
        }
        onIntent(i)
    }
    // SPEC 6.8: the thumbnail opens our own viewer, not another app.
    val openLastShot: () -> Unit = { state.lastShot?.let { uri -> nav.onGallery(uri) } }

    Box(Modifier.fillMaxSize().background(VlColors.Bench)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            CameraLayout(state, onIntentUi, previewHost, nav, openLastShot)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 232.dp, start = 16.dp, end = 16.dp).widthIn(max = 560.dp)) {
            Snackbar(it, containerColor = VlColors.Raised, contentColor = VlColors.Ink)
        }
        Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
        ScreenFlashOverlay(onScreenFlash, state.flashGel)
    }
}

/** DESIGN §4: safe area, mode choice, contained viewfinder, chrome placement. */
@Composable
fun CameraLayout(
    s: CameraUiState,
    onIntent: (CameraIntent) -> Unit,
    previewHost: PreviewHost,
    nav: CameraNav,
    openLastShot: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val w = maxWidth.value
        val h = maxHeight.value
        val mode = remember(w, h, s.imagePortrait) { LayoutMath.chooseMode(w, h, s.imagePortrait) }
        val landscape = LayoutMath.windowLandscape(w, h)
        val m = LayoutMath.metrics(mode, landscape)
        val group = { i: Float -> Modifier.semantics { isTraversalGroup = true; traversalIndex = i } }
        val slot: @Composable (Modifier) -> Unit = { mod ->
            Box(mod.then(group(2f))) {
                ViewfinderSlot(s, onIntent, previewHost, swipeHorizontal = !landscape || mode == LayoutMode.Mini, modifier = Modifier.fillMaxSize())
                MultiShotChip(s, onIntent, Modifier.align(Alignment.TopStart).padding(12.dp))
            }
        }
        if (!landscape) {
            Column(Modifier.fillMaxSize()) {
                if (mode != LayoutMode.Mini) ControlBar(s, onIntent, nav, vertical = false, modifier = Modifier.fillMaxWidth().height(m.bar.dp).then(group(1f)))
                slot(Modifier.weight(1f).fillMaxWidth())
                if (mode != LayoutMode.Mini) {
                    LookPicker(s.looks, s.lookId, vertical = false, compact = mode == LayoutMode.Compact, onSelect = { onIntent(CameraIntent.SelectLook(it)) },
                        modifier = Modifier.fillMaxWidth().height(m.carousel.dp).background(VlColors.Deck).then(group(5f)))
                    Deck(s, onIntent, m.shutterDp, vertical = false, onOpenLastShot = openLastShot, modifier = Modifier.fillMaxWidth().height(m.deck.dp).then(group(6f)))
                } else {
                    MiniBar(s, onIntent, vertical = false, nav = nav, onOpenLastShot = openLastShot, modifier = Modifier.fillMaxWidth().height(m.deck.dp).then(group(6f)))
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                if (mode != LayoutMode.Mini) ControlBar(s, onIntent, nav, vertical = true, modifier = Modifier.fillMaxHeight().width(m.bar.dp).then(group(1f)))
                slot(Modifier.weight(1f).fillMaxHeight())
                if (mode != LayoutMode.Mini) {
                    LookPicker(s.looks, s.lookId, vertical = true, compact = mode == LayoutMode.Compact, onSelect = { onIntent(CameraIntent.SelectLook(it)) },
                        modifier = Modifier.fillMaxHeight().width(m.carousel.dp).background(VlColors.Deck).then(group(5f)))
                    Deck(s, onIntent, m.shutterDp, vertical = true, onOpenLastShot = openLastShot, modifier = Modifier.fillMaxHeight().width(m.deck.dp).then(group(6f)))
                } else {
                    MiniBar(s, onIntent, vertical = true, nav = nav, onOpenLastShot = openLastShot, modifier = Modifier.fillMaxHeight().width(m.deck.dp).then(group(6f)))
                }
            }
        }
    }
}

