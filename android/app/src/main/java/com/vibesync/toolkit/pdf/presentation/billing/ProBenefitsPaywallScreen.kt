package com.vibesync.toolkit.pdf.presentation.billing

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.core.billing.BillingConstants
import com.vibesync.toolkit.pdf.data.billing.LocalAdBillingManager
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProBenefitsPaywallScreen(
    billingManager: LocalAdBillingManager,
    activity: Activity,
    onNavigateBack: () -> Unit
) {
    val entitlement by billingManager.state.collectAsState()
    val productDetails by billingManager.productDetails.collectAsState()
    val connected by billingManager.connectionState.collectAsState()
    val pending by billingManager.pendingPurchase.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var quantity by remember { mutableStateOf(1f) }

    LaunchedEffect(billingManager) {
        billingManager.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(connected) {
        if (connected) {
            billingManager.queryProductDetails()
        } else {
            snackbarHostState.showSnackbar(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
        }
    }

    val selectedQuantity = quantity.toInt().coerceIn(1, BillingConstants.MAX_STACK_YEARS)
    val fallbackBase = BillingConstants.PRO_PASS_DISPLAY_PRICE_FALLBACK
        .removeSuffix(BillingConstants.YEAR_PRICE_SUFFIX)
    val fallbackTotal = "$fallbackBase × $selectedQuantity${BillingConstants.YEAR_PRICE_SUFFIX}"
    val offer = productDetails?.oneTimePurchaseOfferDetails
    val totalPrice = offer?.let {
        runCatching {
            val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault())
            formatter.currency = Currency.getInstance(it.priceCurrencyCode)
            formatter.format(it.priceAmountMicros.toDouble() * selectedQuantity / 1_000_000.0)
        }.getOrDefault("${it.formattedPrice} × $selectedQuantity")
    } ?: fallbackTotal

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("VibeSync PDF Pro") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Upgrade to VibeSync PDF Pro",
                style = MaterialTheme.typography.headlineSmall
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Per-device Pro Pass", style = MaterialTheme.typography.titleMedium)
            }

            listOf(
                "✨ Watermark-Free Exports",
                "🚫 100% Ad-Free Experience",
                "🛡️ Custom Watermarks",
                "📂 Multi-Year Stacking (up to ${BillingConstants.MAX_STACK_YEARS} years)"
            ).forEach { benefit ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(benefit, style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (entitlement.isProActive) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Badge { Text("Pro Pass Active — ${entitlement.daysRemaining} days remaining") }
                    }
                }
                Button(
                    onClick = { billingManager.launchPurchase(activity, 1) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Extend +1 Year")
                }
            }

            if (pending) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        BillingConstants.PURCHASE_PENDING_MESSAGE,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            Text("Choose how many years to add", style = MaterialTheme.typography.titleMedium)
            Text("$selectedQuantity ${if (selectedQuantity == 1) BillingConstants.YEAR_LABEL else BillingConstants.YEARS_LABEL}")
            Slider(
                value = quantity,
                onValueChange = { quantity = it },
                valueRange = 1f..BillingConstants.MAX_STACK_YEARS.toFloat(),
                steps = (BillingConstants.MAX_STACK_YEARS - 2).coerceAtLeast(0)
            )
            Text(
                text = if (offer == null) {
                    "Price: $fallbackTotal"
                } else {
                    "Price: ${offer.formattedPrice} × $selectedQuantity  •  Total: $totalPrice"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Button(
                onClick = { billingManager.launchPurchase(activity, selectedQuantity) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Unlock Pro Now ($totalPrice)")
            }
            TextButton(
                onClick = {
                    billingManager.refreshPurchases { active ->
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (active) "Pro Pass is active on this device." else "No active Pro pass found."
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Check Status / Restore")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "This pass is tied to this device and cannot be restored after uninstall or clearing app data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
