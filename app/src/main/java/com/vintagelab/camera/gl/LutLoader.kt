package com.vintagelab.camera.gl

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.vintagelab.camera.looks.LookLutBaker
import com.vintagelab.camera.looks.LutLayout
import com.vintagelab.camera.looks.Preset
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads film LUTs (`assets/luts/<id>.png`, 1089×33, see [LutLayout]) and bakes the per-preset look
 * cube. Decoded film LUTs are cached as CPU floats and shared by both GL contexts, so preview and
 * capture upload byte-identical cubes.
 */
class LutLoader(private val assets: AssetManager) {
    private val filmCache = ConcurrentHashMap<String, FloatArray>()
    /** Bounded (editor sliders bake many distinct presets); 13 looks plus a few adjusted variants fit comfortably. */
    private val bakedCache: MutableMap<Preset, ByteArray> = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<Preset, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Preset, ByteArray>): Boolean = size > BAKED_MAX
        },
    )

    fun filmLut(id: String): FloatArray? {
        filmCache[id]?.let { return it }
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val bmp = try {
            assets.open("luts/$id.png").use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: IOException) {
            Log.e(TAG, "LUT '$id' missing: ${e.message}")
            null
        } ?: return null
        if (bmp.width != LutLayout.IMAGE_WIDTH || bmp.height != LutLayout.IMAGE_HEIGHT) {
            Log.e(TAG, "LUT '$id' has size ${bmp.width}x${bmp.height}")
            bmp.recycle()
            return null
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        return LutLayout.fromArgbPixels(px).also { filmCache[id] = it }
    }

    /** RGBA8 bytes of the baked look cube for [preset]. */
    fun baked(preset: Preset): ByteArray =
        bakedCache[preset] ?: LookLutBaker.bake(preset, preset.lut?.let { filmLut(it) }).also { bakedCache[preset] = it }

    private companion object {
        const val TAG = "LutLoader"
        const val BAKED_MAX = 40
    }
}
