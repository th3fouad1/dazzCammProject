package com.vintagelab.camera.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.max
import kotlin.math.min

/** Zoom and pan of one viewer photo; offset is in px from the centred, fitted position. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** Applies a pinch/pan step; [centroid] is relative to the view centre. */
    internal fun transform(zoom: Float, pan: Offset, centroid: Offset, view: Size, imageAspect: Float) {
        val (s, o) = ZoomMath.step(scale, offset, zoom, pan, centroid, view, imageAspect)
        scale = s
        offset = o
    }

    /** Double tap: zoom in on the tapped point, or back to fit. */
    internal fun toggle(tap: Offset, view: Size, imageAspect: Float) {
        if (scale > 1f) reset() else transform(ZoomMath.DOUBLE_TAP / scale, Offset.Zero, tap, view, imageAspect)
    }
}

internal object ZoomMath {
    const val MAX = 5f
    const val DOUBLE_TAP = 2.5f

    /** Size of an image of [aspect] (w/h) fitted inside [view]. */
    fun fitted(view: Size, aspect: Float): Size =
        if (view.width / view.height > aspect) Size(view.height * aspect, view.height) else Size(view.width, view.width / aspect)

    /** Keeps the point under [centroid] fixed while scaling, then clamps so the photo never leaves its edges. */
    fun step(scale: Float, offset: Offset, zoom: Float, pan: Offset, centroid: Offset, view: Size, aspect: Float): Pair<Float, Offset> {
        val s = (scale * zoom).coerceIn(1f, MAX)
        val k = s / scale
        val raw = centroid - (centroid - offset) * k + pan
        return s to clamp(raw, s, view, aspect)
    }

    fun clamp(o: Offset, s: Float, view: Size, aspect: Float): Offset {
        val img = fitted(view, aspect)
        val mx = max(0f, (img.width * s - view.width) / 2f)
        val my = max(0f, (img.height * s - view.height) / 2f)
        return Offset(min(mx, max(-mx, o.x)), min(my, max(-my, o.y)))
    }
}

/**
 * Pinch to zoom (1x to 5x), drag to pan while zoomed, double tap to toggle. At 1x a one-finger drag is left
 * unconsumed so the pager still swipes between photos.
 */
internal fun Modifier.zoomable(state: ZoomState, imageAspect: Float): Modifier = this
    .pointerInput(state, imageAspect) {
        detectTapGestures(onDoubleTap = { p ->
            val view = Size(size.width.toFloat(), size.height.toFloat())
            state.toggle(p - view.center(), view, imageAspect)
        })
    }
    .pointerInput(state, imageAspect) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || state.scale > 1f) {
                    val view = Size(size.width.toFloat(), size.height.toFloat())
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (centroid != Offset.Unspecified) {
                        state.transform(event.calculateZoom(), event.calculatePan(), centroid - view.center(), view, imageAspect)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = state.scale
        scaleY = state.scale
        translationX = state.offset.x
        translationY = state.offset.y
    }

private fun Size.center() = Offset(width / 2f, height / 2f)
