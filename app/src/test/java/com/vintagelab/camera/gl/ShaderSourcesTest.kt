package com.vintagelab.camera.gl

import com.vintagelab.camera.looks.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShaderSourcesTest {
    private val read: (String) -> String = { TestAssets.file(it).readText() }

    /** Every uniform of SPEC §7.2 with its declared type. */
    private val contract = mapOf(
        "uCropRect" to "vec4", "uShortSide" to "float", "uOutSize" to "vec2", "uLookLut" to "sampler3D",
        "uLutStrength" to "float", "uGrain" to "vec3", "uGrainTime" to "float", "uVignette" to "vec4",
        "uCenterGlow" to "vec2", "uSoftFocus" to "float", "uBloom" to "vec2", "uSharpen" to "float",
        "uDistortion" to "vec2", "uPrism" to "vec2", "uLeak" to "vec4", "uLeakPos" to "vec2", "uDust" to "float", "uSeed" to "float",
        "uFrame" to "int", "uStampColor" to "vec4", "uStampOn" to "float", "uStampDigits" to "vec4",
    )

    private fun allFragments(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in ShaderSources.Pass.entries) out["${p.name}.es3.frag"] = ShaderSources.fragment(p, ShaderSources.Lang.ESSL3, read)
        out["PASS0_OES.es1.frag"] = ShaderSources.fragment(ShaderSources.Pass.PASS0_OES, ShaderSources.Lang.ESSL1, read)
        return out
    }

    @Test
    fun everyContractUniformIsDeclared() {
        val all = allFragments().values.joinToString("\n")
        for ((name, type) in contract) {
            val re = Regex("""uniform\s+(?:highp\s+|mediump\s+|lowp\s+)?$type\s+$name\b""")
            assertTrue("uniform $type $name missing from shaders", re.containsMatchIn(all))
        }
    }

    @Test
    fun versionLineIsFirst_andVariantsDefineMacros() {
        for ((name, src) in allFragments()) {
            val first = src.lineSequence().first()
            assertTrue(name, first == "#version 300 es" || first == "#version 100")
            assertTrue(name, "FRAG_COLOR" in src)
        }
        val oes3 = ShaderSources.fragment(ShaderSources.Pass.PASS0_OES, ShaderSources.Lang.ESSL3, read)
        assertEquals("#extension GL_OES_EGL_image_external_essl3 : require", oes3.lines()[1])
        val oes1 = ShaderSources.fragment(ShaderSources.Pass.PASS0_OES, ShaderSources.Lang.ESSL1, read)
        assertEquals("#extension GL_OES_EGL_image_external : require", oes1.lines()[1])
        assertTrue(ShaderSources.vertex(ShaderSources.Lang.ESSL1, read).startsWith("#version 100\n#define ATTR attribute"))
    }

    @Test
    fun effectFilesAreAllUsed() {
        val used = ShaderSources.Pass.entries.flatMap { it.parts }.toSet()
        val files = TestAssets.file("shaders/effects").listFiles()!!.map { "effects/" + it.name }
        for (f in files) assertTrue("$f not referenced by any pass", f in used)
    }

    /** Writes assembled sources to build/shaders for offline validation (glslc / glslangValidator). */
    @Test
    fun dumpAssembled() {
        val dir = File("build/shaders").apply { mkdirs() }
        allFragments().forEach { (n, s) -> File(dir, n).writeText(s) }
        File(dir, "vert.es3.vert").writeText(ShaderSources.vertex(ShaderSources.Lang.ESSL3, read))
        File(dir, "vert.es1.vert").writeText(ShaderSources.vertex(ShaderSources.Lang.ESSL1, read))
    }
}
