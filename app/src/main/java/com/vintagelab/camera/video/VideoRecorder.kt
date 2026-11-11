package com.vintagelab.camera.video

import android.content.ContentResolver
import android.content.ContentValues
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.vintagelab.camera.looks.VideoAspect
import com.vintagelab.camera.storage.StorageConfig
import java.io.IOException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class VideoEncoderTarget(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val timestamps: PresentationTimestamp,
    val isAlive: () -> Boolean,
)

data class VideoStartResult(val recording: ActiveVideoRecording, val used720pFallback: Boolean)

/** H.264 surface encoder + optional AudioRecord/AAC + MediaMuxer, writing a pending MediaStore row. */
class VideoRecorder(
    private val resolver: ContentResolver,
    private val ownPackage: String,
) {
    fun start(aspect: VideoAspect, portrait: Boolean, withAudio: Boolean): Result<VideoStartResult> = runCatching {
        val video = createVideoEncoder(aspect, portrait)
        var audio: AudioPipe? = null
        if (withAudio) {
            audio = runCatching { createAudioPipe() }.onFailure { Log.w(TAG, "audio unavailable; recording silent", it) }.getOrNull()
        }
        val pending = createPending(LocalDateTime.now())
        try {
            val active = ActiveVideoRecording(resolver, pending, video, audio)
            active.start()
            VideoStartResult(active, video.fallback)
        } catch (t: Throwable) {
            runCatching { audio?.release() }
            runCatching { video.release() }
            abortPending(pending)
            throw t
        }
    }

    fun cleanupPending() {
        try {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.Video.Media.IS_PENDING}=1 AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            val args = arrayOf(VIDEO_RELATIVE_PATH + "%", ownPackage)
            val query = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                resolver.query(collection, arrayOf(MediaStore.Video.Media._ID), bundle, null)
            } else {
                @Suppress("DEPRECATION")
                resolver.query(MediaStore.setIncludePending(collection), arrayOf(MediaStore.Video.Media._ID), selection, args, null)
            }
            query?.use { c ->
                while (c.moveToNext()) resolver.delete(Uri.withAppendedPath(collection, c.getLong(0).toString()), null, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "pending video cleanup failed", e)
        }
    }

    internal data class Pending(val uri: Uri, val pfd: ParcelFileDescriptor)

    private fun createPending(time: LocalDateTime): Pending {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, VIDEO_NAME.format(time))
            put(MediaStore.Video.Media.MIME_TYPE, MIME_VIDEO)
            put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_RELATIVE_PATH)
            put(MediaStore.Video.Media.DATE_TAKEN, time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore video insert failed")
        return try {
            Pending(uri, resolver.openFileDescriptor(uri, "rw") ?: throw IOException("cannot open $uri"))
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun abortPending(pending: Pending) {
        runCatching { pending.pfd.close() }
        runCatching { resolver.delete(pending.uri, null, null) }
    }

    internal data class VideoPipe(
        val codec: MediaCodec,
        val surface: Surface,
        val width: Int,
        val height: Int,
        val fallback: Boolean,
    ) {
        fun release() {
            runCatching { surface.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun createVideoEncoder(aspect: VideoAspect, portrait: Boolean): VideoPipe {
        val sizes = when (aspect) {
            VideoAspect.R4_3 -> listOf(1440 to 1080, 960 to 720)
            VideoAspect.R16_9 -> listOf(1920 to 1080, 1280 to 720)
        }.map { (long, short) -> if (portrait) short to long else long to short }
        var last: Throwable? = null
        for ((index, size) in sizes.withIndex()) {
            val codec = MediaCodec.createEncoderByType(MIME_AVC)
            try {
                val format = MediaFormat.createVideoFormat(MIME_AVC, size.first, size.second).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (index == 0) VideoLimits.VIDEO_BIT_RATE else 7_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, VideoLimits.FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                return VideoPipe(codec, surface, size.first, size.second, index > 0)
            } catch (t: Throwable) {
                last = t
                runCatching { codec.release() }
            }
        }
        throw IOException("H.264 encoder rejected 1080p and 720p", last)
    }

    internal data class AudioPipe(val codec: MediaCodec, val record: AudioRecord, val bufferSize: Int) {
        fun release() {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    @Suppress("MissingPermission")
    private fun createAudioPipe(): AudioPipe {
        val min = AudioRecord.getMinBufferSize(VideoLimits.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "AudioRecord buffer query failed: $min" }
        val buffer = maxOf(min * 2, 8192)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(VideoLimits.AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(buffer)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
        val codec = MediaCodec.createEncoderByType(MIME_AAC)
        try {
            val format = MediaFormat.createAudioFormat(MIME_AAC, VideoLimits.AUDIO_SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, VideoLimits.AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, buffer)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return AudioPipe(codec, record, buffer)
        } catch (t: Throwable) {
            record.release()
            codec.release()
            throw t
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
        const val MIME_AVC = "video/avc"
        const val MIME_AAC = "audio/mp4a-latm"
        const val MIME_VIDEO = "video/mp4"
        val VIDEO_RELATIVE_PATH: String = Environment.DIRECTORY_MOVIES + "/" + StorageConfig.SAVE_FOLDER
        private val VIDEO_NAME = DateTimeFormatter.ofPattern("'VID_'yyyyMMdd_HHmmss_SSS'.mp4'")
    }

    /** Owns the codecs and pending row from start until [stop]. */
    class ActiveVideoRecording internal constructor(
        private val resolver: ContentResolver,
        private val pending: Pending,
        private val video: VideoPipe,
        private val audio: AudioPipe?,
    ) {
        private val running = AtomicBoolean(true)
        private val audioRunning = AtomicBoolean(audio != null)
        private val failure = AtomicReference<Throwable?>(null)
        private val result = AtomicReference<Result<Uri>?>(null)
        private val finished = CountDownLatch(1)
        private lateinit var drainThread: Thread
        private var audioThread: Thread? = null

        val target = VideoEncoderTarget(video.surface, video.width, video.height, PresentationTimestamp()) { running.get() && failure.get() == null }
        val hasAudio: Boolean get() = audio != null
        val used720pFallback: Boolean get() = video.fallback
        fun failureOrNull(): Throwable? = failure.get()

        internal fun start() {
            video.codec.start()
            audio?.codec?.start()
            drainThread = Thread(::drain, "video-drain").apply { start() }
            if (audio != null) audioThread = Thread({ feedAudio(audio) }, "audio-record").apply { start() }
        }

        /** Called only after the GL thread detached the encoder Surface. */
        fun stop(): Result<Uri> {
            if (running.getAndSet(false)) {
                audioRunning.set(false)
                runCatching { audio?.record?.stop() }
                runCatching { audioThread?.join(2_000) }
                runCatching { video.codec.signalEndOfInputStream() }.onFailure { failure.compareAndSet(null, it) }
            }
            if (!finished.await(12, TimeUnit.SECONDS)) {
                failure.compareAndSet(null, IOException("encoder drain timed out"))
                runCatching { drainThread.interrupt() }
            }
            return result.get() ?: Result.failure(failure.get() ?: IOException("recording did not finish"))
        }

        private fun feedAudio(pipe: AudioPipe) {
            val clock = AvClockAlignment(VideoLimits.AUDIO_SAMPLE_RATE)
            var frames = 0L
            try {
                pipe.record.startRecording()
                while (audioRunning.get()) {
                    val index = pipe.codec.dequeueInputBuffer(20_000)
                    if (index < 0) continue
                    val input = pipe.codec.getInputBuffer(index) ?: continue
                    input.clear()
                    val count = pipe.record.read(input, minOf(input.capacity(), pipe.bufferSize), AudioRecord.READ_BLOCKING)
                    if (count <= 0) continue
                    pipe.codec.queueInputBuffer(index, 0, count, clock.audioPtsUs(frames), 0)
                    frames += count / 2L
                }
            } catch (t: Throwable) {
                if (running.get()) failure.compareAndSet(null, t)
            } finally {
                queueAudioEos(pipe.codec, clock.audioPtsUs(frames))
            }
        }

        private fun queueAudioEos(codec: MediaCodec, ptsUs: Long) {
            repeat(50) {
                val index = runCatching { codec.dequeueInputBuffer(20_000) }.getOrDefault(-1)
                if (index >= 0) {
                    runCatching { codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                    return
                }
            }
            failure.compareAndSet(null, IOException("could not queue audio EOS"))
        }

        private data class Sample(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

        private fun drain() {
            var muxer: MediaMuxer? = null
            var muxerStarted = false
            var videoTrack = -1
            var audioTrack = -1
            var videoEos = false
            var audioEos = audio == null
            val pendingSamples = ArrayList<Sample>()
            var wroteVideo = false
            try {
                muxer = MediaMuxer(pending.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(0) }
                val vi = MediaCodec.BufferInfo()
                val ai = MediaCodec.BufferInfo()
                while (!videoEos || !audioEos) {
                    var progressed = false
                    fun drainCodec(codec: MediaCodec, info: MediaCodec.BufferInfo, trackKind: Int): Boolean {
                        val index = codec.dequeueOutputBuffer(info, 5_000)
                        when {
                            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (trackKind == TRACK_VIDEO) videoTrack = muxer!!.addTrack(codec.outputFormat)
                                else audioTrack = muxer!!.addTrack(codec.outputFormat)
                                return true
                            }
                            index >= 0 -> {
                                val buffer = codec.getOutputBuffer(index)
                                val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (!codecConfig && info.size > 0 && buffer != null) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    val bytes = ByteArray(info.size)
                                    buffer.get(bytes)
                                    val copy = MediaCodec.BufferInfo().apply { set(0, bytes.size, info.presentationTimeUs, info.flags) }
                                    val track = if (trackKind == TRACK_VIDEO) videoTrack else audioTrack
                                    if (muxerStarted) {
                                        muxer!!.writeSampleData(track, ByteBuffer.wrap(bytes), copy)
                                        if (trackKind == TRACK_VIDEO) wroteVideo = true
                                    } else pendingSamples += Sample(trackKind, bytes, copy)
                                }
                                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(index, false)
                                if (eos) {
                                    if (trackKind == TRACK_VIDEO) videoEos = true else audioEos = true
                                }
                                return true
                            }
                            else -> return false
                        }
                    }
                    progressed = drainCodec(video.codec, vi, TRACK_VIDEO) || progressed
                    if (audio != null) progressed = drainCodec(audio.codec, ai, TRACK_AUDIO) || progressed
                    if (!muxerStarted && videoTrack >= 0 && (audio == null || audioTrack >= 0)) {
                        muxer.start()
                        muxerStarted = true
                        for (sample in pendingSamples) {
                            val track = if (sample.track == TRACK_VIDEO) videoTrack else audioTrack
                            muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), sample.info)
                            if (sample.track == TRACK_VIDEO) wroteVideo = true
                        }
                        pendingSamples.clear()
                    }
                    if (!progressed) Thread.sleep(2)
                    failure.get()?.let { if (!running.get()) throw it }
                }
                check(muxerStarted && wroteVideo) { "no encoded video samples" }
                muxer.stop()
                pending.pfd.close()
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                check(resolver.update(pending.uri, done, null, null) == 1) { "clearing video IS_PENDING failed" }
                result.set(Result.success(pending.uri))
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
                if (muxerStarted) runCatching { muxer?.stop() }
                runCatching { pending.pfd.close() }
                runCatching { resolver.delete(pending.uri, null, null) }
                result.set(Result.failure(failure.get() ?: t))
            } finally {
                runCatching { muxer?.release() }
                video.release()
                audio?.release()
                finished.countDown()
            }
        }

        private companion object {
            const val TRACK_VIDEO = 0
            const val TRACK_AUDIO = 1
        }
    }
}

typealias ActiveVideoRecording = VideoRecorder.ActiveVideoRecording
