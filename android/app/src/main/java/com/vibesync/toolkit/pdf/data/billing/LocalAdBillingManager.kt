package com.vibesync.toolkit.pdf.data.billing

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
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.QueryPurchasesParams
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.ceil

class LocalAdBillingManager(
    context: Context,
    private val prefs: BillingPreferences,
    private val fingerprintProvider: () -> String = { DeviceUtils.fingerprint(context) },
    private val clock: () -> Long = System::currentTimeMillis,
    billingClientForTest: BillingClient? = null,
    private val managerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val acknowledgeFn: (suspend (String) -> BillingResult)? = null
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(ProEntitlementState(false, 0L, 0))
    val state: StateFlow<ProEntitlementState> = _state.asStateFlow()
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()
    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    private val _pendingPurchase = MutableStateFlow(false)
    val pendingPurchase: StateFlow<Boolean> = _pendingPurchase.asStateFlow()
    private val _isProOwned = MutableStateFlow(false)
    val isProOwned: StateFlow<Boolean> = _isProOwned.asStateFlow()
    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val grantMutex = Mutex()
    private val connectionMutex = Mutex()
    private val hardwareBindingLock = Any()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            _pendingPurchase.value = purchases.any {
                BillingConstants.PRO_PASS_PRODUCT_ID in it.products &&
                    it.purchaseState == Purchase.PurchaseState.PENDING
            }
            purchases.forEach { purchase ->
                managerScope.launch(Dispatchers.IO) { grantIfValid(purchase, appContext) }
            }
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
        }
    }

    private val billingClient: BillingClient = billingClientForTest ?: BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        // Derive the UUID from the app-scoped Android ID so it can be regenerated on reinstall.
        getOrCreateHardwareBindingId()
        refreshState()
    }

    fun checkAdStatusForThisDevice(): Boolean {
        if (prefs.getFingerprint() != fingerprintProvider()) return false
        return clock() < prefs.getExpiry()
    }

    fun shouldForceDefaultFooterWatermark(): Boolean = !checkAdStatusForThisDevice()

    suspend fun queryProductDetails(): ProductDetails? = withContext(Dispatchers.IO) {
        if (!billingClient.isReady) {
            _productDetails.value = null
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            return@withContext null
        }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BillingConstants.PRO_PASS_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        val details = try {
            val queryResult = billingClient.queryProductDetails(params)
            if (queryResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                queryResult.productDetailsList
                    ?.firstOrNull { it.productId == BillingConstants.PRO_PASS_PRODUCT_ID }
                    ?.takeIf { !it.subscriptionOfferDetails.isNullOrEmpty() }
            } else null
        } catch (error: Exception) {
            Log.w(TAG, "Could not query the Pro Pass product details.", error)
            null
        }
        _productDetails.value = details
        if (details == null) _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
        details
    }

    fun launchPurchase(activity: Activity) {
        val details = _productDetails.value
        if (!billingClient.isReady || details == null) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            return
        }
        val subscriptionOffer = details.subscriptionOfferDetails
            ?.firstOrNull { it.offerId == null }
            ?: details.subscriptionOfferDetails?.firstOrNull()
        val offerToken = subscriptionOffer?.offerToken
        if (offerToken.isNullOrBlank()) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            return
        }
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .setObfuscatedAccountId(getOrCreateHardwareBindingId())
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK &&
            result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED
        ) _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
    }

    fun startConnection() {
        managerScope.launch(Dispatchers.IO) { restoreProLicenseFromPlayStore(appContext) }
    }

    fun endConnection() {
        _connectionState.value = false
        runCatching { billingClient.endConnection() }
    }

    /** Connects and queries Play-owned SUBS purchases on Dispatchers.IO. */
    suspend fun restoreProLicenseFromPlayStore(context: Context) = withContext(Dispatchers.IO) {
        val safeContext = context.applicationContext
        if (!ensureBillingConnection()) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            refreshState()
            return@withContext
        }
        val (result, purchases) = try {
            queryOwnedPurchases()
        } catch (error: Exception) {
            Log.w(TAG, "Could not query owned Pro Pass purchases.", error)
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            refreshState()
            return@withContext
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
            refreshState()
            return@withContext
        }

        val proPurchases = purchases.filter { BillingConstants.PRO_PASS_PRODUCT_ID in it.products }
        val purchased = proPurchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        _pendingPurchase.value = proPurchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        _isProOwned.value = purchased.isNotEmpty()
        purchased.forEach { grantIfValid(it, safeContext) }

        refreshState()
    }

    fun refreshPurchases(onComplete: ((Boolean) -> Unit)? = null) {
        managerScope.launch(Dispatchers.IO) {
            restoreProLicenseFromPlayStore(appContext)
            onComplete?.invoke(checkAdStatusForThisDevice())
        }
    }

    suspend fun grantIfValid(purchase: Purchase, eventContext: Context = appContext) {
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

        grantMutex.withLock {
            val token = purchase.purchaseToken
            // A token is recorded only after a successful acknowledgement and entitlement write.
            // Do not acknowledge the same Play purchase again during restore/repeated callbacks.
            val alreadyProcessed = token in prefs.getProcessedTokens()
            if (!purchase.isAcknowledged && !alreadyProcessed) {
                val ack = acknowledgeFn?.invoke(token) ?: suspendAcknowledgeDefault(token)
                if (ack.responseCode != BillingClient.BillingResponseCode.OK) {
                    _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
                    return@withLock
                }
            }

            _isProOwned.value = true
            val deviceId = getOrCreateHardwareBindingId()
            if (purchase.purchaseTime <= 0L) {
                _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
                return@withLock
            }
            val expiry = try {
                Math.addExact(
                    Math.addExact(purchase.purchaseTime, BillingConstants.PRO_PASS_DURATION_MS),
                    SUBSCRIPTION_GRACE_BUFFER_MS
                )
            } catch (error: ArithmeticException) {
                Log.w(TAG, "Pro Pass purchase time overflowed the expiry timestamp.", error)
                _events.tryEmit(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
                return@withLock
            }
            prefs.persistNonConsumableGrant(
                expiry = expiry,
                fingerprint = fingerprintProvider(),
                hardwareBindingId = deviceId,
                token = token
            )
            refreshState()
        }
    }

    private suspend fun ensureBillingConnection(): Boolean = connectionMutex.withLock {
        if (billingClient.isReady) {
            _connectionState.value = true
            return@withLock true
        }
        suspendCancellableCoroutine { continuation ->
            try {
                billingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        val ready = result.responseCode == BillingClient.BillingResponseCode.OK
                        _connectionState.value = ready
                        if (continuation.isActive) continuation.resume(ready)
                    }
                    override fun onBillingServiceDisconnected() {
                        _connectionState.value = false
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
            } catch (error: Exception) {
                Log.w(TAG, "Could not connect to Google Play Billing.", error)
                _connectionState.value = false
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    private suspend fun queryOwnedPurchases(): Pair<BillingResult, List<Purchase>> =
        suspendCancellableCoroutine { continuation ->
            try {
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
                billingClient.queryPurchasesAsync(params) { result, purchases ->
                    if (continuation.isActive) continuation.resume(result to purchases)
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resume(
                    BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.ERROR).build() to emptyList()
                )
            }
        }

    private suspend fun suspendAcknowledgeDefault(token: String): BillingResult =
        suspendCancellableCoroutine { continuation ->
            try {
                val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
                billingClient.acknowledgePurchase(params) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not acknowledge the Pro Pass purchase.", error)
                if (continuation.isActive) continuation.resume(
                    BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.ERROR).build()
                )
            }
        }

    /**
     * Creates a stable UUID from the app-scoped Android ID, stores it in EncryptedSharedPreferences,
     * and sends only this opaque value to Play as the obfuscated account identifier.
     */
    private fun getOrCreateHardwareBindingId(): String = synchronized(hardwareBindingLock) {
        prefs.getHardwareBindingId()?.takeIf { it.isNotBlank() }?.let { return@synchronized it }
        val generated = UUID.nameUUIDFromBytes(
            "com.vibesync.toolkit.pdf:pro-pass:${fingerprintProvider()}".toByteArray(Charsets.UTF_8)
        ).toString()
        prefs.setHardwareBindingId(generated)
        generated
    }

    private fun refreshState() {
        val now = clock()
        val expiry = prefs.getExpiry()
        val active = checkAdStatusForThisDevice()
        val days = if (active) ceil((expiry - now) / BillingConstants.MILLIS_PER_DAY.toDouble()).toInt().coerceAtLeast(0) else 0
        _state.value = ProEntitlementState(active, expiry, days)
    }

    private companion object {
        const val TAG = "LocalAdBillingManager"
        const val SUBSCRIPTION_GRACE_BUFFER_MS = 24L * 60L * 60L * 1000L
    }
}

object BillingManagerProvider {
    @Volatile private var billingManager: LocalAdBillingManager? = null
    fun inject(manager: LocalAdBillingManager) { billingManager = manager }
    fun current(): LocalAdBillingManager? = billingManager
}
