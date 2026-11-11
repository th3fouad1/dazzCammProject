package com.vintagelab.camera.looks

/**
 * Prism lens (SPEC 7.4, Pass 1): user toggle that is not part of a look. [code] is the `uPrism.x` value.
 * All offsets are expressed in normalized image units in the shader, so preview and capture agree.
 */
enum class PrismMode(val code: Int) {
    OFF(0), SPLIT(1), TRI(2), HALO(3);

    fun next(): PrismMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** `uPrism.y`: overall strength of the effect (reserved for a future control). */
        const val AMOUNT = 1f
    }
}
