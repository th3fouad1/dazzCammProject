package com.vintagelab.camera.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Own geometric icons (DESIGN §5): 24 dp grid, 1.75 dp round strokes, tinted at use site. */
object VlIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(p: PathBuilder.() -> Unit) = path(
        stroke = SolidColor(Color.White), strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = p,
    )

    private fun ImageVector.Builder.fill(p: PathBuilder.() -> Unit) = path(fill = SolidColor(Color.White), pathBuilder = p)

    private fun PathBuilder.bolt() {
        moveTo(13f, 3f); lineTo(6f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 21f); lineTo(18f, 10f); lineTo(12.5f, 10f); close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy); arcTo(r, r, 0f, true, true, cx + r, cy); arcTo(r, r, 0f, true, true, cx - r, cy); close()
    }

    val FlashOff = icon("FlashOff") { stroke { bolt() }; stroke { moveTo(4f, 4f); lineTo(20f, 20f) } }
    val FlashOn = icon("FlashOn") { fill { bolt() } }
    val FlashAuto = icon("FlashAuto") {
        stroke { bolt() }
        stroke { moveTo(16f, 22f); lineTo(19f, 15f); lineTo(22f, 22f); moveTo(17.2f, 19.5f); lineTo(20.8f, 19.5f) }
    }
    val ScreenFlash = icon("ScreenFlash") {
        stroke { moveTo(9f, 7f); lineTo(15f, 7f); quadTo(17f, 7f, 17f, 9f); lineTo(17f, 21f); lineTo(7f, 21f); lineTo(7f, 9f); quadTo(7f, 7f, 9f, 7f); close() }
        stroke { moveTo(12f, 2f); lineTo(12f, 4f); moveTo(7f, 3f); lineTo(8f, 4.5f); moveTo(17f, 3f); lineTo(16f, 4.5f) }
    }
    val Timer = icon("Timer") {
        stroke { circle(12f, 13f, 8f) }
        stroke { moveTo(12f, 2f); lineTo(12f, 4f); moveTo(12f, 13f); lineTo(16f, 10f) }
    }
    val FrameNone = icon("FrameNone") {
        stroke {
            for (i in 0 until 4) {
                val a = 4f + i * 4f
                moveTo(a, 4f); lineTo(a + 2f, 4f); moveTo(a, 20f); lineTo(a + 2f, 20f)
                moveTo(4f, a); lineTo(4f, a + 2f); moveTo(20f, a); lineTo(20f, a + 2f)
            }
        }
    }
    val FrameInstant = icon("FrameInstant") {
        stroke { moveTo(4f, 3f); lineTo(20f, 3f); lineTo(20f, 21f); lineTo(4f, 21f); close() }
        fill { moveTo(4f, 16f); lineTo(20f, 16f); lineTo(20f, 21f); lineTo(4f, 21f); close() }
    }
    val FrameStrip = icon("FrameStrip") {
        stroke { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close() }
        fill { for (x in listOf(7f, 12f, 17f)) { circle(x, 2.5f, 1f); circle(x, 21.5f, 1f) } }
    }
    val DateStamp = icon("DateStamp") {
        fill {
            // 7-segment "8", 10 × 16, 1.5 gaps
            fun seg(x0: Float, y0: Float, x1: Float, y1: Float) { moveTo(x0, y0); lineTo(x1, y0); lineTo(x1, y1); lineTo(x0, y1); close() }
            seg(8.5f, 4f, 15.5f, 5.6f); seg(8.5f, 11.2f, 15.5f, 12.8f); seg(8.5f, 18.4f, 15.5f, 20f)
            seg(7f, 5.5f, 8.6f, 11.3f); seg(15.4f, 5.5f, 17f, 11.3f); seg(7f, 12.7f, 8.6f, 18.5f); seg(15.4f, 12.7f, 17f, 18.5f)
        }
    }
    val Shuffle = icon("Shuffle") {
        stroke { moveTo(7.4f, 5.6f); lineTo(17.5f, 3.5f); lineTo(19.9f, 15.2f); lineTo(9.8f, 17.4f); close() }
        fill { circle(10.5f, 8f, 1.2f); circle(13.7f, 10.5f, 1.2f); circle(16.8f, 13f, 1.2f) }
    }
    val LensSwitch = icon("LensSwitch") {
        stroke { moveTo(4f, 12f); arcTo(8f, 8f, 0f, false, true, 18.5f, 7.5f); moveTo(18.5f, 7.5f); lineTo(18.8f, 4f); moveTo(18.5f, 7.5f); lineTo(15f, 7.2f) }
        stroke { moveTo(20f, 12f); arcTo(8f, 8f, 0f, false, true, 5.5f, 16.5f); moveTo(5.5f, 16.5f); lineTo(5.2f, 20f); moveTo(5.5f, 16.5f); lineTo(9f, 16.8f) }
        fill { circle(12f, 12f, 1.5f) }
    }
    /** Crescent: boost Night (outline = off, filled = on). */
    val Moon = icon("Moon") {
        stroke { moveTo(14.5f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, false, 20.5f, 14.5f); arcTo(6.8f, 6.8f, 0f, false, true, 14.5f, 3.5f); close() }
    }
    val MoonOn = icon("MoonOn") {
        fill { moveTo(14.5f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, false, 20.5f, 14.5f); arcTo(6.8f, 6.8f, 0f, false, true, 14.5f, 3.5f); close() }
    }
    val More = icon("More") { fill { circle(12f, 5f, 1.5f); circle(12f, 12f, 1.5f); circle(12f, 19f, 1.5f) } }
    val Back = icon("Back") { stroke { moveTo(20f, 12f); lineTo(4f, 12f); moveTo(10f, 6f); lineTo(4f, 12f); lineTo(10f, 18f) } }
    val Chevron = icon("Chevron") { stroke { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }
}
