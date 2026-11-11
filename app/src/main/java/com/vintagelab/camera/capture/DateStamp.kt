package com.vintagelab.camera.capture

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date stamp text `'yy MM dd` encoded as 8 glyph codes for `uStampDigits[2]` (SPEC §7.2/§7.3).
 * Slot layout: [apostrophe, y, y, M, M, d, d, blank]; the shader inserts the group gaps.
 * Glyphs are drawn procedurally (SDF 7-segment) in `overlay.glsl` — no font asset.
 */
object DateStamp {
    const val GLYPH_BLANK = 10
    const val GLYPH_APOSTROPHE = 11
    const val SLOTS = 8
    const val VIDEO_SLOTS = 20
    const val GLYPH_COLON = 12
    const val GLYPH_A = 13

    fun codes(t: LocalDateTime): IntArray {
        val yy = t.year % 100
        val mm = t.monthValue
        val dd = t.dayOfMonth
        return intArrayOf(
            GLYPH_APOSTROPHE,
            yy / 10, yy % 10,
            mm / 10, mm % 10,
            dd / 10, dd % 10,
            GLYPH_BLANK,
        )
    }

    /** `MMM dd yyyy HH:mm`, encoded for the procedural video stamp in overlay.glsl. */
    fun videoCodes(t: LocalDateTime): IntArray {
        val text = VIDEO_FORMAT.format(t).uppercase(Locale.ENGLISH)
        return IntArray(VIDEO_SLOTS) { i ->
            when (val c = text.getOrNull(i) ?: ' ') {
                in '0'..'9' -> c - '0'
                ':' -> GLYPH_COLON
                in 'A'..'Z' -> GLYPH_A + (c - 'A')
                else -> GLYPH_BLANK
            }
        }
    }

    /** Human-readable form of [codes] (for logs/tests), e.g. `'26 11 11`. */
    fun text(codes: IntArray): String {
        fun c(i: Int) = when (val g = codes[i]) {
            GLYPH_APOSTROPHE -> "'"
            GLYPH_BLANK -> ""
            else -> g.toString()
        }
        return "${c(0)}${c(1)}${c(2)} ${c(3)}${c(4)} ${c(5)}${c(6)}${c(7)}"
    }

    /** 7-segment bit masks (bit 0..6 = a,b,c,d,e,f,g) for digits 0–9; mirrored in overlay.glsl. */
    val SEGMENTS = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)

    private val VIDEO_FORMAT = DateTimeFormatter.ofPattern("MMM dd yyyy HH:mm", Locale.ENGLISH)
}
