package com.vibesync.toolkit.pdf

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.pdf.DiscoveredPdf
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardUiState
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardViewModel
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardScreen
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun allToolkitCardsExposeExpectedRoutesAndPrivacyDialogOpens() {
        val viewModel = mockk<DashboardViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(DashboardUiState())
        val routes = mutableListOf<String>()
        var scannerLaunched = false

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToViewer = {},
                    onNavigateToTool = { routes.add(it) },
                    onLaunchScanner = { scannerLaunched = true },
                    onRequestStoragePermission = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_convert"))
        composeTestRule.onNodeWithText("Images to PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Extract Images").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tool_convert").performClick()
        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_extract"))
        composeTestRule.onNodeWithTag("tool_extract").performClick()
        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_scanner"))
        composeTestRule.onNodeWithTag("tool_scanner").performClick()

        assertEquals(listOf("convert", "extract"), routes)
        assertTrue(scannerLaunched)

        composeTestRule.onNodeWithContentDescription("Privacy & Terms").performClick()
        composeTestRule.onNodeWithText("Privacy & Data Safety Guarantee").assertIsDisplayed()
    }
}