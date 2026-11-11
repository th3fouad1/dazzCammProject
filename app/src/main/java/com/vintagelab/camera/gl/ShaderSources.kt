package com.vintagelab.camera.gl

/**
 * Pure-Kotlin shader assembly (SPEC §6.3): line 1 `#version`, then the variant block, then the
 * body (effect files concatenated before the pass file). Unit-tested without a GL context.
 */
object ShaderSources {
    enum class Lang(val versionLine: String) {
        ESSL3("#version 300 es"),
        ESSL1("#version 100"),
    }

    enum class Pass(val parts: List<String>, val oes: Boolean = false) {
        PASS0_OES(listOf("pass0_oes.glsl"), oes = true),
        PASS0_2D(listOf("pass0_2d.glsl")),
        GEOMETRY(listOf("effects/common.glsl", "effects/distortion.glsl", "geometry.glsl")),
        PYRAMID(listOf("pyramid.glsl")),
        COMBINE(
            listOf(
                "effects/common.glsl",
                "effects/color.glsl",
                "effects/softbloom.glsl",
                "effects/glow.glsl",
                "effects/leak.glsl",
                "effects/vignette.glsl",
                "effects/dust.glsl",
                "effects/grain.glsl",
                "effects/video.glsl",
                "combine.glsl",
            ),
        ),
        OVERLAY(listOf("overlay.glsl")),
        COPY(listOf("copy.glsl")),
    }

    const val VERTEX = "fullscreen_vert.glsl"
    const val DIR = "shaders/"

    fun vertexVariant(lang: Lang): String = when (lang) {
        Lang.ESSL3 -> "#define ATTR in\n#define VARY_OUT out\n"
        Lang.ESSL1 -> "#define ATTR attribute\n#define VARY_OUT varying\n"
    }

    fun fragmentVariant(lang: Lang, oes: Boolean): String = buildString {
        when (lang) {
            Lang.ESSL3 -> {
                if (oes) append("#extension GL_OES_EGL_image_external_essl3 : require\n")
                append("precision highp float;\nprecision highp int;\n")
                append("precision highp sampler2D;\nprecision highp sampler3D;\n")
                append("#define IN in\n#define TEX2D texture\n")
                if (oes) append("#define SAMPLER_OES samplerExternalOES\n#define TEX_OES texture\n")
                append("out vec4 fragColor;\n#define FRAG_COLOR fragColor\n")
            }
            Lang.ESSL1 -> {
                if (oes) append("#extension GL_OES_EGL_image_external : require\n")
                append("precision mediump float;\n")
                append("#define IN varying\n#define TEX2D texture2D\n#define FRAG_COLOR gl_FragColor\n")
                if (oes) append("#define SAMPLER_OES samplerExternalOES\n#define TEX_OES texture2D\n")
            }
        }
    }

    fun assemble(versionLine: String, variant: String, bodies: List<String>): String =
        buildString {
            append(versionLine).append('\n')
            append(variant)
            for (b in bodies) append(b).append('\n')
        }

    fun vertex(lang: Lang, read: (String) -> String): String =
        assemble(lang.versionLine, vertexVariant(lang), listOf(read(DIR + VERTEX)))

    fun fragment(pass: Pass, lang: Lang, read: (String) -> String): String {
        require(lang == Lang.ESSL3 || pass == Pass.PASS0_OES) { "only Pass 0 OES has an ESSL 1.00 variant" }
        return assemble(lang.versionLine, fragmentVariant(lang, pass.oes), pass.parts.map { read(DIR + it) })
    }
}
