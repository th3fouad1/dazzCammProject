package com.vintagelab.camera.looks

import java.io.File

/** Locates app assets from a JVM unit test (working dir is the module dir under Gradle). */
object TestAssets {
    private val root: File = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }

    fun file(path: String): File = File(root, path)

    fun presetsText(): String = file("presets/presets.json").readText()

    fun presets(log: PresetLog = PresetLog.Silent): List<Preset> = PresetParser.parse(presetsText(), log)
}
