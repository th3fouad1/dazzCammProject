package com.vintagelab.camera.capture

import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.FileDescriptor
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Output EXIF (SPEC §4): copy capture metadata from the camera JPEG, set Orientation=1 (pixels
 * are rendered upright) and Software=app_name. GPS is never written.
 */
object ExifWriter {
    val COPIED_TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
    )

    private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** Reads the tags to copy from the in-memory camera JPEG (missing tags are skipped). */
    fun readSource(jpeg: ByteArray): Map<String, String> = try {
        val exif = ExifInterface(ByteArrayInputStream(jpeg))
        COPIED_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
    } catch (e: java.io.IOException) {
        emptyMap()
    }

    /** Pure: the full attribute set to write. */
    fun plan(source: Map<String, String>, software: String, captureTime: LocalDateTime): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (tag in COPIED_TAGS) source[tag]?.let { out[tag] = it }
        if (ExifInterface.TAG_DATETIME_ORIGINAL !in out) out[ExifInterface.TAG_DATETIME_ORIGINAL] = EXIF_DATE.format(captureTime)
        out[ExifInterface.TAG_ORIENTATION] = ExifInterface.ORIENTATION_NORMAL.toString()
        out[ExifInterface.TAG_SOFTWARE] = software
        return out
    }

    /** Writes [attrs] into the JPEG behind a seekable read/write descriptor. */
    fun write(fd: FileDescriptor, attrs: Map<String, String>) {
        // The JPEG was just written through this descriptor: rewind so ExifInterface parses from byte 0.
        Os.lseek(fd, 0L, OsConstants.SEEK_SET)
        val exif = ExifInterface(fd)
        for ((k, v) in attrs) exif.setAttribute(k, v)
        exif.saveAttributes()
    }
}
