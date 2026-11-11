package com.vintagelab.camera.looks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetParserTest {

    private class Rec : PresetLog {
        val warns = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun warn(msg: String) { warns += msg }
        override fun error(msg: String) { errors += msg }
    }

    private fun parse(text: String, log: PresetLog = PresetLog.Silent) = PresetParser.parse(text, log)

    @Test
    fun shippedFile_isValid_withTwelveLooks() {
        val log = Rec()
        val presets = TestAssets.presets(log)
        assertEquals((log.warns + log.errors).toString(), 0, log.warns.size + log.errors.size)
        assertEquals(listOf("classic35", "disposable", "instant", "ccd", "ccd2", "bw400", "fisheye", "slide", "premium", "toy120", "tlr", "square", "fine_mono", "pano"), presets.map { it.id })
        for (p in presets) {
            val lut = p.lut
            if (lut != null) assertTrue("missing LUT $lut", TestAssets.file("luts/$lut.png").exists())
        }
        assertEquals(FrameId.INSTANT_WHITE, presets.first { it.id == "instant" }.defaultFrame)
        assertTrue(presets.first { it.id == "ccd" }.dateStamp.enabledByDefault)
        assertTrue(presets.first { it.id == "ccd2" }.dateStamp.enabledByDefault)
        assertEquals("fuji_superia_200", presets.first { it.id == "ccd2" }.lut)
        assertTrue(presets.first { it.id == "bw400" }.monochrome)
        assertTrue(presets.first { it.id == "fisheye" }.distortion.circularMask)
        assertEquals(FrameId.SLIDE_MOUNT, presets.first { it.id == "slide" }.defaultFrame)
        assertEquals(FrameId.FILM_120, presets.first { it.id == "toy120" }.defaultFrame)
        assertEquals(FrameId.FILM_120, presets.first { it.id == "tlr" }.defaultFrame)
        assertEquals(FrameId.INSTANT_SQUARE, presets.first { it.id == "square" }.defaultFrame)
        assertTrue(presets.first { it.id == "fine_mono" }.monochrome)
        for (id in listOf("slide", "premium", "toy120", "tlr", "square", "fine_mono")) {
            assertEquals("look_$id", presets.first { it.id == id }.displayNameRes)
        }
    }

    @Test
    fun newLooks_matchBrief() {
        val all = TestAssets.presets()
        val slide = all.first { it.id == "slide" }
        assertEquals("fuji_velvia_50", slide.lut)
        assertEquals(0.85f, slide.lutStrength)
        val premium = all.first { it.id == "premium" }
        assertEquals("kodak_portra_400", premium.lut)
        assertEquals(0.2f, premium.grain.amount)
        val toy = all.first { it.id == "toy120" }
        assertEquals("fuji_superia_200", toy.lut)
        assertEquals(0.75f, toy.vignette.amount)
        assertEquals(VignetteShape.CIRCLE, toy.vignette.shape)
        assertEquals(0.3f, toy.softFocus)
        assertEquals(0.85f, toy.lightLeak.probability)
        assertEquals(0.6f, toy.lightLeak.amount)
        assertEquals(0.1f, toy.distortion.barrel)
        assertEquals("kodak_ektar_100", all.first { it.id == "tlr" }.lut)
        val sq = all.first { it.id == "square" }
        assertEquals("fuji_provia_100f", sq.lut)
        assertEquals(0.2f, sq.fade)
        assertEquals("ilford_delta_100", all.first { it.id == "fine_mono" }.lut)
        // temperature scale is +-0.25 t, so every look stays within +-0.25
        for (p in all) assertTrue("${p.id} temperature", kotlin.math.abs(p.temperature) <= 0.25f)
    }

    @Test
    fun classic35_matchesAppendixA() {
        val p = TestAssets.presets().first { it.id == "classic35" }
        assertEquals("look_classic35", p.displayNameRes)
        assertEquals("kodak_gold_200", p.lut)
        assertEquals(1.0f, p.lutStrength)
        assertEquals(0.12f, p.exposure)
        assertEquals(listOf(0f to 0.04f, 0.25f to 0.24f, 0.75f to 0.78f, 1f to 0.97f), p.tone.all)
        assertEquals(1.15f, p.saturation)
        assertEquals(0.216f, p.temperature)
        assertEquals(0f, p.tint)
        assertEquals(TintSpec(Rgb.parseHex("#2E3A2A")!!, 0.06f), p.shadowTint)
        assertEquals(TintSpec(Rgb.parseHex("#FFB955")!!, 0.30f), p.highlightTint)
        assertEquals(0.06f, p.fade)
        assertFalse(p.monochrome)
        assertEquals(GrainSpec(0.4f, 0.0014f, 0.2f), p.grain)
        assertEquals(VignetteSpec(0.35f, 0.9f, 0.6f, VignetteShape.OVAL), p.vignette)
        assertEquals(CenterGlowSpec(0f, 0.5f), p.centerGlow)
        assertEquals(BloomSpec(0f, 0.8f), p.bloom)
        assertEquals(DistortionSpec(0f, false), p.distortion)
        assertEquals(LightLeakSpec(0.3f, 0.35f, listOf(0, 1, 2, 3)), p.lightLeak)
        assertEquals(0.1f, p.dust)
        assertEquals(FrameId.NONE, p.defaultFrame)
        assertEquals(DateStampSpec(false, Rgb.STAMP_ORANGE), p.dateStamp)
    }

    @Test
    fun missingFields_getDefaults() {
        val log = Rec()
        val p = parse("""{"schemaVersion":1,"presets":[{"id":"x"}]}""", log).single()
        assertEquals(Preset("x"), p)
        assertEquals("x", p.displayNameRes)
        assertNull(p.lut)
        assertEquals(1f, p.lutStrength)
        assertEquals(0f, p.grain.amount)
        assertEquals(Rgb.STAMP_ORANGE, p.dateStamp.color)
        assertTrue(log.warns.isEmpty() && log.errors.isEmpty())
    }

    @Test
    fun outOfRange_isClamped_withWarning() {
        val log = Rec()
        val json = """
            {"schemaVersion":1,"presets":[{"id":"x","exposure":5,"saturation":-1,
             "grain":{"amount":2,"size":0.5},"vignette":{"radius":3},
             "lightLeak":{"variants":[0,7]}}]}
        """.trimIndent()
        val p = parse(json, log).single()
        assertEquals(2f, p.exposure)
        assertEquals(0f, p.saturation)
        assertEquals(1f, p.grain.amount)
        assertEquals(0.01f, p.grain.size)
        assertEquals(1.5f, p.vignette.radius)
        assertEquals(listOf(0), p.lightLeak.variants)
        assertEquals(log.warns.toString(), 6, log.warns.size)
        assertTrue(log.errors.isEmpty())
    }

    @Test
    fun badToneCurve_fallsBackToIdentity() {
        val log = Rec()
        val p = parse("""{"schemaVersion":1,"presets":[{"id":"x","tone":{"all":[[0,0],[0,1]],"r":[[0,0]]}}]}""", log).single()
        assertTrue(p.tone.isIdentity)
        assertEquals(2, log.warns.size)
    }

    @Test
    fun duplicateAndMissingId_skipped_withError() {
        val log = Rec()
        val presets = parse(
            """{"schemaVersion":1,"presets":[{"id":"a","fade":0.1},{"id":"a"},{"fade":0.2},{"id":"b"}]}""",
            log,
        )
        assertEquals(listOf("a", "b"), presets.map { it.id })
        assertEquals(0.1f, presets[0].fade)
        assertEquals(2, log.errors.size)
    }

    @Test
    fun malformedJson_givesIdentityPreset() {
        for (text in listOf("{not json", "", "[]", """{"schemaVersion":1}""", """{"presets":[]}""")) {
            val log = Rec()
            assertEquals(text, listOf(Preset.IDENTITY), parse(text, log))
            assertTrue(text, log.errors.isNotEmpty())
        }
    }
}
