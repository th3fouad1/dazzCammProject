package com.vintagelab.camera.monetize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePointsTest {
    private val cfg = AdConfig(pointsPerShare = 10, shareDailyLimit = 1, pointsUnlockCost = 10)
    private val empty = SharePoints.Wallet()

    @Test fun oneRewardedSharePerDay() {
        val a = SharePoints.award(empty, cfg, today = 100)
        assertEquals(10, a.points)
        assertEquals(0, SharePoints.sharesLeft(a, cfg, 100))
        assertEquals(a, SharePoints.award(a, cfg, 100)) // second share the same day earns nothing
        val b = SharePoints.award(a, cfg, 101)
        assertEquals(20, b.points)
        assertEquals(1, b.sharesToday)
    }

    @Test fun limitCountsPerDay() {
        val c = cfg.copy(shareDailyLimit = 2)
        var w = empty
        repeat(5) { w = SharePoints.award(w, c, 7) }
        assertEquals(20, w.points)
    }

    @Test fun spendingNeedsTheFullCost() {
        assertFalse(SharePoints.canSpend(empty.copy(points = 9), cfg))
        assertTrue(SharePoints.canSpend(empty.copy(points = 10), cfg))
    }

    @Test fun killSwitchStopsEarningAndSpending() {
        val off = cfg.copy(shareRewardsEnabled = false)
        assertEquals(empty, SharePoints.award(empty, off, 1))
        assertFalse(SharePoints.canSpend(empty.copy(points = 50), off))
    }

    @Test fun remoteConfigDefaultsCoverTheNewKeys() {
        val m = AdConfig.defaultsMap()
        for (k in listOf(AdConfig.KEY_SHARE_REWARDS_ENABLED, AdConfig.KEY_POINTS_PER_SHARE, AdConfig.KEY_SHARE_DAILY_LIMIT,
            AdConfig.KEY_POINTS_UNLOCK_COST, AdConfig.KEY_POINTS_UNLOCK_HOURS)) assertTrue(k, k in m)
        assertEquals(24L, m[AdConfig.KEY_POINTS_UNLOCK_HOURS])
    }
}
