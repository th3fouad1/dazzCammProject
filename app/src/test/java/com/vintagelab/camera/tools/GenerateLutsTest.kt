package com.vintagelab.camera.tools

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Generator entry point, skipped unless `-PgenerateLuts` is passed (see HaldClutConverter). */
class GenerateLutsTest {
    @Test
    fun generateLuts() {
        assumeTrue(System.getProperty("generateLuts") == "true")
        val root = File(System.getProperty("repoRoot") ?: error("repoRoot not set"))
        val ids = HaldClutConverter.convertDirectory(
            File(root, "third_party/t3mujinpack/haldcluts"),
            File(root, "app/src/main/assets/luts"),
        )
        println("generated LUTs: $ids")
    }
}
