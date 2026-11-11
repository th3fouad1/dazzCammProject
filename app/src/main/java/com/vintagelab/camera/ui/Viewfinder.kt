package com.vintagelab.camera.ui

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vintagelab.camera.R
import com.vintagelab.camera.capture.FrameGeometry
import com.vintagelab.camera.gl.PreviewEvent
import com.vintagelab.camera.gl.PreviewHost
import com.vintagelab.camera.ui.icons.VlIcons
import com.vintagelab.camera.ui.theme.LocalReducedMotion
import com.vintagelab.camera.ui.theme.VlColors
import com.vintagelab.camera.ui.theme.VlMotion
import com.vintagelab.camera.ui.theme.VlRadius
import com.vintagelab.camera.ui.theme.VlType
import com.vintagelab.camera.ui.theme.countdown
import com.vintagelab.camera.ui.theme.zoom
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Viewfinder slot: contains the canvas-aspect viewfinder plus its overlays (DESIGN §4.1, §10.2). */
@Composable
fun ViewfinderSlot(
    s: CameraUiState,
    onIntent: (CameraIntent) -> Unit,
    previewHost: PreviewHost,
    swipeHorizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val aspect = FrameGeometry.canvasAspect(s.ratio, s.frame, s.imagePortrait)
    var veilTarget by remember { mutableStateOf(1f) }
    val reduced = LocalReducedMotion.current
    val veil by animateFloatAsState(veilTarget, if (veilTarget > 0f) VlMotion.quick() else VlMotion.standard(reduced), label = "veil")
    // Veil on bind/size change; lift on the first frame (K11) with a 350/800 ms fallback.
    LaunchedEffect(s.lens, s.ratio, s.frame) {
        veilTarget = 1f
        delay(800)
        veilTarget = 0f
    }
    LaunchedEffect(previewHost) {
        previewHost.events.collect { if (it == PreviewEvent.FirstFrame) veilTarget = 0f }
    }
    LaunchedEffect(s.frame) { delay(350); veilTarget = 0f }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val vf = LayoutMath.contain(DpSize2(maxWidth.value, maxHeight.value), aspect)
        Box(Modifier.size(vf.w.dp, vf.h.dp)) {
            Viewfinder(previewHost)
            ViewfinderOverlays(s, onIntent, swipeHorizontal)
            Box(Modifier.fillMaxSize().alpha(veil).background(VlColors.Bench))
            CameraStatusPanel(s, onIntent)
        }
    }
}

@Composable
private fun Viewfinder(previewHost: PreviewHost) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            previewHost.createView(ctx).also { v ->
                // Forward lifecycle to the GL thread (SPEC §6.3).
                val observer = LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_RESUME -> v.onResume()
                        Lifecycle.Event.ON_PAUSE -> v.onPause()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                v.setTag(R.id.lifecycle_observer, observer)
            }
        },
        onRelease = { v: GLSurfaceView ->
            (v.getTag(R.id.lifecycle_observer) as? LifecycleEventObserver)?.let { lifecycle.removeObserver(it) }
        },
    )
}

