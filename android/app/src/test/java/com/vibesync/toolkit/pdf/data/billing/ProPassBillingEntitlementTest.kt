package com.vibesync.toolkit.pdf.data.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import com.vibesync.toolkit.pdf.core.billing.BillingConstants
import com.vibesync.toolkit.pdf.core.security.InMemoryBillingPreferences
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import io.mockk.secondArg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProPassBillingEntitlementTest {
    private val now = 1_800_000_000_000L
    private val okResult = mockk<BillingResult> {
        every { responseCode } returns BillingClient.BillingResponseCode.OK
    }

    private fun manager(
        prefs: InMemoryBillingPreferences = InMemoryBillingPreferences(),
        client: BillingClient = mockk(relaxed = true),
        clock: () -> Long = { now }
    ): LocalAdBillingManager = LocalAdBillingManager(
        context = mockk<Context>(relaxed = true),
        prefs = prefs,
        fingerprintProvider = { "device-A" },
        clock = clock,
        billingClientForTest = client,
        managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    )

    private fun purchased(
        token: String,
        quantity: Int = 1,
        state: Int = Purchase.PurchaseState.PURCHASED
    ): Purchase {
        val purchase = mockk<Purchase>(relaxed = true)
        every { purchase.purchaseToken } returns token
        every { purchase.purchaseState } returns state
        every { purchase.products } returns listOf(BillingConstants.PRO_PASS_PRODUCT_ID)
        every { purchase.quantity } returns quantity
        return purchase
    }

    private fun successfulConsumer(client: BillingClient, onConsumed: () -> Unit = {}) {
        every { client.consumeAsync(any(), any()) } answers {
            onConsumed()
            secondArg<ConsumeResponseListener>().onConsumeResponse(okResult, "test-token")
        }
    }

    @Test
    fun firstPurchaseGrantsOneYearAfterSuccessfulConsume() = runBlocking {
        val prefs = InMemoryBillingPreferences()
        val client = mockk<BillingClient>(relaxed = true)
        successfulConsumer(client)
        val manager = manager(prefs, client)

        manager.grantIfValid(purchased("token-first", quantity = 1))

        assertEquals(now + BillingConstants.PRO_PASS_DURATION_MS, prefs.getExpiry())
        assertEquals("device-A", prefs.getFingerprint())
        assertTrue(manager.state.value.isProActive)
    }

    @Test
    fun stackingTwoYearsAddsThemAfterExistingExpiry() = runBlocking {
        val prefs = InMemoryBillingPreferences().apply {
            setFingerprint("device-A")
            setExpiry(now + BillingConstants.PRO_PASS_DURATION_MS)
        }
        val client = mockk<BillingClient>(relaxed = true)
        successfulConsumer(client)
        val manager = manager(prefs, client)

        manager.grantIfValid(purchased("token-stack", quantity = 2))

        assertEquals(now + 3L * BillingConstants.PRO_PASS_DURATION_MS, prefs.getExpiry())
    }

    @Test
    fun expiredPassIsInactiveAndRequiresDefaultWatermark() {
        val prefs = InMemoryBillingPreferences().apply {
            setFingerprint("device-A")
            setExpiry(now - 1L)
        }
        val manager = manager(prefs)

        assertFalse(manager.state.value.isProActive)
        assertFalse(manager.checkAdStatusForThisDevice())
        assertTrue(manager.shouldForceDefaultFooterWatermark())
    }

    @Test
    fun repeatedPurchaseTokenIsConsumedAndGrantedOnlyOnce() = runBlocking {
        var consumeCount = 0
        val prefs = InMemoryBillingPreferences()
        val client = mockk<BillingClient>(relaxed = true)
        successfulConsumer(client) { consumeCount++ }
        val manager = manager(prefs, client)
        val purchase = purchased("token-duplicate", quantity = 1)

        manager.grantIfValid(purchase)
        val originalExpiry = prefs.getExpiry()
        manager.grantIfValid(purchase)

        assertEquals(1, consumeCount)
        assertEquals(originalExpiry, prefs.getExpiry())
    }

    @Test
    fun differentDeviceFingerprintCannotUseValidExpiry() {
        val prefs = InMemoryBillingPreferences().apply {
            setFingerprint("device-B")
            setExpiry(now + BillingConstants.PRO_PASS_DURATION_MS)
        }
        val manager = manager(prefs)

        assertFalse(manager.checkAdStatusForThisDevice())
        assertFalse(manager.state.value.isProActive)
    }

    @Test
    fun pendingPurchaseNeverChangesExpiry() = runBlocking {
        val prefs = InMemoryBillingPreferences().apply {
            setExpiry(now + 1234L)
        }
        val client = mockk<BillingClient>(relaxed = true)
        val manager = manager(prefs, client)

        manager.grantIfValid(purchased("token-pending", state = Purchase.PurchaseState.PENDING))

        assertEquals(now + 1234L, prefs.getExpiry())
        assertTrue(manager.pendingPurchase.value)
    }

    @Test
    fun startupPurchaseQueryProcessesDuplicateTokenOnlyOnce() {
        var consumeCount = 0
        val client = mockk<BillingClient>(relaxed = true)
        every { client.isReady } returnsMany listOf(false, true, true)
        every { client.startConnection(any()) } answers {
            firstArg<BillingClientStateListener>().onBillingSetupFinished(okResult)
        }
        successfulConsumer(client) { consumeCount++ }
        val duplicate = purchased("token-startup")
        every {
            client.queryPurchasesAsync(any<QueryPurchasesParams>(), any<PurchasesResponseListener>())
        } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult, listOf(duplicate, duplicate))
        }
        val manager = manager(client = client)

        manager.startConnection()

        assertEquals(1, consumeCount)
        assertTrue(manager.state.value.isProActive)
    }
}
