package com.vintagelab.camera.monetize

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.vintagelab.camera.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SPEC 4.2: consent (UMP), SDK start and the five formats. Every show is a no-op for Pro users, before consent,
 * or when Remote Config switches ads off. Full-screen formats are preloaded so a show never waits.
 * All methods are called on the main thread.
 */
class AdsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<AdConfig>,
    private val entitlements: StateFlow<Entitlements>,
) {
    private val consentStarted = AtomicBoolean(false)
    private val sdkStarted = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    /** Consent gathered (or not needed) and the SDK started: ads may be requested. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _privacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private var interstitial: InterstitialAd? = null
    private var interstitialLoading = false
    private var rewarded: RewardedAd? = null
    private var rewardedLoading = false
    private var appOpen: AppOpenAd? = null
    private var appOpenLoadedAt = 0L
    private var appOpenLoading = false
    private var showingFullScreen = false
    private val pacer = InterstitialPacer()
    private var lastFullScreenMs = -(1L shl 40)

    private val consentInfo: ConsentInformation by lazy { UserMessagingPlatform.getConsentInformation(context) }

    /** Ads may show right now. */
    val active: Boolean
        get() = _ready.value && config.value.adsEnabled && entitlements.value.loaded && !entitlements.value.pro

    /** A full-screen ad is on top (the camera is released meanwhile). */
    val isShowingFullScreen: Boolean get() = showingFullScreen

    /** Cold start: refresh consent (the form shows only where the law needs it), then start the SDK off the main thread. */
    fun gatherConsent(activity: Activity) {
        if (!consentStarted.compareAndSet(false, true)) return
        val params = ConsentRequestParameters.Builder().build()
        consentInfo.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { err ->
                if (err != null) Log.w(TAG, "consent form: ${err.message}")
                updatePrivacyOptions()
                if (consentInfo.canRequestAds()) startSdk()
            }
        }, { err ->
            Log.w(TAG, "consent update: ${err.message}")
            if (consentInfo.canRequestAds()) startSdk()
        })
        // Consent from a previous run allows requests at once, in parallel with the refresh.
        if (consentInfo.canRequestAds()) startSdk()
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { err ->
            if (err != null) Log.w(TAG, "privacy options: ${err.message}")
            updatePrivacyOptions()
        }
    }

    private fun updatePrivacyOptions() {
        _privacyOptionsRequired.value =
            consentInfo.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun startSdk() {
        if (!sdkStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.Main) {
            // Pro users never load an ad: wait for the cached entitlement before deciding.
            entitlements.first { it.loaded }
            withContext(Dispatchers.IO) { MobileAds.initialize(context) }
            _ready.value = true
            preload()
        }
        scope.launch(Dispatchers.Main) {
            // Becoming Pro mid-session drops everything already loaded.
            entitlements.collect { if (it.pro) { interstitial = null; rewarded = null; appOpen = null } }
        }
    }

    private fun preload() {
        if (!active) return
        loadInterstitial()
        loadRewarded()
        if (config.value.appOpenEnabled) loadAppOpen()
    }

    private fun request() = AdRequest.Builder().build()

    private fun loadInterstitial() {
        if (!active || interstitial != null || interstitialLoading) return
        interstitialLoading = true
        InterstitialAd.load(context, BuildConfig.AD_INTERSTITIAL, request(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) { interstitialLoading = false; interstitial = ad }
            override fun onAdFailedToLoad(e: LoadAdError) { interstitialLoading = false; Log.i(TAG, "interstitial: ${e.message}") }
        })
    }

    private fun loadRewarded() {
        if (!active || rewarded != null || rewardedLoading) return
        rewardedLoading = true
        RewardedAd.load(context, BuildConfig.AD_REWARDED, request(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) { rewardedLoading = false; rewarded = ad }
            override fun onAdFailedToLoad(e: LoadAdError) { rewardedLoading = false; Log.i(TAG, "rewarded: ${e.message}") }
        })
    }

    private fun loadAppOpen() {
        if (!active || !config.value.appOpenEnabled || appOpenLoading || (appOpen != null && !appOpenExpired())) return
        appOpenLoading = true
        AppOpenAd.load(context, BuildConfig.AD_APP_OPEN, request(), object : AppOpenAd.AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) { appOpenLoading = false; appOpen = ad; appOpenLoadedAt = SystemClock.elapsedRealtime() }
            override fun onAdFailedToLoad(e: LoadAdError) { appOpenLoading = false; Log.i(TAG, "app open: ${e.message}") }
        })
    }

    /** App Open ads expire after four hours (AdMob guidance). */
    private fun appOpenExpired() = SystemClock.elapsedRealtime() - appOpenLoadedAt > 4 * 3_600_000L

    private fun fullScreenCallback(onDone: () -> Unit, reload: () -> Unit) = object : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() {
            lastFullScreenMs = SystemClock.elapsedRealtime()
        }
        override fun onAdDismissedFullScreenContent() {
            showingFullScreen = false
            lastFullScreenMs = SystemClock.elapsedRealtime()
            reload()
            onDone()
        }
        override fun onAdFailedToShowFullScreenContent(e: AdError) {
            showingFullScreen = false
            Log.i(TAG, "show failed: ${e.message}")
            reload()
            onDone()
        }
    }

    /**
     * A navigation action happened (gallery opened, editor saved). Shows an interstitial when the pacer allows and one is
     * loaded, then runs [onDone]; otherwise runs [onDone] at once. Callers never call this mid-capture or mid-recording.
     */
    fun onNavigation(activity: Activity?, onDone: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (activity == null || !active || showingFullScreen || !pacer.onAction(config.value, now, entitlements.value.session)) {
            onDone(); return
        }
        val ad = interstitial
        if (ad == null) { loadInterstitial(); onDone(); return }
        interstitial = null
        pacer.onShown(now)
        showingFullScreen = true
        ad.fullScreenContentCallback = fullScreenCallback(onDone) { loadInterstitial() }
        ad.show(activity)
    }

    /**
     * Rewarded unlock. [onResult] gets true when the user earned the reward (or no ad could be had and
     * [AdConfig.rewardFallbackGrant] is on), false when they closed the ad early.
     */
    fun showRewarded(activity: Activity, onResult: (Boolean) -> Unit) {
        scope.launch(Dispatchers.Main) {
            if (!active) { onResult(true); return@launch } // Pro, ads off or no consent: nothing to watch
            if (rewarded == null) {
                loadRewarded()
                withTimeoutOrNull(REWARD_WAIT_MS) { while (rewarded == null && rewardedLoading) delay(100) }
            }
            val ad = rewarded
            if (ad == null) { onResult(config.value.rewardFallbackGrant); return@launch }
            rewarded = null
            var earned = false
            showingFullScreen = true
            pacer.onOtherFullScreen(SystemClock.elapsedRealtime())
            ad.fullScreenContentCallback = fullScreenCallback({ onResult(earned) }) { loadRewarded() }
            ad.show(activity) { earned = true }
        }
    }

    /** MainActivity came back to the foreground (not a cold start). */
    fun onWarmStart(activity: Activity, backgroundMs: Long, returnedFromOwnActivity: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val ok = AppOpenPolicy.shouldShow(
            config.value, entitlements.value.pro, active, warmStart = true, returnedFromOwnActivity = returnedFromOwnActivity,
            backgroundMs = backgroundMs, sinceLastFullScreenMs = now - lastFullScreenMs,
        )
        val ad = appOpen
        if (!ok || showingFullScreen || ad == null || appOpenExpired()) {
            loadAppOpen()
            return
        }
        appOpen = null
        showingFullScreen = true
        pacer.onOtherFullScreen(now)
        ad.fullScreenContentCallback = fullScreenCallback({}) { loadAppOpen() }
        ad.show(activity)
    }

    /** Native ads for the gallery grid; [onLoaded] receives each ad (up to [count]). The caller destroys them. */
    fun loadNatives(count: Int, onLoaded: (NativeAd) -> Unit) {
        if (!active || count <= 0) return
        val loader = AdLoader.Builder(context, BuildConfig.AD_NATIVE)
            .forNativeAd { ad -> if (active) onLoaded(ad) else ad.destroy() }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(e: LoadAdError) { Log.i(TAG, "native: ${e.message}") }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT).build())
            .build()
        loader.loadAds(request(), count.coerceIn(1, 5))
    }

    private companion object {
        const val TAG = "Ads"
        const val REWARD_WAIT_MS = 6_000L
    }
}
