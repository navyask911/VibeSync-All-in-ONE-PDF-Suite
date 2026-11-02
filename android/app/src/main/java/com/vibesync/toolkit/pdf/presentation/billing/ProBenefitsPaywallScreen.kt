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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.billing.BillingConstants
import com.vibesync.toolkit.pdf.data.billing.LocalAdBillingManager
import kotlinx.coroutines.launch

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
    val productOwned by billingManager.isProOwned.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val restoreSuccessMessage = stringResource(R.string.pro_pass_restore_success)
    val restoreMissingMessage = stringResource(R.string.pro_pass_restore_not_found)

    LaunchedEffect(billingManager) {
        billingManager.events.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    LaunchedEffect(connected) {
        if (connected) billingManager.queryProductDetails()
        else snackbarHostState.showSnackbar(BillingConstants.BILLING_UNAVAILABLE_MESSAGE)
    }

    val offer = productDetails?.oneTimePurchaseOfferDetails
    val priceLabel = offer?.formattedPrice ?: BillingConstants.PRO_PASS_DISPLAY_PRICE_FALLBACK

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
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Upgrade to VibeSync PDF Pro", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.pro_pass_one_time_copy), style = MaterialTheme.typography.titleMedium)
            }
            listOf(
                "✨ Watermark-Free Exports",
                "🚫 100% Ad-Free Experience",
                "🛡️ Custom Watermarks"
            ).forEach { benefit ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(benefit, style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (entitlement.isProActive) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Badge { Text("Pro Pass Active — ${entitlement.daysRemaining} days remaining") }
                    }
                }
            } else if (productOwned) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(stringResource(R.string.pro_pass_expired_owned), modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(priceLabel, style = MaterialTheme.typography.titleLarge)
                Button(
                    onClick = { billingManager.launchPurchase(activity) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connected && productDetails != null
                ) { Text("Unlock Pro Now") }
            }

            if (pending) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(BillingConstants.PURCHASE_PENDING_MESSAGE, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.titleSmall)
                }
            }

            TextButton(
                onClick = {
                    scope.launch {
                        billingManager.restoreProLicenseFromPlayStore(activity.applicationContext)
                        snackbarHostState.showSnackbar(
                            if (billingManager.state.value.isProActive) restoreSuccessMessage else restoreMissingMessage
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = connected
            ) { Text(stringResource(R.string.pro_pass_restore_action)) }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pro_pass_restore_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
