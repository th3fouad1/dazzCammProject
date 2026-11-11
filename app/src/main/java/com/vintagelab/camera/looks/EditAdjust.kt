package com.vintagelab.camera.looks

/** Editor adjustments applied on top of a look (SPEC 6.7): exposure in EV and a temperature offset. */
object EditAdjust {
    const val EV_RANGE = 1f
    const val TEMP_RANGE = 0.5f

    /** The look with the editor's exposure [ev] and temperature [temp] added; both go through the normal LUT bake. */
    fun apply(preset: Preset, ev: Float, temp: Float): Preset {
        val e = ev.coerceIn(-EV_RANGE, EV_RANGE)
        val t = temp.coerceIn(-TEMP_RANGE, TEMP_RANGE)
        if (e == 0f && t == 0f) return preset
        return preset.copy(
            exposure = (preset.exposure + e).coerceIn(-3f, 3f),
            temperature = (preset.temperature + t).coerceIn(-1f, 1f),
        )
    }
}
