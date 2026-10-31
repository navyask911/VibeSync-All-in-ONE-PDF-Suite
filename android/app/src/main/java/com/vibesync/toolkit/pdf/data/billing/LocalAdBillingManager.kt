package com.vibesync.toolkit.pdf.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.PurchasesResponseListener
import com.vibesync.toolkit.pdf.core.billing.BillingConstants
import com.vibesync.toolkit.pdf.core.security.BillingPreferences
import com.vibesync.toolkit.pdf.core.util.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.sync.Mutex

class LocalAdBillingManager(
    private val context: Context,
    private val prefs: BillingPreferences,
    private val fingerprintProvider: () -> String = { DeviceUtils.fingerprint(context) },
    private val clock: () -> Long = System::currentTimeMillis,
    billingClientForTest: BillingClient? = null,
    private val managerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _state = MutableStateFlow(ProEntitlementState(false, 0L, 0))
    val state: StateFlow<ProEntitlementState> = _state.asStateFlow()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    private val _pendingPurchase = MutableStateFlow(false)
    val pendingPurchase: StateFlow<Boolean> = _pendingPurchase.asStateFlow()

    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val grantMutex = Mutex()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            _pendingPurchase.value = purchases.any {
                BillingConstants.PRO_PASS_PRODUCT_ID in it.products &&
                    it.purchaseState == Purchase.PurchaseState.PENDING
            }
            purchases.forEach { purchase ->
                managerScope.launch { grantIfValid(purchase) }
            }
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
        }
    }

    private val billingClient: BillingClient = billingClientForTest ?: BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    init {
        refreshState()
    }

    fun checkAdStatusForThisDevice(): Boolean {
        if (prefs.getFingerprint() != fingerprintProvider()) return false
        return clock() < prefs.getExpiry()
    }

    fun shouldForceDefaultFooterWatermark(): Boolean = !checkAdStatusForThisDevice()

    suspend fun queryProductDetails(): ProductDetails? {
        if (!billingClient.isReady) {
            _productDetails.value = null
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            return null
        }

        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BillingConstants.PRO_PASS_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        val details = suspendCancellableCoroutine<ProductDetails?> { continuation ->
            try {
                billingClient.queryProductDetailsAsync(params) { result, queryResult ->
                    if (!continuation.isActive) return@queryProductDetailsAsync
                    val found = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryResult.firstOrNull {
                            it.productId == BillingConstants.PRO_PASS_PRODUCT_ID
                        }
                    } else {
                        null
                    }
                    continuation.resume(found)
                }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
        _productDetails.value = details
        if (details == null) _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
        return details
    }

    fun launchPurchase(activity: Activity, quantity: Int) {
        val details = _productDetails.value
        if (!billingClient.isReady || details == null) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            return
        }

        val clampedQuantity = quantity.coerceIn(1, BillingConstants.MAX_STACK_YEARS)
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setQuantity(clampedQuantity)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK &&
            result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED
        ) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
        }
    }

    fun startConnection() {
        if (billingClient.isReady) {
            _connectionState.value = true
            refreshState()
            refreshPurchases()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                val ready = result.responseCode == BillingClient.BillingResponseCode.OK
                _connectionState.value = ready
                refreshState()
                if (ready) {
                    refreshPurchases()
                } else {
                    _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
                }
            }

            override fun onBillingServiceDisconnected() {
                _connectionState.value = false
            }
        })
    }

    fun endConnection() {
        _connectionState.value = false
        runCatching { billingClient.endConnection() }
    }

    fun refreshPurchases(onComplete: ((Boolean) -> Unit)? = null) {
        if (!billingClient.isReady) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            onComplete?.invoke(checkAdStatusForThisDevice())
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(
            params,
            PurchasesResponseListener { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
                    onComplete?.invoke(checkAdStatusForThisDevice())
                    return@PurchasesResponseListener
                }
                _pendingPurchase.value = purchases.any {
                    BillingConstants.PRO_PASS_PRODUCT_ID in it.products &&
                        it.purchaseState == Purchase.PurchaseState.PENDING
                }
                managerScope.launch {
                    purchases.forEach { grantIfValid(it) }
                    refreshState()
                    onComplete?.invoke(checkAdStatusForThisDevice())
                }
            }
        )
    }

    suspend fun grantIfValid(purchase: Purchase) {
        // Serialize grants so two distinct consumed purchases cannot calculate the same base expiry.
        grantMutex.lock()
        try {
            if (BillingConstants.PRO_PASS_PRODUCT_ID !in purchase.products) return
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PENDING -> {
                    _pendingPurchase.value = true
                    _events.tryEmit(BillingConstants.PURCHASE_PENDING_MESSAGE)
                    return
                }
                Purchase.PurchaseState.PURCHASED -> Unit
                else -> return
            }

            val token = purchase.purchaseToken
            if (prefs.getProcessedTokens().contains(token)) return

            val quantity = purchase.quantity.coerceIn(1, BillingConstants.MAX_STACK_YEARS)
            val newExpiry = maxOf(clock(), prefs.getExpiry()) +
                quantity.toLong() * BillingConstants.PRO_PASS_DURATION_MS
            val consumeResult = suspendCancellableCoroutine<com.android.billingclient.api.BillingResult?> { continuation ->
                try {
                    val consumeParams = ConsumeParams.newBuilder()
                        .setPurchaseToken(token)
                        .build()
                    billingClient.consumeAsync(
                        consumeParams,
                        ConsumeResponseListener { result, _ ->
                            if (continuation.isActive) continuation.resume(result)
                        }
                    )
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }

            if (consumeResult?.responseCode == BillingClient.BillingResponseCode.OK) {
                prefs.persistGrant(newExpiry, fingerprintProvider(), token)
                refreshState()
            } else {
                _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            }
        } finally {
            grantMutex.unlock()
        }
    }

    private fun refreshState() {
        val now = clock()
        val expiry = prefs.getExpiry()
        val active = checkAdStatusForThisDevice()
        val days = if (active) {
            ceil((expiry - now) / BillingConstants.MILLIS_PER_DAY.toDouble())
                .toInt()
                .coerceAtLeast(0)
        } else {
            0
        }
        _state.value = ProEntitlementState(active, expiry, days)
    }
}


/**
 * Application-level reference to the Activity-owned billing manager. MainActivity injects
 * its single instance before composing routes; independent PDF engines and editor ad call sites
 * then share the same locally persisted device entitlement without constructing their own client.
 */
object BillingManagerProvider {
    @Volatile
    private var billingManager: LocalAdBillingManager? = null

    fun inject(manager: LocalAdBillingManager) {
        billingManager = manager
    }

    fun current(): LocalAdBillingManager? = billingManager
}
