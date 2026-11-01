package com.vibesync.toolkit.pdf.core.billing

object BillingConstants {
    const val PRO_PASS_PRODUCT_ID = "vibesync_pro_1y"
    const val PRO_PASS_DURATION_MS = 365L * 24 * 60 * 60 * 1000L
    const val MAX_STACK_YEARS = 10
    const val PRO_PASS_DISPLAY_PRICE_FALLBACK = "₹299 / Year"

    const val MILLIS_PER_DAY = 86_400_000L
    const val YEAR_LABEL = "Year"
    const val YEARS_LABEL = "years"
    const val YEAR_PRICE_SUFFIX = " / Year"

    const val BILLING_UNAVAILABLE_MESSAGE = "Billing unavailable — try again later or update the app."
    const val PURCHASE_PENDING_MESSAGE = "Purchase pending approval"
}
