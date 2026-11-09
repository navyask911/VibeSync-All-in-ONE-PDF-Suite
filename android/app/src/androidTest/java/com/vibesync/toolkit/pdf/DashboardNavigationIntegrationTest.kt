package com.vibesync.toolkit.pdf

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

/**
 * Exercises the real MainActivity dashboard and NavHost destinations without replacing
 * the Activity's root composition in a test-only Compose host.
 */
class DashboardNavigationIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allToolkitCardsNavigateAndPrivacyDialogOpens() {
        // MainActivity performs application setup before publishing its Compose content.
        // Wait for the real dashboard hierarchy instead of querying a not-yet-attached root.
        composeRule.waitUntil(15_000) {
            runCatching {
                composeRule.onNodeWithTag("dashboard_list").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_convert"))
        composeRule.onNodeWithTag("tool_convert").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Images to PDF").assertIsDisplayed()
        navigateBackToDashboard()

        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_extract"))
        composeRule.onNodeWithTag("tool_extract").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Extract Images").assertIsDisplayed()
        navigateBackToDashboard()

        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_scanner"))
        composeRule.onNodeWithTag("tool_scanner").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Scan History").assertIsDisplayed()
        navigateBackToDashboard()

        composeRule.onNodeWithContentDescription("Privacy & Terms").performClick()
        composeRule.onNodeWithText("Privacy & Data Safety Guarantee").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_legal_links").assertExists()
        composeRule.onNodeWithTag("privacy_public_info").assertExists()
        composeRule.onNodeWithTag("privacy_policy_link").assertExists()
        composeRule.onNodeWithTag("privacy_license_link").assertExists()
        composeRule.onNodeWithTag("privacy_terms_link").assertExists()
        composeRule.onNodeWithTag("privacy_close_button").assertIsDisplayed()
    }

    private fun navigateBackToDashboard() {
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_list").assertIsDisplayed()
    }
}
