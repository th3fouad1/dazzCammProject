package com.vintagelab.camera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import com.vintagelab.camera.ui.theme.VlColors
import kotlin.random.Random

/** DESIGN §8 "horizon card" swatch spec for one look. */
private data class SwatchSpec(
    val skyTop: Long,
    val skyBottom: Long,
    val sun: Long?,
    val ground: Long,
    val subject: Long?,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val coarse: Boolean = false,
    val mono: Boolean = false,
    val fade: Float = 0f,
    val glow: Float = 0f,
)

private val SPECS = mapOf(
    "classic35" to SwatchSpec(0xFFF6C88E, 0xFFE8895A, 0xFFFFF1D0, 0xFF5B4A2E, 0xFF3A2C1C, vignette = 0.25f, grain = 0.10f),
    "disposable" to SwatchSpec(0xFF2A2B33, 0xFF3B3A40, null, 0xFF1C1B1E, 0xFFF2D3B3, vignette = 0.55f, grain = 0.22f, coarse = true),
    "instant" to SwatchSpec(0xFFE9D8E4, 0xFFF4E6CF, 0xFFFFFDF5, 0xFFB9C3A9, 0xFF8E8A9A, grain = 0.06f, fade = 0.14f),
    "ccd" to SwatchSpec(0xFFA3C8EA, 0xFFE2EEF7, 0xFFFFFFFF, 0xFF4E6D73, 0xFF2D3E44, grain = 0.05f),
    "bw400" to SwatchSpec(0xFFDADADA, 0xFF7C7C7C, 0xFFFFFFFF, 0xFF2B2B2B, 0xFF111111, vignette = 0.35f, grain = 0.20f, coarse = true, mono = true),
    "fisheye" to SwatchSpec(0xFFF2B47C, 0xFF7FB4C9, 0xFFFFF6E2, 0xFF3C4A3A, null),
    "slide" to SwatchSpec(0xFF2F6FD0, 0xFF8FC4F0, 0xFFFFF3C0, 0xFF1F5A2A, 0xFFB5231B, vignette = 0.15f, grain = 0.05f),
    "premium" to SwatchSpec(0xFFF3DCCB, 0xFFF8EBDD, 0xFFFFFAF0, 0xFFBFB59E, 0xFFD9A58B, vignette = 0.1f, grain = 0.08f, fade = 0.05f),
    "toy120" to SwatchSpec(0xFFE8A04A, 0xFF4FA3B8, 0xFFFFE9A8, 0xFF2E5A3A, 0xFFD2432F, vignette = 0.7f, grain = 0.14f, glow = 0.35f),
    "tlr" to SwatchSpec(0xFF2B7AC0, 0xFFA9D6EE, 0xFFFFF4D8, 0xFF3B6B2E, 0xFFC4381F, vignette = 0.2f, grain = 0.08f),
    "square" to SwatchSpec(0xFFD9B98C, 0xFFEBD3B0, 0xFFFFF0C8, 0xFF8A8F70, 0xFF5C6A74, grain = 0.06f, fade = 0.2f),
    "pano" to SwatchSpec(0xFF3E7FB8, 0xFFF0C48A, 0xFFFFF0C8, 0xFF2F4A38, 0xFF9C3B22, vignette = 0.25f, grain = 0.10f),
    "fine_mono" to SwatchSpec(0xFFE6E6E6, 0xFF8A8A8A, 0xFFFFFFFF, 0xFF1F1F1F, 0xFF0A0A0A, vignette = 0.15f, grain = 0.06f, mono = true),
)

private val FALLBACK = SwatchSpec(0xFF8A8A8A, 0xFF5A5A5A, 0xFFDDDDDD, 0xFF333333, 0xFF1A1A1A)

