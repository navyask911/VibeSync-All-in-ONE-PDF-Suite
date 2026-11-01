package com.vibesync.toolkit.pdf.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.queryProductDetails
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
    private val managerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val consumeFn: (suspend (String) -> BillingResult)? = null
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
        .enableAutoServiceReconnection()
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

        val details = try {
            val queryResult = billingClient.queryProductDetails(params)
            if (queryResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                queryResult.productDetailsList?.firstOrNull {
                    it.productId == BillingConstants.PRO_PASS_PRODUCT_ID
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        _productDetails.value = details
        if (details == null) _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
        return details
    }

    fun launchPurchase(activity: Activity) {
        val details = _productDetails.value
        if (!billingClient.isReady || details == null) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            return
        }

        val productDetailsParams =
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()

        val billingFlowParams =
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsParams))
                .build()

        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
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
                android.util.Log.w(
                    "LocalAdBillingManager",
                    "Billing service disconnected; automatic reconnection is enabled."
                )
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
        if (BillingConstants.PRO_PASS_PRODUCT_ID !in purchase.products) return
        val quantity = purchase.quantity.coerceIn(1, BillingConstants.MAX_STACK_YEARS)
        applyGrantForTest(purchase.purchaseToken, quantity, purchase.purchaseState)
    }

    internal suspend fun applyGrantForTest(
        token: String,
        quantity: Int,
        state: Int
    ) {
        // Serialize grants so two distinct consumed purchases cannot calculate the same base expiry.
        grantMutex.lock()
        try {
            when (state) {
                Purchase.PurchaseState.PENDING -> {
                    _pendingPurchase.value = true
                    _events.tryEmit(BillingConstants.PURCHASE_PENDING_MESSAGE)
                    return
                }
                Purchase.PurchaseState.PURCHASED -> Unit
                else -> return
            }

            if (prefs.getProcessedTokens().contains(token)) return

            val newExpiry = maxOf(clock(), prefs.getExpiry()) +
                quantity.toLong() * BillingConstants.PRO_PASS_DURATION_MS
            val result = consumeFn?.invoke(token) ?: suspendConsumeDefault(token)

            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                applyConsumeSuccess(prefs, token, newExpiry)
                refreshState()
            } else {
                _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            }
        } finally {
            grantMutex.unlock()
        }
    }

    private suspend fun suspendConsumeDefault(token: String): BillingResult =
        suspendCancellableCoroutine { continuation ->
            try {
                val consumeParams = ConsumeParams.newBuilder()
                    .setPurchaseToken(token)
                    .build()
                // NOTE (PBL 8+): Google Play Billing 8 removed the ability to query CONSUMED one-time products.
                // Once consumeAsync() succeeds, the purchase is gone permanently. It cannot be restored
                // on reinstall. Do NOT attempt a restore-purchase flow.
                billingClient.consumeAsync(consumeParams) { result, _ ->
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (_: Exception) {
                if (continuation.isActive) {
                    continuation.resume(
                        BillingResult.newBuilder()
                            .setResponseCode(BillingClient.BillingResponseCode.ERROR)
                            .build()
                    )
                }
            }
        }

    private fun applyConsumeSuccess(
        prefs: BillingPreferences,
        token: String,
        newExpiry: Long
    ) {
        prefs.persistGrant(newExpiry, fingerprintProvider(), token)
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
