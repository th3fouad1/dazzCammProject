package com.vintagelab.camera.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object VlSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Chrome sizes per layout mode (DESIGN §4.2), in dp along the chrome axis. */
data class ChromeMetrics(
    val bar: Float,
    val carousel: Float,
    val deck: Float,
    val shutter: Float,
    val cardW: Float,
    val cardH: Float,
) {
    val total: Float get() = bar + carousel + deck
    val shutterDp: Dp get() = shutter.dp

    companion object {
        val RegularPortrait = ChromeMetrics(56f, 96f, 120f, 80f, 72f, 88f)
        val RegularLandscape = ChromeMetrics(64f, 96f, 120f, 80f, 88f, 72f)
        val Compact = ChromeMetrics(56f, 64f, 104f, 72f, 56f, 56f)
        val Mini = ChromeMetrics(0f, 0f, 88f, 64f, 0f, 0f)
    }
}
