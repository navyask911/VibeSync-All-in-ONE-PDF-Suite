package com.vibesync.toolkit.pdf.data.billing

data class ProEntitlementState(
    val isProActive: Boolean,
    val expiryEpochMillis: Long,
    val daysRemaining: Int
)
