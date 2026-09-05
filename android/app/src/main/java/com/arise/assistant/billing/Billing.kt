package com.arise.assistant.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.arise.assistant.log.LocalLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thin Google Play Billing wrapper for the optional subscription.
 * Arise stays fully usable free; subscription is an optional support tier.
 * All failures surface honestly — nothing is gated in a way that locks the user
 * out of app data.
 */
class BillingManager(context: Context, private val listener: (Boolean) -> Unit) {

    interface Callback { fun onState(billingOk: Boolean, purchased: Boolean, price: String?) }

    private val productId = "arise_support_monthly"
    private val callbacks = CopyOnWriteArrayList<Callback>()
    @Volatile private var product: ProductDetails? = null
    @Volatile private var purchased = false

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(PurchasesUpdatedListener { result: BillingResult, purchases: List<Purchase>? ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && !purchases.isNullOrEmpty()) {
                purchased = true
                fire()
            } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                LocalLog.w("Billing", "purchase update code=${result.responseCode}")
            }
        })
        .enablePendingPurchases()
        .build()

    fun connect() {
        billingClient.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct()
                    queryPurchases()
                }
            }
            override fun onBillingServiceDisconnected() { listener(false) }
        })
    }

    private fun queryProduct() {
        val params = com.android.billingclient.api.QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(com.android.billingclient.api.QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()))
            .build()
        billingClient.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && details.isNotEmpty()) {
                product = details.first()
                fire()
            }
        }
    }

    private fun queryPurchases() {
        billingClient.queryPurchasesAsync(BillingClient.ProductType.SUBS) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchased = purchases.any { it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                fire()
            }
        }
    }

    fun launchFlow(activity: Activity) {
        val p = product ?: return
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p).build())
        ).build()
        billingClient.launchBillingFlow(activity, params)
    }

    fun state(): Pair<Boolean, String?> = purchased to product?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

    fun addCallback(cb: Callback) { callbacks.add(cb); cb.onState(true, purchased, product?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice) }
    fun removeCallback(cb: Callback) { callbacks.remove(cb) }

    private fun fire() { callbacks.forEach { it.onState(true, purchased, state().second) } }

    fun dispose() {
        try { billingClient.endConnection() } catch (_: Exception) {}
    }
}
