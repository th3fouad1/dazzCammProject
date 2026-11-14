package com.vintagelab.camera.monetize

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** SPEC 4: entitlements, Remote Config, ads and billing, owned by [com.vintagelab.camera.App]. */
class Monetize(context: Context, scope: CoroutineScope) {
    val entitlements = EntitlementStore(context, scope)
    val remote = RemoteAdConfig(context)
    val ads = AdsManager(context, scope, remote.config, entitlements.state)
    val billing = BillingManager(context, entitlements)
    private var started = false

    /** First activity start, after the first frame: nothing here sits on the cold-start path. */
    fun start(activity: Activity) {
        if (started) return
        started = true
        remote.fetch()
        billing.connect()
        ads.gatherConsent(activity)
    }
}
