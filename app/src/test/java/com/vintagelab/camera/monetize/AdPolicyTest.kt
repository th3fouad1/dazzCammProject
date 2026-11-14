package com.vintagelab.camera.monetize

import com.vintagelab.camera.looks.PresetParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdPolicyTest {
    private val cfg = AdConfig(interstitialInterval = 3, interstitialMinSeconds = 180, interstitialGraceSessions = 1)

    @Test fun interstitialWaitsForTheIntervalAndTheTimeFloor() {
        val p = InterstitialPacer()
        val t = 1_000_000L
        assertFalse(p.onAction(cfg, t, session = 2))
        assertFalse(p.onAction(cfg, t, session = 2))
        assertTrue(p.onAction(cfg, t, session = 2)) // 3rd action
        p.onShown(t)
        repeat(2) { assertFalse(p.onAction(cfg, t + 10_000, session = 2)) }
        assertFalse(p.onAction(cfg, t + 10_000, session = 2)) // 3rd again, but only 10 s later
        assertTrue(p.onAction(cfg, t + 181_000, session = 2))
    }

    @Test fun noInterstitialInTheFirstSession() {
        val p = InterstitialPacer()
        repeat(10) { assertFalse(p.onAction(cfg, 10_000_000L, session = 1)) }
    }

    @Test fun otherFullScreenAdsResetTheFloor() {
        val p = InterstitialPacer()
        p.onOtherFullScreen(5_000_000L)
        repeat(5) { assertFalse(p.onAction(cfg, 5_060_000L, session = 3)) }
        assertTrue(p.onAction(cfg, 5_200_000L, session = 3))
    }

    @Test fun appOpenOnlyOnARealWarmStart() {
        val c = AdConfig()
        fun show(pro: Boolean = false, own: Boolean = false, bg: Long = 60_000, since: Long = 600_000, warm: Boolean = true, consent: Boolean = true) =
            AppOpenPolicy.shouldShow(c, pro, consent, warm, own, bg, since)
        assertTrue(show())
        assertFalse(show(pro = true))
        assertFalse(show(own = true)) // back from the picker, share sheet, Play or an ad
        assertFalse(show(bg = 5_000)) // a quick app switch
        assertFalse(show(since = 30_000)) // another full-screen ad just closed
        assertFalse(show(warm = false))
        assertFalse(show(consent = false))
        assertFalse(AppOpenPolicy.shouldShow(c.copy(appOpenEnabled = false), false, true, true, false, 60_000, 600_000))
    }

    @Test fun lockedLooksNeedProOrAnUnexpiredUnlock() {
        assertTrue(LookLocks.canShoot(locked = false, pro = false, unlockedUntilMs = null, nowMs = 0))
        assertFalse(LookLocks.canShoot(locked = true, pro = false, unlockedUntilMs = null, nowMs = 0))
        assertTrue(LookLocks.canShoot(locked = true, pro = true, unlockedUntilMs = null, nowMs = 0))
        assertTrue(LookLocks.canShoot(locked = true, pro = false, unlockedUntilMs = 100, nowMs = 99))
        assertFalse(LookLocks.canShoot(locked = true, pro = false, unlockedUntilMs = 100, nowMs = 100))
    }

    @Test fun galleryAdsSitBetweenPhotosOnly() {
        assertEquals(10, GalleryAds.entries(10, 15, adsOn = true).size) // fewer photos than one block: no ad
        val e = GalleryAds.entries(31, 15, adsOn = true)
        assertEquals(33, e.size)
        assertEquals(GridEntry.Ad(0), e[15])
        assertEquals(GridEntry.Ad(1), e[31])
        assertEquals(GridEntry.Photo(30), e.last())
        assertEquals(15, GalleryAds.entries(15, 15, adsOn = true).size) // never an ad at the very end
        assertEquals(31, GalleryAds.entries(31, 15, adsOn = false).size)
        assertEquals(14, GalleryAds.photoIndexAt(e, 15)) // the ad row maps to the photo before it
        assertEquals(15, GalleryAds.photoIndexAt(e, 16))
    }

    @Test fun unlocksSurviveTheStoreFormat() {
        val m = mapOf("ccd2" to 123L, "pano" to 456L)
        assertEquals(m, EntitlementStore.decode(EntitlementStore.encode(m)))
        assertEquals(emptyMap<String, Long>(), EntitlementStore.decode(null))
        assertEquals(mapOf("a" to 1L), EntitlementStore.decode("a=1;bad;=3;b=x"))
    }

    /** SPEC 4.1: the owner's split, 8 free (6 photo + 2 video) and 10 locked. */
    @Test fun shippedLockSplit() {
        val root = System.getProperty("repoRoot") ?: "."
        val text = File(root, "app/src/main/assets/presets/presets.json").readText()
        val presets = PresetParser.parse(text)
        val locked = presets.filter { it.locked }.map { it.id }.toSet()
        assertEquals(setOf("ccd2", "slide", "premium", "toy120", "tlr", "square", "fine_mono", "pano", "dv", "bw16"), locked)
        assertFalse("the default photo look must be free", presets.first().locked)
        assertTrue(presets.filter { !it.locked }.map { it.id }.containsAll(listOf("classic35", "vhs")))
    }
}
