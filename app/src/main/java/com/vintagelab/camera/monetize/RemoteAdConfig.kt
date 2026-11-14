package com.vintagelab.camera.monetize

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SPEC 4.2: ad knobs from Firebase Remote Config. Without a Firebase project (no google-services.json)
 * the app keeps [AdConfig]'s in-code defaults.
 */
class RemoteAdConfig(context: Context) {
    private val _config = MutableStateFlow(AdConfig())
    val config: StateFlow<AdConfig> = _config.asStateFlow()

    private val rc: FirebaseRemoteConfig? = runCatching {
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) null
        else FirebaseRemoteConfig.getInstance()
    }.getOrNull()

    fun fetch() {
        val rc = rc ?: return
        runCatching {
            rc.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(3600).build())
            rc.setDefaultsAsync(AdConfig.defaultsMap())
            // Values activated on the previous run apply now; the fresh fetch applies from the next read.
            publish(rc)
            rc.fetchAndActivate().addOnCompleteListener { publish(rc) }
        }.onFailure { Log.w(TAG, "remote config unavailable", it) }
    }

    private fun publish(rc: FirebaseRemoteConfig) {
        val d = AdConfig()
        fun long(k: String, def: Int) = rc.getLong(k).toInt().takeIf { rc.getValue(k).source != FirebaseRemoteConfig.VALUE_SOURCE_STATIC } ?: def
        fun bool(k: String, def: Boolean) = if (rc.getValue(k).source == FirebaseRemoteConfig.VALUE_SOURCE_STATIC) def else rc.getBoolean(k)
        _config.value = AdConfig(
            adsEnabled = bool(AdConfig.KEY_ADS_ENABLED, d.adsEnabled),
            showBanner = bool(AdConfig.KEY_SHOW_BANNER, d.showBanner),
            interstitialInterval = long(AdConfig.KEY_INTERSTITIAL_INTERVAL, d.interstitialInterval).coerceAtLeast(1),
            interstitialMinSeconds = long(AdConfig.KEY_INTERSTITIAL_MIN_SECONDS, d.interstitialMinSeconds).coerceAtLeast(0),
            interstitialGraceSessions = long(AdConfig.KEY_INTERSTITIAL_GRACE_SESSIONS, d.interstitialGraceSessions).coerceAtLeast(0),
            nativeEvery = long(AdConfig.KEY_NATIVE_EVERY, d.nativeEvery).coerceAtLeast(0),
            appOpenEnabled = bool(AdConfig.KEY_APP_OPEN_ENABLED, d.appOpenEnabled),
            appOpenMinBackgroundSeconds = long(AdConfig.KEY_APP_OPEN_MIN_BACKGROUND, d.appOpenMinBackgroundSeconds).coerceAtLeast(0),
            appOpenMinIntervalSeconds = long(AdConfig.KEY_APP_OPEN_MIN_INTERVAL, d.appOpenMinIntervalSeconds).coerceAtLeast(0),
            rewardUnlockHours = long(AdConfig.KEY_REWARD_UNLOCK_HOURS, d.rewardUnlockHours).coerceIn(1, 24 * 7),
            rewardFallbackGrant = bool(AdConfig.KEY_REWARD_FALLBACK, d.rewardFallbackGrant),
        )
    }

    private companion object { const val TAG = "RemoteAdConfig" }
}
