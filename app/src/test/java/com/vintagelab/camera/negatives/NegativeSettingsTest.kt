package com.vintagelab.camera.negatives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NegativeSettingsTest {
    private val sample = NegativeSettings(
        lookId = "classic35", seed = -1234567890123L, grainTime = 3.25f, frame = "instant_white", dateStamp = true,
        stampTime = "2026-11-11T14:03:09", prism = "TRI", gel = "WARM", exposureEv = -0.5f, temperature = 0.25f,
        captureMode = NegativeSettings.MODE_HALF, sourceWidth = 2400, sourceHeight = 1800,
        exif = mapOf("Make" to "Acme", "DateTimeOriginal" to "2026:11:11 14:03:09"), hasBlend = false, createdAtMs = 42L,
    )

    @Test fun roundTrip() {
        assertEquals(sample, NegativeCodec.decode(NegativeCodec.encode(sample)))
    }

    @Test fun schemaVersionIsWritten() {
        val text = NegativeCodec.encode(sample)
        assertTrue(text, text.contains("\"schemaVersion\":${NegativeSettings.SCHEMA_VERSION}"))
    }

    @Test fun newerSchemaIsRejected() {
        val text = NegativeCodec.encode(sample.copy(schemaVersion = NegativeSettings.SCHEMA_VERSION + 1))
        assertNull(NegativeCodec.decode(text))
        assertNull(NegativeCodec.decode(NegativeCodec.encode(sample.copy(schemaVersion = 0))))
    }

    @Test fun unknownKeysAndMissingOptionalFieldsAreTolerated() {
        val min = "{\"schemaVersion\":1,\"lookId\":\"ccd\",\"seed\":7,\"stampTime\":\"2026-01-02T03:04:05\",\"future\":\"x\"}"
        val s = NegativeCodec.decode(min)
        assertNotNull(s)
        assertEquals("none", s!!.frame)
        assertEquals(NegativeSettings.MODE_NORMAL, s.captureMode)
        assertEquals(0f, s.exposureEv, 0f)
        assertFalse(s.composite)
    }

    @Test fun malformedOrIncompleteIsNull() {
        assertNull(NegativeCodec.decode("not json"))
        assertNull(NegativeCodec.decode("{\"schemaVersion\":1,\"seed\":7}"))
        assertNull(NegativeCodec.decode(""))
    }

    @Test fun compositeModes() {
        assertTrue(sample.composite)
        assertTrue(sample.copy(captureMode = NegativeSettings.MODE_GRID).composite)
        assertFalse(sample.copy(captureMode = NegativeSettings.MODE_DOUBLE).composite)
        assertFalse(sample.copy(captureMode = NegativeSettings.MODE_IMPORT).composite)
    }
}
