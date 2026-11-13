package com.vintagelab.camera.video

import kotlin.math.max

/**
 * Maps camera frame timestamps to the shared recording clock. SurfaceTexture timestamps use the
 * monotonic clock (same base as [System.nanoTime]), so video PTS = frame timestamp - [epochNs].
 * Frames older than the epoch are dropped ([DROP]); output is strictly monotonic since OEMs may
 * repeat or briefly regress timestamps. If the very first frame is more than [MAX_SKEW_NS] away
 * from the epoch the sensor uses a different timebase, so the epoch is re-anchored on that frame.
 */
class PresentationTimestamp(val epochNs: Long = 0L, private val minimumStepNs: Long = 1L) {
    private var effectiveEpochNs = epochNs
    private var seenFirst = false
    private var lastNs = Long.MIN_VALUE

    /** Returns PTS in ns relative to the epoch, or [DROP] if the frame precedes the recording. */
    fun next(sourceTimestampNs: Long): Long {
        if (!seenFirst) {
            seenFirst = true
            if (kotlin.math.abs(sourceTimestampNs - epochNs) > MAX_SKEW_NS) effectiveEpochNs = sourceTimestampNs
        }
        val normalized = sourceTimestampNs - effectiveEpochNs
        if (normalized < 0L) return DROP
        val out = if (lastNs == Long.MIN_VALUE) normalized else max(normalized, lastNs + minimumStepNs)
        lastNs = out
        return out
    }

    companion object {
        const val DROP = -1L
        const val MAX_SKEW_NS = 5_000_000_000L
    }
}

/** Audio side of the shared recording clock (same epoch as [PresentationTimestamp]). */
class AudioPtsMapper(
    private val epochNs: Long,
    private val sampleRate: Int,
) {
    init { require(sampleRate > 0) }

    /** Fallback anchor: nanoTime when capture started, which is when frame 0 was captured. */
    private var startNs = Long.MIN_VALUE
    private var lastUs = Long.MIN_VALUE

    fun anchorStart(nanoTime: Long) { startNs = nanoTime }

    /**
     * PTS (us) of the first frame of a chunk. [framesBefore] = frames read previously; when the
     * driver supplied a timestamp ([tsFramePosition] captured at [tsNanoTime], monotonic) it wins.
     * Returns [DROP] when the chunk precedes the epoch. Monotonic otherwise.
     */
    fun ptsUs(framesBefore: Long, tsFramePosition: Long? = null, tsNanoTime: Long? = null): Long {
        val chunkNs = if (tsFramePosition != null && tsNanoTime != null) {
            tsNanoTime + (framesBefore - tsFramePosition) * 1_000_000_000L / sampleRate
        } else {
            check(startNs != Long.MIN_VALUE) { "anchorStart not called" }
            startNs + framesBefore * 1_000_000_000L / sampleRate
        }
        val us = (chunkNs - epochNs) / 1_000L
        if (chunkNs - epochNs < 0L) return DROP
        val out = if (lastUs == Long.MIN_VALUE) us else max(us, lastUs + 1)
        lastUs = out
        return out
    }

    companion object { const val DROP = -1L }
}

/**
 * Keeps the audio track from outlasting the video track (backlog B-002). An audio sample is released
 * only once video has reached its PTS; samples ahead of video are held. When video ends, held samples
 * up to the last video PTS plus one frame are released and the rest dropped. Not thread-safe: the
 * drain thread owns it.
 */
class AudioTailGate<T>(private val frameDurationUs: Long = 1_000_000L / VideoLimits.FRAME_RATE) {
    private val held = ArrayDeque<Pair<Long, T>>()
    private var lastVideoUs = Long.MIN_VALUE
    private var videoEnded = false

    /** Returns the samples (including [sample], if it may be written now) to write, in PTS order. */
    fun onAudio(ptsUs: Long, sample: T): List<T> {
        if (videoEnded) return if (ptsUs <= endLimitUs()) listOf(sample) else emptyList()
        held.addLast(ptsUs to sample)
        return releaseUpTo(lastVideoUs)
    }

    /** Call after writing a video sample; returns held audio that may now be written. */
    fun onVideo(ptsUs: Long): List<T> {
        if (ptsUs > lastVideoUs) lastVideoUs = ptsUs
        return releaseUpTo(lastVideoUs)
    }

    /** Video EOS: returns the held tail that fits within the video; the remainder is dropped. */
    fun onVideoEnd(): List<T> {
        videoEnded = true
        val out = releaseUpTo(endLimitUs())
        held.clear()
        return out
    }

    val heldCount: Int get() = held.size

    private fun endLimitUs(): Long = if (lastVideoUs == Long.MIN_VALUE) Long.MIN_VALUE else lastVideoUs + frameDurationUs

    private fun releaseUpTo(limitUs: Long): List<T> {
        if (held.isEmpty() || held.first().first > limitUs) return emptyList()
        val out = ArrayList<T>()
        while (held.isNotEmpty() && held.first().first <= limitUs) out += held.removeFirst().second
        return out
    }
}

object VideoLimits {
    const val MAX_DURATION_MS = 3L * 60L * 1000L
    const val FRAME_RATE = 30
    const val VIDEO_BIT_RATE = 12_000_000
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_BIT_RATE = 128_000
}

/** Staged resource ownership: everything registered is released in reverse order unless [commit]ted. */
class CleanupScope {
    private val releases = ArrayDeque<() -> Unit>()
    private var committed = false

    fun <T> add(release: (T) -> Unit, create: () -> T): T {
        val value = create()
        releases.addLast { release(value) }
        return value
    }

    fun commit() { committed = true }

    fun releaseAll() {
        if (committed) return
        while (releases.isNotEmpty()) runCatching { releases.removeLast()() }
    }
}

/**
 * Idempotent recording teardown: EGL detach first, then finalize (codec EOS/drain, audio release,
 * MediaStore row resolution) exactly once, however many times stop requests / detach callbacks arrive.
 */
class RecordingTeardown(
    private val detachEgl: (onDetached: () -> Unit) -> Unit,
    private val finalize: () -> Unit,
) {
    private enum class Phase { Active, Detaching, Finalized }
    private val phase = java.util.concurrent.atomic.AtomicReference(Phase.Active)

    /** First call starts the EGL detach; later calls are ignored. */
    fun request(): Boolean {
        if (!phase.compareAndSet(Phase.Active, Phase.Detaching)) return false
        try { detachEgl(::onDetached) } catch (t: Throwable) { onDetached() }
        return true
    }

    /** EGL detach callback, or a forced fallback. Finalizes once. */
    fun onDetached() {
        if (phase.getAndSet(Phase.Finalized) != Phase.Finalized) finalize()
    }

    val isFinalized: Boolean get() = phase.get() == Phase.Finalized
}
