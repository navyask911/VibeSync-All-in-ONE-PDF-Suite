package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.core.util.EngagementPolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngagementPolicyTest {
    @Test
    fun offlinePromptIsNotEligibleWhenInternetIsValidated() {
        assertFalse(EngagementPolicy.shouldShowOfflinePrompt(true, 100_000L, 0L))
    }

    @Test
    fun offlinePromptHonorsCooldown() {
        val now = 10_000_000L
        assertFalse(EngagementPolicy.shouldShowOfflinePrompt(false, now, now - 1_000L, 5_000L))
        assertTrue(EngagementPolicy.shouldShowOfflinePrompt(false, now, now - 5_000L, 5_000L))
    }

    @Test
    fun reviewRequiresMultipleSessionsAndSuccessfulOperations() {
        assertFalse(EngagementPolicy.shouldRequestReview(1, 8, 100_000L, 0L))
        assertFalse(EngagementPolicy.shouldRequestReview(3, 2, 100_000L, 0L))
        assertTrue(EngagementPolicy.shouldRequestReview(2, 3, 100_000L, 0L))
    }

    @Test
    fun reviewPromptHonorsAttemptCooldown() {
        val now = 1_000_000_000L
        assertFalse(EngagementPolicy.shouldRequestReview(4, 10, now, now - 1_000L, 5_000L))
        assertTrue(EngagementPolicy.shouldRequestReview(4, 10, now, now - 5_000L, 5_000L))
    }
}
