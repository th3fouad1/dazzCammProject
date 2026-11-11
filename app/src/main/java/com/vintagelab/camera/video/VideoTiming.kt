package com.vintagelab.camera.video

import kotlin.math.max

/**
 * Converts camera timestamps to a zero-based, strictly monotonic encoder clock.
 * SurfaceTexture timestamps use the monotonic clock but OEMs may repeat or briefly regress them.
 */
class PresentationTimestamp(private val minimumStepNs: Long = 1L) {
    private var sourceBaseNs = Long.MIN_VALUE
    private var lastNs = Long.MIN_VALUE

    fun next(sourceTimestampNs: Long): Long {
        val source = max(0L, sourceTimestampNs)
        if (sourceBaseNs == Long.MIN_VALUE) sourceBaseNs = source
        val normalized = max(0L, source - sourceBaseNs)
        val out = if (lastNs == Long.MIN_VALUE) normalized else max(normalized, lastNs + minimumStepNs)
        lastNs = out
        return out
    }
}

/** Shared A/V recording clock. Audio is sample-count based; video uses normalized camera time. */
class AvClockAlignment(
    val sampleRate: Int,
    private val maxLeadUs: Long = 100_000L,
) {
    init { require(sampleRate > 0) }

    fun audioPtsUs(framesWritten: Long): Long = max(0L, framesWritten) * 1_000_000L / sampleRate

    /** Prevents a late first video frame from putting audio implausibly far ahead. */
    fun alignAudioToVideo(audioPtsUs: Long, firstVideoPtsUs: Long?): Long =
        if (firstVideoPtsUs == null) audioPtsUs else minOf(audioPtsUs, firstVideoPtsUs + maxLeadUs)
}

object VideoLimits {
    const val MAX_DURATION_MS = 3L * 60L * 1000L
    const val FRAME_RATE = 30
    const val VIDEO_BIT_RATE = 12_000_000
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_BIT_RATE = 128_000
}
