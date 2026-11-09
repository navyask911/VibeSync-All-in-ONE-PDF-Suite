package com.vibesync.toolkit.pdf

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardScreen
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardUiState
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Exercises Dashboard card dispatch and privacy UI in a deterministic Compose host.
 * MainActivity's smart_scan NavHost destination is wired separately in production code.
 */
class DashboardNavigationIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allToolkitCardsDispatchRoutesAndPrivacyDialogOpens() {
        val viewModel = mockk<DashboardViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(DashboardUiState())
        val destinations = mutableListOf<String>()

        composeRule.setContent {
            VibeSyncPdfTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToViewer = {},
                    onNavigateToEditor = {},
                    onNavigateToTool = { destinations.add(it) },
                    onLaunchScanner = {},
                    onNavigateToSmartScan = { destinations.add("smart_scan") }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_convert"))
        composeRule.onNodeWithTag("tool_convert").performClick()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_extract"))
        composeRule.onNodeWithTag("tool_extract").performClick()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_scanner"))
        composeRule.onNodeWithTag("tool_scanner").performClick()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_smart_scan"))
        composeRule.onNodeWithTag("tool_smart_scan").performClick()

        assertEquals(
            listOf("convert", "extract", "scan_history", "smart_scan"),
            destinations
        )

        composeRule.onNodeWithContentDescription("Privacy & Terms").performClick()
        composeRule.onNodeWithText("Privacy & Data Safety Guarantee").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_legal_links").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_public_info").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_policy_link").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_license_link").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_terms_link").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_close_button").assertIsDisplayed()
    }
}
