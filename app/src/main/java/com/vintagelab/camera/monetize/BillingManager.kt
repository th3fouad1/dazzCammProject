package com.vintagelab.camera.monetize

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The three ways to get Pro (SPEC 4.3). Prices come from Play, never from code. */
enum class ProPlan { Monthly, Yearly, Lifetime }

/** One plan as Play reports it: [price] is localized ("$3.99", "39,99 MAD"); [trial] is the free-trial period (ISO 8601) if one is offered. */
data class PlanOffer(val plan: ProPlan, val price: String, val priceMicros: Long, val trial: String?, internal val details: ProductDetails, internal val offerToken: String?)

data class BillingState(
    val connected: Boolean = false,
    /** Product details were queried at least once (an empty [offers] then means "not set up in Play Console"). */
    val queried: Boolean = false,
    val offers: Map<ProPlan, PlanOffer> = emptyMap(),
    /** A purchase waits for payment (cash, bank transfer): Pro starts when Play confirms it. */
    val pending: Boolean = false,
)

/**
 * Play Billing (SPEC 4.3): one subscription with a monthly and a yearly base plan, plus a one-time lifetime product.
 * Any of them active means Pro. Purchases are acknowledged here; Pro is re-checked on every resume.
 */
class BillingManager(context: Context, private val entitlements: EntitlementStore) {
    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val listener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) handle(purchases, fromQuery = false)
        else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) Log.i(TAG, "purchase: ${result.debugMessage}")
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun connect() {
        if (client.isReady) { refresh(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                _state.update { it.copy(connected = ok) }
                if (ok) { queryProducts(); refresh() } else Log.i(TAG, "setup: ${result.debugMessage}")
            }
            override fun onBillingServiceDisconnected() {
                _state.update { it.copy(connected = false) }
            }
        })
    }

    /** Re-reads owned purchases (app resume, after a purchase): the only place Pro is switched off again. */
    fun refresh() {
        if (!client.isReady) return
        var subs: List<Purchase>? = null
        var inapp: List<Purchase>? = null
        fun done() {
            val s = subs ?: return
            val i = inapp ?: return
            handle(s + i, fromQuery = true)
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) { subs = list; done() }
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) { inapp = list; done() }
        }
    }

    private fun queryProducts() {
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(SUB_ID).setProductType(BillingClient.ProductType.SUBS).build(),
            QueryProductDetailsParams.Product.newBuilder().setProductId(LIFETIME_ID).setProductType(BillingClient.ProductType.INAPP).build(),
        )
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { r, result ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "products: ${r.debugMessage}")
                _state.update { it.copy(queried = true) }
                return@queryProductDetailsAsync
            }
            val offers = mutableMapOf<ProPlan, PlanOffer>()
            for (d in result.productDetailsList) {
                when (d.productId) {
                    SUB_ID -> {
                        subOffer(d, MONTHLY_PLAN)?.let { offers[ProPlan.Monthly] = it.copy(plan = ProPlan.Monthly) }
                        subOffer(d, YEARLY_PLAN)?.let { offers[ProPlan.Yearly] = it.copy(plan = ProPlan.Yearly) }
                    }
                    LIFETIME_ID -> {
                        val o = d.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: d.oneTimePurchaseOfferDetails
                        if (o != null) offers[ProPlan.Lifetime] = PlanOffer(ProPlan.Lifetime, o.formattedPrice, o.priceAmountMicros, null, d, o.offerToken)
                    }
                }
            }
            _state.update { it.copy(queried = true, offers = offers) }
        }
    }

    /** The offer for [basePlan]: one with a free trial when the user is eligible (Play lists only eligible offers), else the base plan. */
    private fun subOffer(d: ProductDetails, basePlan: String): PlanOffer? {
        val all = d.subscriptionOfferDetails.orEmpty().filter { it.basePlanId == basePlan }
        if (all.isEmpty()) return null
        val withTrial = all.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
        val pick = withTrial ?: all.firstOrNull { it.offerId == null } ?: all.first()
        val phases = pick.pricingPhases.pricingPhaseList
        val recurring = phases.lastOrNull() ?: return null
        val trial = phases.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod
        return PlanOffer(ProPlan.Monthly, recurring.formattedPrice, recurring.priceAmountMicros, trial, d, pick.offerToken)
    }

    /** Opens Play's purchase sheet; false when the plan is not available. */
    fun buy(activity: Activity, plan: ProPlan): Boolean {
        val offer = _state.value.offers[plan] ?: return false
        val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.details)
        offer.offerToken?.let { p.setOfferToken(it) }
        val r = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p.build())).build())
        return r.responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun handle(purchases: List<Purchase>, fromQuery: Boolean) {
        val ours = purchases.filter { p -> p.products.any { it == SUB_ID || it == LIFETIME_ID } }
        val owned = ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        _state.update { it.copy(pending = pending) }
        for (p in owned) if (!p.isAcknowledged) acknowledge(p)
        // A purchase callback only adds Pro; a full query is the truth and may remove it (expired, refunded).
        if (owned.isNotEmpty()) entitlements.setPro(true) else if (fromQuery) entitlements.setPro(false)
    }

    private fun acknowledge(p: Purchase) {
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK) Log.w(TAG, "acknowledge: ${r.debugMessage}")
        }
    }

    companion object {
        private const val TAG = "Billing"
        /** Play Console ids (SPEC 4.3): create them with exactly these names. */
        const val SUB_ID = "pro_subscription"
        const val MONTHLY_PLAN = "monthly"
        const val YEARLY_PLAN = "yearly"
        const val LIFETIME_ID = "pro_lifetime"
    }
}
