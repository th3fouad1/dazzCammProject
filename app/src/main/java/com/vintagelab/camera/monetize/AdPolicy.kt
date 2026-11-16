package com.vintagelab.camera.monetize

/** SPEC 4.2: every ad knob, from Firebase Remote Config when it is set up, these defaults otherwise. */
data class AdConfig(
    val adsEnabled: Boolean = true,
    val showBanner: Boolean = true,
    /** Interstitial at most once every N navigation actions (gallery opens, editor saves). */
    val interstitialInterval: Int = 3,
    /** And never twice within this many seconds. */
    val interstitialMinSeconds: Int = 180,
    /** No interstitial during the user's first N app sessions. */
    val interstitialGraceSessions: Int = 1,
    /** One native ad card after every N gallery photos (0 = off). */
    val nativeEvery: Int = 15,
    val appOpenEnabled: Boolean = true,
    /** App Open shows on a warm start only after the app was in the background this long. */
    val appOpenMinBackgroundSeconds: Int = 30,
    /** And never twice within this many seconds. */
    val appOpenMinIntervalSeconds: Int = 240,
    val rewardUnlockHours: Int = 24,
    /** No rewarded ad to show (offline, no fill): unlock anyway rather than block the user. */
    val rewardFallbackGrant: Boolean = true,
    /** SPEC 4.4: share-the-app points. */
    val shareRewardsEnabled: Boolean = true,
    val pointsPerShare: Int = 10,
    /** Shares that earn points per local calendar day. */
    val shareDailyLimit: Int = 1,
    val pointsUnlockCost: Int = 10,
    val pointsUnlockHours: Int = 24,
) {
    companion object {
        const val KEY_ADS_ENABLED = "ads_enabled"
        const val KEY_SHOW_BANNER = "show_banner"
        const val KEY_INTERSTITIAL_INTERVAL = "interstitial_click_interval"
        const val KEY_INTERSTITIAL_MIN_SECONDS = "interstitial_min_seconds"
        const val KEY_INTERSTITIAL_GRACE_SESSIONS = "interstitial_grace_sessions"
        const val KEY_NATIVE_EVERY = "native_every"
        const val KEY_APP_OPEN_ENABLED = "app_open_enabled"
        const val KEY_APP_OPEN_MIN_BACKGROUND = "app_open_min_background_seconds"
        const val KEY_APP_OPEN_MIN_INTERVAL = "app_open_min_interval_seconds"
        const val KEY_REWARD_UNLOCK_HOURS = "reward_unlock_hours"
        const val KEY_REWARD_FALLBACK = "reward_fallback_grant"
        const val KEY_SHARE_REWARDS_ENABLED = "share_rewards_enabled"
        const val KEY_POINTS_PER_SHARE = "points_per_share"
        const val KEY_SHARE_DAILY_LIMIT = "share_daily_limit"
        const val KEY_POINTS_UNLOCK_COST = "points_unlock_cost"
        const val KEY_POINTS_UNLOCK_HOURS = "points_unlock_hours"

        fun defaultsMap(): Map<String, Any> = AdConfig().run {
            mapOf(
                KEY_ADS_ENABLED to adsEnabled, KEY_SHOW_BANNER to showBanner,
                KEY_INTERSTITIAL_INTERVAL to interstitialInterval.toLong(), KEY_INTERSTITIAL_MIN_SECONDS to interstitialMinSeconds.toLong(),
                KEY_INTERSTITIAL_GRACE_SESSIONS to interstitialGraceSessions.toLong(), KEY_NATIVE_EVERY to nativeEvery.toLong(),
                KEY_APP_OPEN_ENABLED to appOpenEnabled, KEY_APP_OPEN_MIN_BACKGROUND to appOpenMinBackgroundSeconds.toLong(),
                KEY_APP_OPEN_MIN_INTERVAL to appOpenMinIntervalSeconds.toLong(), KEY_REWARD_UNLOCK_HOURS to rewardUnlockHours.toLong(),
                KEY_REWARD_FALLBACK to rewardFallbackGrant,
                KEY_SHARE_REWARDS_ENABLED to shareRewardsEnabled, KEY_POINTS_PER_SHARE to pointsPerShare.toLong(),
                KEY_SHARE_DAILY_LIMIT to shareDailyLimit.toLong(), KEY_POINTS_UNLOCK_COST to pointsUnlockCost.toLong(),
                KEY_POINTS_UNLOCK_HOURS to pointsUnlockHours.toLong(),
            )
        }
    }
}