@Composable
private fun ViewfinderOverlays(s: CameraUiState, onIntent: (CameraIntent) -> Unit, swipeHorizontal: Boolean) {
    val img = FrameGeometry.imageRectInCanvas(s.ratio, s.frame, s.imagePortrait)
    val state by rememberUpdatedState(s)
    val imgNow by rememberUpdatedState(img)
    val density = LocalDensity.current
    var tap by remember { mutableStateOf<Offset?>(null) }
    val currentLook = s.looks.firstOrNull { it.id == s.lookId }
    val name = lookName(currentLook)
    val index = s.looks.indexOfFirst { it.id == s.lookId }
    val selectAt: (Int) -> Unit = { i -> s.looks.getOrNull(i)?.let { onIntent(CameraIntent.SelectLook(it.id)) } }
    val zoomIn = stringResource(R.string.a11y_action_zoom_in)
    val zoomOut = stringResource(R.string.a11y_action_zoom_out)
    val zoomReset = stringResource(R.string.a11y_action_zoom_reset)
    val nextLook = stringResource(R.string.a11y_action_next_look)
    val prevLook = stringResource(R.string.a11y_action_prev_look)
    val shuffleLbl = stringResource(R.string.a11y_action_shuffle)
    val focusCenter = stringResource(R.string.a11y_action_focus_center)
    val vfCd = stringResource(R.string.cd_viewfinder, name)
    val systemGestureDp = 24f

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = vfCd
                    onClick(focusCenter) { onIntent(CameraIntent.TapFocus(0.5f, 0.5f)); true }
                    customActions = buildList {
                        add(CustomAccessibilityAction(zoomIn) { onIntent(CameraIntent.Zoom(state.zoom + 0.1f)); true })
                        add(CustomAccessibilityAction(zoomOut) { onIntent(CameraIntent.Zoom(state.zoom - 0.1f)); true })
                        add(CustomAccessibilityAction(zoomReset) {
                            onIntent(CameraIntent.Zoom(ZoomMath.linearFromRatio(1f, state.zoomRange.start, state.zoomRange.endInclusive))); true
                        })
                        add(CustomAccessibilityAction(nextLook) { selectAt(index + 1); true })
                        add(CustomAccessibilityAction(prevLook) { selectAt(index - 1); true })
                        if (currentLook?.hasRandomFx == true) add(CustomAccessibilityAction(shuffleLbl) { onIntent(CameraIntent.Shuffle); true })
                    }
                }
                // The block is keyed only on orientation, so everything that changes (look, ratio, frame)
                // must be read through state/imgNow at gesture end, never captured from the composition.
                .pointerInput(swipeHorizontal) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = down.position
                        val t0 = down.uptimeMillis
                        val edge = systemGestureDp * density.density
                        val inSystemGesture = start.x < edge || start.x > wPx - edge || start.y > hPx - edge
                        var pinching = false
                        var startZoom = state.zoom
                        var scale = 1f
                        var last = start
                        var lastTime = t0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (!pinching) { pinching = true; startZoom = state.zoom; scale = 1f }
                                scale *= ev.calculateZoom()
                                onIntent(CameraIntent.Zoom((startZoom + (scale - 1f) * 0.5f).coerceIn(0f, 1f)))
                                ev.changes.forEach { it.consume() }
                            } else {
                                last = pressed[0].position
                                lastTime = pressed[0].uptimeMillis
                            }
                        }
                        if (pinching) return@awaitEachGesture
                        val dx = (last.x - start.x) / density.density
                        val dy = (last.y - start.y) / density.density
                        val dt = maxOf(1L, lastTime - t0) / 1000f
                        val img = imgNow
                        val looksNow = state.looks
                        val indexNow = looksNow.indexOfFirst { it.id == state.lookId }
                        val travel = if (swipeHorizontal) dx else dy
                        val cross = if (swipeHorizontal) dy else dx
                        if (abs(travel) < 10f && abs(cross) < 10f) {
                            // Tap: normalized image-area coordinates (K10); taps on the frame margin are ignored.
                            val nx = (start.x / wPx - img.x) / img.z
                            val ny = (start.y / hPx - img.y) / img.w
                            if (nx in 0f..1f && ny in 0f..1f) {
                                tap = start
                                onIntent(CameraIntent.TapFocus(nx, ny))
                            }
                        } else if (abs(travel) > abs(cross)) {
                            SwipeRules.target(travel, travel / dt, inSystemGesture, indexNow, looksNow.size)?.let {
                                if (it != indexNow) looksNow.getOrNull(it)?.let { l -> onIntent(CameraIntent.SelectLook(l.id)) }
                            }
                        }
                    }
                },
        )
        FocusRing(s.focus, tap, img.x * wPx, img.y * hPx, img.z * wPx, img.w * hPx)
        if (currentLook?.hasRandomFx == true) ShuffleButton(onIntent, Modifier.align(Alignment.BottomStart).padding(12.dp))
        ZoomPill({ state.zoomRatio }, s, onIntent, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        LookToast(s.lookId, name, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
        CountdownNumeral(s.captureState, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun FocusRing(focus: FocusUi?, tap: Offset?, left: Float, top: Float, w: Float, h: Float) {
    if (focus == null || tap == null) return
    val reduced = LocalReducedMotion.current
    val anim = remember(focus.seq) { Animatable(1.3f) }
    val alpha = remember(focus.seq) { Animatable(0f) }
    LaunchedEffect(focus.seq) {
        if (reduced) anim.snapTo(1f) else anim.animateTo(1f, VlMotion.standard())
    }
    LaunchedEffect(focus.seq, focus.phase) {
        alpha.animateTo(1f, VlMotion.standard(reduced))
        when (focus.phase) {
            FocusPhase.Focusing -> { delay(3000); alpha.animateTo(0f) }
            FocusPhase.Locked -> { delay(600); alpha.animateTo(0f, androidx.compose.animation.core.tween(300)) }
            FocusPhase.Failed -> alpha.animateTo(0f, androidx.compose.animation.core.tween(300))
        }
    }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(focus.seq, focus.phase) { if (focus.phase == FocusPhase.Locked) haptics.performHapticFeedback(HapticFeedbackType.ContextClick) }
    val r = with(LocalDensity.current) { 36.dp.toPx() }
    val cx = tap.x.coerceIn(left + r, maxOf(left + r, left + w - r))
    val cy = tap.y.coerceIn(top + r, maxOf(top + r, top + h - r))
    val color = if (focus.phase == FocusPhase.Failed) VlColors.InkMuted else VlColors.Amber
    val tick = if (focus.phase == FocusPhase.Locked) 9.dp else 6.dp
    Canvas(Modifier.fillMaxSize().alpha(alpha.value)) {
        val rr = r * anim.value
        val c = Offset(cx, cy)
        drawCircle(VlColors.Halo, rr + 1.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        drawCircle(color, rr, c, style = Stroke(1.5.dp.toPx()))
        val t = tick.toPx()
        drawLine(color, Offset(c.x, c.y - rr), Offset(c.x, c.y - rr + t), 1.5.dp.toPx())
        drawLine(color, Offset(c.x, c.y + rr), Offset(c.x, c.y + rr - t), 1.5.dp.toPx())
        drawLine(color, Offset(c.x - rr, c.y), Offset(c.x - rr + t, c.y), 1.5.dp.toPx())
        drawLine(color, Offset(c.x + rr, c.y), Offset(c.x + rr - t, c.y), 1.5.dp.toPx())
    }
}

@Composable
private fun ShuffleButton(onIntent: (CameraIntent) -> Unit, modifier: Modifier) {
    var turns by remember { mutableStateOf(0f) }
    val rot by animateFloatAsState(turns, VlMotion.standard(LocalReducedMotion.current), label = "shuffle")
    val haptics = LocalHapticFeedback.current
    val cd = stringResource(R.string.cd_shuffle)
    Box(
        modifier
            .size(48.dp)
            .clickable { turns += 180f; haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onIntent(CameraIntent.Shuffle) }
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(VlColors.VfScrimIcon), contentAlignment = Alignment.Center) {
            Icon(VlIcons.Shuffle, null, tint = VlColors.Ink, modifier = Modifier.size(22.dp).rotate(rot))
        }
    }
}

@Composable
private fun ZoomPill(zoomRatio: () -> Float, s: CameraUiState, onIntent: (CameraIntent) -> Unit, modifier: Modifier) {
    val min = s.zoomRange.start
    val max = s.zoomRange.endInclusive
    val hasRange = max > min
    var active by remember { mutableStateOf(false) }
    var idleFaded by remember { mutableStateOf(false) }
    LaunchedEffect(s.zoom) { active = true; idleFaded = false; delay(1500); active = false; idleFaded = true }
    val alpha by animateFloatAsState(if (idleFaded) 0.6f else 1f, VlMotion.quick(), label = "zoomAlpha")
    val r = zoomRatio()
    val label = if (hasRange) stringResource(R.string.label_zoom, ZoomMath.format(r)) else "${(s.zoom * 100).toInt()}%"
    val cd = stringResource(R.string.cd_zoom, ZoomMath.format(r))
    val state by rememberUpdatedState(s)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .alpha(alpha)
            .clip(RoundedCornerShape(50))
            .background(VlColors.VfScrim)
            .sizeIn(minWidth = if (active) 120.dp else 56.dp, minHeight = 32.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dx ->
                    onIntent(CameraIntent.Zoom((state.zoom + dx / (200.dp.toPx())).coerceIn(0f, 1f)))
                }
            }
            .clickable {
                val target = if (r < 1.5f && max >= 2f) 2f else 1f
                onIntent(CameraIntent.Zoom(if (hasRange) ZoomMath.linearFromRatio(target, min, max) else 0f))
            }
            .semantics {
                contentDescription = cd
                if (hasRange) {
                    progressBarRangeInfo = ProgressBarRangeInfo(r.coerceIn(min, max), min..max)
                    setProgress { v -> onIntent(CameraIntent.Zoom(ZoomMath.linearFromRatio(v, min, max))); true }
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = VlType.zoom(), color = VlColors.Ink)
    }
}

@Composable
private fun LookToast(lookId: String, name: String, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(lookId) {
        if (first) { first = false; return@LaunchedEffect }
        visible = true
        delay(900 + VlMotion.Quick.toLong())
        visible = false
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(VlMotion.quick<Float>()), exit = fadeOut(VlMotion.standard<Float>())) {
        Text(
            name.uppercase(), style = VlType.lookToast, color = VlColors.Ink,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(VlColors.VfScrim).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CountdownNumeral(cs: CaptureState, modifier: Modifier) {
    val n = (cs as? CaptureState.Countdown)?.n ?: return
    val reduced = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val scale = remember(n) { Animatable(if (reduced) 1f else 1.4f) }
    LaunchedEffect(n) {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scale.animateTo(1f, VlMotion.standard())
    }
    val style = VlType.countdown()
    Box(modifier.scale(scale.value)) {
        Text(n.toString(), style = style.copy(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = with(LocalDensity.current) { 3.dp.toPx() })), color = VlColors.Halo)
        Text(n.toString(), style = style, color = if (n <= 3) VlColors.Amber else VlColors.Ink)
    }
}

/** Opening (after 1 s) / Error panel over the viewfinder (DESIGN §5.1, §7.1). */
@Composable
private fun CameraStatusPanel(s: CameraUiState, onIntent: (CameraIntent) -> Unit) {
    var showOpening by remember { mutableStateOf(false) }
    LaunchedEffect(s.camera) {
        showOpening = false
        if (s.camera == CameraStatus.Opening) { delay(1000); showOpening = true }
    }
    val err = (s.camera as? CameraStatus.Error)?.kind
    if (err == null && !(showOpening && s.camera == CameraStatus.Opening)) return
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(VlRadius.Card)).background(VlColors.Raised).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (err == null) {
                Text(stringResource(R.string.status_opening), style = MaterialTheme.typography.bodyLarge, color = VlColors.InkMuted)
                return@Column
            }
            val (title, body) = when (err) {
                CameraErrorKind.InUse -> R.string.err_camera_in_use_title to R.string.err_camera_in_use_body
                CameraErrorKind.Disabled -> R.string.err_camera_disabled_title to R.string.err_camera_disabled_body
                CameraErrorKind.Fatal -> R.string.err_camera_generic_title to R.string.err_camera_generic_body
                CameraErrorKind.GpuUnsupported -> R.string.err_gpu_title to R.string.err_gpu_body
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(VlColors.Signal))
            Text(
                stringResource(title), color = VlColors.Ink, style = MaterialTheme.typography.titleLarge.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize * 0.82f),
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            )
            Text(stringResource(body), color = VlColors.Ink, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (err == CameraErrorKind.InUse || err == CameraErrorKind.Fatal) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onIntent(CameraIntent.RetryCamera) },
                    colors = ButtonDefaults.buttonColors(containerColor = VlColors.Amber, contentColor = VlColors.OnAmber),
                    modifier = Modifier.height(48.dp).sizeIn(minWidth = 160.dp),
                ) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

