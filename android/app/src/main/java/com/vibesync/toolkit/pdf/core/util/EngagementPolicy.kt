package com.vibesync.toolkit.pdf.core.util

import android.content.Context

internal object EngagementPolicy {
    const val UPDATE_CHECK_COOLDOWN_MS = 6L * 60L * 60L * 1000L
    const val UPDATE_PROMPT_COOLDOWN_MS = 24L * 60L * 60L * 1000L
    const val OFFLINE_PROMPT_COOLDOWN_MS = 6L * 60L * 60L * 1000L

    fun shouldCheckForUpdate(
        isInternetValidated: Boolean,
        nowMs: Long,
        lastCheckMs: Long,
        force: Boolean = false,
        immediateUpdateInProgress: Boolean = false,
        cooldownMs: Long = UPDATE_CHECK_COOLDOWN_MS
    ): Boolean {
        if (!isInternetValidated) return false
        if (force || immediateUpdateInProgress || lastCheckMs <= 0L) return true
        return nowMs >= lastCheckMs && nowMs - lastCheckMs >= cooldownMs
    }

    fun shouldPromptForUpdate(
        updateAvailable: Boolean,
        isInternetValidated: Boolean,
        nowMs: Long,
        snoozeUntilMs: Long
    ): Boolean = updateAvailable && isInternetValidated && nowMs >= snoozeUntilMs
    const val REVIEW_COOLDOWN_MS = 120L * 24L * 60L * 60L * 1000L
    const val SESSION_GAP_MS = 30L * 60L * 1000L

    fun shouldShowOfflinePrompt(
        isInternetValidated: Boolean,
        nowMs: Long,
        lastShownMs: Long,
        cooldownMs: Long = OFFLINE_PROMPT_COOLDOWN_MS
    ): Boolean {
        if (isInternetValidated) return false
        return lastShownMs <= 0L || (nowMs >= lastShownMs && nowMs - lastShownMs >= cooldownMs)
    }

    fun shouldRequestReview(
        sessions: Int,
        successfulOperations: Int,
        nowMs: Long,
        lastAttemptMs: Long,
        cooldownMs: Long = REVIEW_COOLDOWN_MS
    ): Boolean {
        if (sessions < 2 || successfulOperations < 3) return false
        return lastAttemptMs <= 0L || (nowMs >= lastAttemptMs && nowMs - lastAttemptMs >= cooldownMs)
    }
}

/** Tracks request eligibility; Google Play does not disclose whether a review was submitted or shown. */
internal class ReviewEligibilityTracker(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun recordSessionIfDue(nowMs: Long = System.currentTimeMillis()) {
        val lastSession = preferences.getLong(KEY_LAST_SESSION, 0L)
        if (lastSession <= 0L || nowMs < lastSession || nowMs - lastSession >= EngagementPolicy.SESSION_GAP_MS) {
            preferences.edit()
                .putInt(KEY_SESSIONS, preferences.getInt(KEY_SESSIONS, 0) + 1)
                .putLong(KEY_LAST_SESSION, nowMs)
                .apply()
        }
    }

    fun recordSuccessfulOperation() {
        preferences.edit()
            .putInt(KEY_SUCCESSFUL_OPERATIONS, preferences.getInt(KEY_SUCCESSFUL_OPERATIONS, 0) + 1)
            .apply()
    }

    fun isEligible(nowMs: Long = System.currentTimeMillis()): Boolean =
        EngagementPolicy.shouldRequestReview(
            sessions = preferences.getInt(KEY_SESSIONS, 0),
            successfulOperations = preferences.getInt(KEY_SUCCESSFUL_OPERATIONS, 0),
            nowMs = nowMs,
            lastAttemptMs = preferences.getLong(KEY_LAST_ATTEMPT, 0L)
        )

    fun markAttempt(nowMs: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(KEY_LAST_ATTEMPT, nowMs).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "vibesync_review_eligibility"
        const val KEY_LAST_SESSION = "last_session_time_ms"
        const val KEY_SESSIONS = "session_count"
        const val KEY_SUCCESSFUL_OPERATIONS = "successful_pdf_operations"
        const val KEY_LAST_ATTEMPT = "last_review_attempt_ms"
    }
}
