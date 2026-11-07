package com.vibesync.toolkit.pdf.data.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import com.vibesync.toolkit.pdf.core.billing.BillingConstants
import com.vibesync.toolkit.pdf.core.security.InMemoryBillingPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProPassBillingEntitlementTest {
    private val now = 1_800_000_000_000L
    private val subscriptionGraceBufferMs = 24L * 60L * 60L * 1000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun okResult() = BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()

    private fun purchase(
        token: String,
        purchaseTime: Long = now,
        acknowledged: Boolean = false,
        state: Int = Purchase.PurchaseState.PURCHASED
    ): Purchase = mockk<Purchase>().also {
        every { it.products } returns listOf(BillingConstants.PRO_PASS_PRODUCT_ID)
        every { it.purchaseToken } returns token
        every { it.purchaseTime } returns purchaseTime
        every { it.isAcknowledged } returns acknowledged
        every { it.purchaseState } returns state
        every { it.accountIdentifiers } returns null
    }

    private fun manager(
        prefs: InMemoryBillingPreferences = InMemoryBillingPreferences(),
        clock: () -> Long = { now },
        billingClientForTest: BillingClient? = null,
        acknowledge: suspend (String) -> BillingResult = { okResult() }
    ) = LocalAdBillingManager(
        context = context,
        prefs = prefs,
        fingerprintProvider = { "device-A" },
        clock = clock,
        billingClientForTest = billingClientForTest,
        acknowledgeFn = acknowledge
    )

    @Test fun newPurchaseIsAcknowledgedAndExpiresExactlyOneYearAfterPurchaseTime() = runBlocking {
        val prefs = InMemoryBillingPreferences()
        val acked = mutableListOf<String>()
        val purchaseTime = now - 2_000L
        val manager = manager(prefs, acknowledge = { acked += it; okResult() })
        manager.grantIfValid(purchase("token-first", purchaseTime))
        assertEquals(listOf("token-first"), acked)
        assertEquals(
            purchaseTime + BillingConstants.PRO_PASS_DURATION_MS + subscriptionGraceBufferMs,
            prefs.getExpiry()
        )
        assertEquals("device-A", prefs.getFingerprint())
        assertNotNull(prefs.getHardwareBindingId())
        assertEquals(setOf("token-first"), prefs.getProcessedTokens())
        assertTrue(manager.state.value.isProActive)
    }

    @Test fun alreadyAcknowledgedPurchaseIsGrantedWithoutAnotherAcknowledgeCall() = runBlocking {
        val acked = mutableListOf<String>()
        val manager = manager(acknowledge = { acked += it; okResult() })
        manager.grantIfValid(purchase("token-acknowledged", acknowledged = true))
        assertTrue(acked.isEmpty())
        assertEquals(
            now + BillingConstants.PRO_PASS_DURATION_MS + subscriptionGraceBufferMs,
            manager.state.value.expiryEpochMillis
        )
        assertTrue(manager.state.value.isProActive)
    }

    @Test fun repeatedPurchaseTokenIsAcknowledgedAndGrantedOnlyOnce() = runBlocking {
        val prefs = InMemoryBillingPreferences()
        val acked = mutableListOf<String>()
        val manager = manager(prefs, acknowledge = { acked += it; okResult() })
        val p = purchase("token-duplicate")
        manager.grantIfValid(p)
        val expiry = prefs.getExpiry()
        manager.grantIfValid(p)
        assertEquals(listOf("token-duplicate"), acked)
        assertEquals(expiry, prefs.getExpiry())
    }

    @Test fun pendingPurchaseNeverChangesExpiryOrGetsAcknowledged() = runBlocking {
        val prefs = InMemoryBillingPreferences().apply { setFingerprint("device-A"); setExpiry(now + 1_234L) }
        val acked = mutableListOf<String>()
        val manager = manager(prefs, acknowledge = { acked += it; okResult() })
        manager.grantIfValid(purchase("token-pending", state = Purchase.PurchaseState.PENDING))
        assertEquals(now + 1_234L, prefs.getExpiry())
        assertTrue(manager.pendingPurchase.value)
        assertTrue(acked.isEmpty())
    }

    @Test fun expiredPlayPurchaseDoesNotGrantActivePro() = runBlocking {
        val prefs = InMemoryBillingPreferences()
        val manager = manager(prefs)
        manager.grantIfValid(purchase(
            "token-expired",
            purchaseTime = now - BillingConstants.PRO_PASS_DURATION_MS - subscriptionGraceBufferMs - 1L,
            acknowledged = true
        ))
        assertEquals(now - 1L, prefs.getExpiry())
        assertFalse(manager.state.value.isProActive)
        assertTrue(manager.isProOwned.value)
    }

    @Test fun startupRestoreQueriesOwnedPurchasesOnIoAndAcknowledgesUnacknowledgedItems() = runBlocking {
        val prefs = InMemoryBillingPreferences()
        val client = mockk<BillingClient>()
        val listener = slot<PurchasesResponseListener>()
        val queryThread = AtomicReference<Thread?>()
        val owned = purchase("token-restored")
        every { client.isReady } returns true
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(listener)) } answers {
            queryThread.set(Thread.currentThread())
            listener.captured.onQueryPurchasesResponse(okResult(), listOf(owned))
        }
        val acked = mutableListOf<String>()
        val manager = manager(prefs = prefs, billingClientForTest = client, acknowledge = { acked += it; okResult() })
        manager.restoreProLicenseFromPlayStore(context)
        assertEquals(listOf("token-restored"), acked)
        assertEquals(
            now + BillingConstants.PRO_PASS_DURATION_MS + subscriptionGraceBufferMs,
            prefs.getExpiry()
        )
        assertTrue(manager.state.value.isProActive)
        assertNotNull(queryThread.get())
        assertFalse(queryThread.get() === Thread.currentThread())
    }

    @Test fun localV1013ConsumedGrantSurvivesWhenPlayReturnsNoOwnedPurchases() = runBlocking {
        val prefs = InMemoryBillingPreferences().apply {
            setFingerprint("device-A")
            setExpiry(now + 7L * BillingConstants.MILLIS_PER_DAY)
            addProcessedToken("legacy-consumed-token")
        }
        val client = mockk<BillingClient>()
        every { client.isReady } returns true
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any()) } answers {
            secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(okResult(), emptyList())
        }
        val manager = manager(prefs = prefs, billingClientForTest = client)
        manager.restoreProLicenseFromPlayStore(context)
        assertTrue(manager.checkAdStatusForThisDevice())
        assertTrue(manager.state.value.isProActive)
        assertNotNull(prefs.getHardwareBindingId())
        assertEquals(now + 7L * BillingConstants.MILLIS_PER_DAY, prefs.getExpiry())
    }
}
