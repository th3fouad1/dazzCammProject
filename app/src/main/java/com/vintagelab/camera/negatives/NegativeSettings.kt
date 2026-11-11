package com.vintagelab.camera.negatives

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Sidecar of one negative (SPEC 6.9): everything needed to develop the stored source again.
 * Plain strings and numbers only, so the file stays readable across versions; [schemaVersion] gates loading.
 */
@Serializable
data class NegativeSettings(
    val schemaVersion: Int = SCHEMA_VERSION,
    val lookId: String,
    val seed: Long,
    val grainTime: Float = 0f,
    /** `FrameId.id` */
    val frame: String = "none",
    val dateStamp: Boolean = false,
    /** Local date-time of the date stamp, `yyyy-MM-dd'T'HH:mm:ss`. */
    val stampTime: String,
    /** `PrismMode.name` */
    val prism: String = "OFF",
    /** Effective `FlashGel.name` (after the "did a flash fire" rule). */
    val gel: String = "NONE",
    /** Editor adjustments on top of the look (EV, and temperature offset in preset units). */
    val exposureEv: Float = 0f,
    val temperature: Float = 0f,
    /** One of [MODE_NORMAL], [MODE_DOUBLE], [MODE_HALF], [MODE_GRID], [MODE_IMPORT]. */
    val captureMode: String = MODE_NORMAL,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** Copied camera EXIF tags (never GPS), reused when the negative is developed again. */
    val exif: Map<String, String> = emptyMap(),
    /** Double exposure: `blend.jpg` holds the developed first exposure. */
    val hasBlend: Boolean = false,
    val createdAtMs: Long = 0L,
) {
    /** Half-frame and grid sources are already composed: no forced-ratio crop, no ratio-forcing frames. */
    val composite: Boolean get() = captureMode == MODE_HALF || captureMode == MODE_GRID

    companion object {
        const val SCHEMA_VERSION = 1
        const val MODE_NORMAL = "normal"
        const val MODE_DOUBLE = "double"
        const val MODE_HALF = "half"
        const val MODE_GRID = "grid"
        const val MODE_IMPORT = "import"

        fun formatStamp(t: LocalDateTime): String = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(t.truncatedTo(ChronoUnit.SECONDS))

        fun parseStamp(s: String): LocalDateTime? = try { LocalDateTime.parse(s) } catch (e: DateTimeParseException) { null }
    }
}

object NegativeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(s: NegativeSettings): String = json.encodeToString(NegativeSettings.serializer(), s)

    /** Null when the text is malformed, lacks a required field, or was written by a newer schema. */
    fun decode(text: String): NegativeSettings? = try {
        json.decodeFromString(NegativeSettings.serializer(), text).takeIf { it.schemaVersion in 1..NegativeSettings.SCHEMA_VERSION }
    } catch (e: Exception) {
        null
    }
}
