package com.vibesync.toolkit.pdf

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Exercises dashboard navigation in the real MainActivity-hosted Compose hierarchy.
 * This avoids an implicit test-only host whose semantics root can be absent at startup.
 */
class DashboardNavigationIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun allToolkitCardsExposeExpectedRoutesAndPrivacyDialogOpens() {
        val viewModel = mockk<DashboardViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(DashboardUiState())
        val routes = mutableListOf<String>()
        var scannerLaunched = false

        setTestContent {
            DashboardScreen(
                viewModel = viewModel,
                onNavigateToViewer = {},
                onNavigateToEditor = {},
                onNavigateToTool = { routes.add(it) },
                onLaunchScanner = { scannerLaunched = true }
            )
        }

        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_convert"))
        composeRule.onNodeWithText("Images to PDF").assertIsDisplayed()
        composeRule.onNodeWithText("Extract Images").assertIsDisplayed()
        composeRule.onNodeWithTag("tool_convert").performClick()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_extract"))
        composeRule.onNodeWithTag("tool_extract").performClick()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_scanner"))
        composeRule.onNodeWithTag("tool_scanner").performClick()

        assertEquals(listOf("convert", "extract", "scan_history"), routes)
        assertEquals(false, scannerLaunched)

        composeRule.onNodeWithContentDescription("Privacy & Terms").performClick()
        composeRule.onNodeWithText("Privacy & Data Safety Guarantee").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy_legal_links").assertExists()
        composeRule.onNodeWithTag("privacy_public_info").assertExists()
        composeRule.onNodeWithTag("privacy_policy_link").assertExists()
        composeRule.onNodeWithTag("privacy_license_link").assertExists()
        composeRule.onNodeWithTag("privacy_terms_link").assertExists()
        composeRule.onNodeWithTag("privacy_close_button").assertExists()
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.waitForIdle()
        composeRule.activity.runOnUiThread {
            val composeView = findComposeView(composeRule.activity.window.decorView)
                ?: error("MainActivity ComposeView was not found")
            composeView.setContent { VibeSyncPdfTheme { content() } }
        }
        composeRule.waitForIdle()
    }

    private fun findComposeView(view: View): ComposeView? {
        if (view is ComposeView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findComposeView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