/** 64×64 grey noise (128 ± 64), seeded so it never changes. */
private val noiseBitmap: ImageBitmap by lazy {
    val r = Random(0x5EED)
    val px = IntArray(64 * 64) { val g = 64 + r.nextInt(129); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
    Bitmap.createBitmap(px, 64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Procedural swatch for [lookId] (no assets; never shows leaks or dust). */
@Composable
fun LookSwatch(lookId: String, modifier: Modifier = Modifier) {
    val spec = SPECS[lookId] ?: FALLBACK
    val grainBrush = remember { ShaderBrush(ImageShader(noiseBitmap, TileMode.Repeated, TileMode.Repeated)) }
    Canvas(modifier) {
        when (lookId) {
            "instant" -> {
                drawRect(VlColors.Paper)
                val side = size.width * 0.06f
                inset(side, size.height * 0.06f, side, size.height * 0.22f) { scene(spec, lookId) }
            }
            "square" -> {
                drawRect(VlColors.Paper)
                val side = size.width * 0.07f
                inset(side, size.height * 0.07f, side, size.height * 0.24f) { scene(spec, lookId) }
            }
            "toy120", "tlr" -> {
                drawRect(Color(0xFF0B0B0B))
                val b = size.width * 0.05f
                inset(b) { scene(spec, lookId) }
                val tickW = size.width * 0.010f
                val tickH = size.height * 0.016f
                for (i in 0 until 4) {
                    drawRect(Color(0xFF9E9E9E), Offset(size.width * (0.12f * i + 0.06f) - tickW / 2, size.height - b / 2 - tickH / 2), Size(tickW, tickH))
                }
            }
            "slide" -> {
                val mount = Color(0xFFEDE9DF)
                drawRect(mount)
                val b = size.width * 0.12f
                val win = Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(b, b, size.width - b, size.height - b, CornerRadius(size.width * 0.03f)))
                }
                clipPath(win) { inset(b) { scene(spec, lookId) } }
            }
            "pano" -> {
                scene(spec, lookId)
                // Dark rebate strips over the top and bottom edge with sprocket holes.
                val band = size.height * 0.16f
                val holeW = size.width * 0.07f
                val holeH = band * 0.55f
                val pitch = size.width * 0.14f
                val hole = Color(0xFFDCCDAE)
                drawRect(Color(0xF00A0908), Offset.Zero, Size(size.width, band))
                drawRect(Color(0xF00A0908), Offset(0f, size.height - band), Size(size.width, band))
                var x = pitch * 0.5f
                while (x < size.width) {
                    for (cy in floatArrayOf(band / 2f, size.height - band / 2f)) {
                        drawRoundRect(hole, Offset(x - holeW / 2f, cy - holeH / 2f), Size(holeW, holeH), CornerRadius(holeW * 0.2f))
                    }
                    x += pitch
                }
            }
            "fisheye" -> {
                drawRect(VlColors.Bench)
                val r = size.minDimension * 0.48f
                val c = Offset(size.width / 2, size.height / 2)
                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
                clipPath(circle) { scene(spec, lookId) }
                drawCircle(Color.White.copy(alpha = 0.2f), r, c, style = Stroke(width = density))
            }
            else -> scene(spec, lookId)
        }
        if (spec.grain > 0f) drawRect(grainBrush, alpha = spec.grain, blendMode = BlendMode.Overlay)
    }
}

private fun DrawScope.scene(s: SwatchSpec, lookId: String) {
    fun c(v: Long): Color {
        val col = Color(v)
        if (!s.mono) return col
        val l = 0.2126f * col.red + 0.7152f * col.green + 0.0722f * col.blue
        return Color(l, l, l)
    }
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(c(s.skyTop), c(s.skyBottom)), 0f, h * 0.62f), size = Size(w, h * 0.62f))
    if (lookId == "fisheye") {
        val ground = Path().apply {
            moveTo(0f, h * 0.66f); quadraticTo(w / 2, h * 0.50f, w, h * 0.66f); lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(ground, c(s.ground))
    } else {
        drawRect(c(s.ground), Offset(0f, h * 0.62f), Size(w, h * 0.38f))
    }
    s.sun?.let { drawCircle(c(it), w * 0.13f, Offset(w * 0.68f, h * 0.34f)) }
    if (lookId == "ccd") drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f), Color.Transparent), Offset(w * 0.68f, h * 0.34f), w * 0.3f), w * 0.3f, Offset(w * 0.68f, h * 0.34f))
    s.subject?.let {
        drawRoundRect(c(it), Offset(w * 0.18f, h * 0.70f), Size(w * 0.22f, h * 0.14f), CornerRadius(w * 0.07f))
    }
    if (lookId == "disposable") {
        val center = Offset(w * 0.3f, h * 0.75f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4E0).copy(alpha = 0.85f), Color.Transparent), center, w * 0.35f), w * 0.35f, center)
    }
    if (lookId == "ccd") {
        for (i in 0 until 3) {
            drawRect(VlColors.Amber, Offset(w * (0.70f + i * 0.08f), h * (1f - 0.06f - 0.012f)), Size(w * 0.06f, h * 0.012f))
        }
    }
    if (s.glow > 0f) {
        val center = Offset(w * 0.5f, h * 0.5f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = s.glow), Color.Transparent), center, w * 0.4f), w * 0.4f, center)
    }
    if (s.fade > 0f) drawRect(Color.White, alpha = s.fade, blendMode = BlendMode.Screen)
    if (s.vignette > 0f) {
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = s.vignette)), Offset(w / 2, h / 2), maxOf(w, h) * 0.75f))
    }
}