/** Interstitial capping: counts navigation actions, fires on every [AdConfig.interstitialInterval]th one, with a time floor. */
class InterstitialPacer {
    private var actions = 0
    private var lastShownMs = Long.MIN_VALUE / 2

    /** Records one action; true when an interstitial may show now. */
    fun onAction(cfg: AdConfig, nowMs: Long, session: Int): Boolean {
        if (session <= cfg.interstitialGraceSessions) return false
        actions++
        if (actions < cfg.interstitialInterval.coerceAtLeast(1)) return false
        if (nowMs - lastShownMs < cfg.interstitialMinSeconds * 1000L) return false
        return true
    }

    /** The ad really showed: restart the count and the time floor. */
    fun onShown(nowMs: Long) {
        actions = 0
        lastShownMs = nowMs
    }

    /** Any full-screen ad (rewarded, App Open) also resets the floor so ads never stack. */
    fun onOtherFullScreen(nowMs: Long) {
        lastShownMs = nowMs
    }
}

object AppOpenPolicy {
    /**
     * Warm start only. Never after the app's own activity (picker, share, billing, an ad), never while another
     * full-screen ad just showed, never for Pro.
     */
    fun shouldShow(
        cfg: AdConfig, pro: Boolean, consentReady: Boolean, warmStart: Boolean, returnedFromOwnActivity: Boolean,
        backgroundMs: Long, sinceLastFullScreenMs: Long,
    ): Boolean = cfg.adsEnabled && cfg.appOpenEnabled && !pro && consentReady && warmStart && !returnedFromOwnActivity &&
        backgroundMs >= cfg.appOpenMinBackgroundSeconds * 1000L && sinceLastFullScreenMs >= cfg.appOpenMinIntervalSeconds * 1000L
}

/** SPEC 4.1: a locked look takes pictures only with Pro or an unexpired rewarded unlock. */
object LookLocks {
    fun canShoot(locked: Boolean, pro: Boolean, unlockedUntilMs: Long?, nowMs: Long): Boolean =
        !locked || pro || (unlockedUntilMs != null && unlockedUntilMs > nowMs)
}

/** SPEC 4.4: points earned by sharing the app, spent on 24 h look unlocks. A share cannot be verified, so earning is capped per day. */
object SharePoints {
    data class Wallet(val points: Int = 0, val shareDay: Long = -1, val sharesToday: Int = 0)

    /** Shares that may still earn points on [today] (local epoch day). */
    fun sharesLeft(w: Wallet, cfg: AdConfig, today: Long): Int =
        if (!cfg.shareRewardsEnabled) 0 else (cfg.shareDailyLimit - if (w.shareDay == today) w.sharesToday else 0).coerceAtLeast(0)

    /** The wallet after one share on [today]; unchanged once today's limit is reached. */
    fun award(w: Wallet, cfg: AdConfig, today: Long): Wallet {
        if (sharesLeft(w, cfg, today) <= 0) return w
        val count = if (w.shareDay == today) w.sharesToday else 0
        return Wallet(w.points + cfg.pointsPerShare, today, count + 1)
    }

    fun canSpend(w: Wallet, cfg: AdConfig): Boolean = cfg.shareRewardsEnabled && w.points >= cfg.pointsUnlockCost
}

/** One row of the gallery grid: a photo (index into the photo list) or a native ad slot. */
sealed interface GridEntry {
    data class Photo(val index: Int) : GridEntry
    data class Ad(val slot: Int) : GridEntry
}

object GalleryAds {
    /** Photos with one ad after every [every] photos; no ad until the gallery has [every] photos, never one at the very end. */
    fun entries(photos: Int, every: Int, adsOn: Boolean): List<GridEntry> {
        if (!adsOn || every <= 0 || photos < every) return List(photos) { GridEntry.Photo(it) }
        val out = ArrayList<GridEntry>(photos + photos / every)
        var slot = 0
        for (i in 0 until photos) {
            out += GridEntry.Photo(i)
            if ((i + 1) % every == 0 && i + 1 < photos) out += GridEntry.Ad(slot++)
        }
        return out
    }

    /** The last photo index at or before grid position [pos] (for the load-more threshold). */
    fun photoIndexAt(entries: List<GridEntry>, pos: Int): Int {
        for (p in pos.coerceAtMost(entries.lastIndex) downTo 0) {
            val e = entries[p]
            if (e is GridEntry.Photo) return e.index
        }
        return 0
    }
}
