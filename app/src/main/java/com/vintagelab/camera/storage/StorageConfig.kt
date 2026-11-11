package com.vintagelab.camera.storage

import android.os.Environment
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object StorageConfig {
    /** Save folder under Pictures/ (SPEC §6.6). Proposed — fixed before release (D1). */
    const val SAVE_FOLDER = "VintageLab"

    val RELATIVE_PATH: String = Environment.DIRECTORY_PICTURES + "/" + SAVE_FOLDER

    const val MIME_JPEG = "image/jpeg"
    const val JPEG_QUALITY = 92

    /** Quality of a stored negative source (SPEC 6.9). */
    const val NEGATIVE_JPEG_QUALITY = 95

    private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("'IMG_'yyyyMMdd_HHmmss_SSS'.jpg'")

    /** `IMG_yyyyMMdd_HHmmss_SSS.jpg` */
    fun fileName(t: LocalDateTime): String = NAME_FORMAT.format(t)
}
