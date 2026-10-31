package com.vibesync.toolkit.pdf.data.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.vibesync.toolkit.pdf.core.billing.BillingConstants
import com.vibesync.toolkit.pdf.core.security.InMemoryBillingPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProPassBillingEntitlementTest {
    private val now = 1_800_000_000_000L

    private fun fakeConsume(consumedTokens: MutableList<String>): suspend (String) -> BillingResult =
        { token ->
            consumedTokens += token
            BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.OK)
                .build()
        }

    private fun manager(
        prefs: InMemoryBillingPreferences = InMemoryBillingPreferences(),
        clock: () -> Long = { now },
        consumeFn: suspend (String) -> BillingResult
    ): LocalAdBillingManager = LocalAdBillingManager(
        context = ApplicationProvider.getApplicationContext<Context>(),
        prefs = prefs,
        fingerprintProvider = { "device-A" },
        clock = clock,
        managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        consumeFn = consumeFn
    )

    @Test
    fun firstPurchaseGrantsOneYearAfterSuccessfulConsume() = runBlocking {
        val prefs = InMemoryBillingPreferences()
        val consumedTokens = mutableListOf<String>()
        val manager = manager(prefs, consumeFn = fakeConsume(consumedTokens))

        manager.applyGrantForTest("token-first", 1, Purchase.PurchaseState.PURCHASED)

        assertEquals(listOf("token-first"), consumedTokens)
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
        val consumedTokens = mutableListOf<String>()
        val manager = manager(prefs, consumeFn = fakeConsume(consumedTokens))

        manager.applyGrantForTest("token-stack", 2, Purchase.PurchaseState.PURCHASED)

        assertEquals(listOf("token-stack"), consumedTokens)
        assertEquals(now + 3L * BillingConstants.PRO_PASS_DURATION_MS, prefs.getExpiry())
    }

    @Test
    fun expiredPassIsInactiveAndRequiresDefaultWatermark() {
        val prefs = InMemoryBillingPreferences().apply {
            setFingerprint("device-A")
            setExpiry(now - 1L)
        }
        val consumedTokens = mutableListOf<String>()
        val manager = manager(prefs, consumeFn = fakeConsume(consumedTokens))

        assertFalse(manager.state.value.isProActive)
        assertFalse(manager.checkAdStatusForThisDevice())
        assertTrue(manager.shouldForceDefaultFooterWatermark())
        assertTrue(consumedTokens.isEmpty())
    }

    @Test
    fun repeatedPurchaseTokenIsConsumedAndGrantedOnlyOnce() = runBlocking {
        val prefs = InMemoryBillingPreferences()
        val consumedTokens = mutableListOf<String>()
        val manager = manager(prefs, consumeFn = fakeConsume(consumedTokens))

        manager.applyGrantForTest("token-duplicate", 1, Purchase.PurchaseState.PURCHASED)
        val originalExpiry = prefs.getExpiry()
        manager.applyGrantForTest("token-duplicate", 1, Purchase.PurchaseState.PURCHASED)

        assertEquals(listOf("token-duplicate"), consumedTokens)
        assertEquals(originalExpiry, prefs.getExpiry())
    }

    @Test
    fun differentDeviceFingerprintCannotUseValidExpiry() {
        val prefs = InMemoryBillingPreferences().apply {
            setFingerprint("device-B")
            setExpiry(now + BillingConstants.PRO_PASS_DURATION_MS)
        }
        val consumedTokens = mutableListOf<String>()
        val manager = manager(prefs, consumeFn = fakeConsume(consumedTokens))

        assertFalse(manager.checkAdStatusForThisDevice())
        assertFalse(manager.state.value.isProActive)
        assertTrue(consumedTokens.isEmpty())
    }

    @Test
    fun pendingPurchaseNeverChangesExpiry() = runBlocking {
        val prefs = InMemoryBillingPreferences().apply {
            setExpiry(now + 1234L)
        }
        val consumedTokens = mutableListOf<String>()
        val manager = manager(prefs, consumeFn = fakeConsume(consumedTokens))

        manager.applyGrantForTest("token-pending", 1, Purchase.PurchaseState.PENDING)

        assertEquals(now + 1234L, prefs.getExpiry())
        assertTrue(manager.pendingPurchase.value)
        assertTrue(consumedTokens.isEmpty())
    }

    @Test
    fun startupPurchaseQueryProcessesDuplicateTokenOnlyOnce() = runBlocking {
        // Startup reconciliation may deliver the same unprocessed token more than once.
        val prefs = InMemoryBillingPreferences()
        val consumedTokens = mutableListOf<String>()
        val manager = manager(prefs, consumeFn = fakeConsume(consumedTokens))

        manager.applyGrantForTest("token-startup", 1, Purchase.PurchaseState.PURCHASED)
        manager.applyGrantForTest("token-startup", 1, Purchase.PurchaseState.PURCHASED)

        assertEquals(listOf("token-startup"), consumedTokens)
        assertTrue(manager.state.value.isProActive)
    }

    @Test
    fun purchaseQuantityOfThreeGrantsExactlyThreeYearsAfterSuccessfulConsume() = runBlocking {
        val prefs = InMemoryBillingPreferences()
        val consumedTokens = mutableListOf<String>()
        val manager = manager(prefs, consumeFn = fakeConsume(consumedTokens))

        manager.applyGrantForTest("token-quantity-three", 3, Purchase.PurchaseState.PURCHASED)

        assertEquals(listOf("token-quantity-three"), consumedTokens)
        assertEquals(
            now + 3L * BillingConstants.PRO_PASS_DURATION_MS,
            prefs.getExpiry()
        )
        assertTrue(manager.state.value.isProActive)
    }
}
