package com.vibesync.toolkit.pdf

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
                    onNavigateToEditor = {},
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
        composeTestRule.onNodeWithTag("privacy_legal_links").assertExists()
        composeTestRule.onNodeWithTag("privacy_public_info").assertExists()
        composeTestRule.onNodeWithTag("privacy_policy_link").assertExists()
        composeTestRule.onNodeWithTag("privacy_license_link").assertExists()
        composeTestRule.onNodeWithTag("privacy_terms_link").assertExists()
        composeTestRule.onNodeWithTag("privacy_close_button").assertExists()
    }

    @Test
    fun languagePickerIncludesWorldwideLocales() {
        val viewModel = mockk<DashboardViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(DashboardUiState())

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToViewer = {},
                    onNavigateToEditor = {},
                    onNavigateToTool = {},
                    onLaunchScanner = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Language").performClick()
        composeTestRule.onNodeWithText("繁體中文").assertExists()
        composeTestRule.onNodeWithText("Tiếng Việt").assertExists()
        composeTestRule.onNodeWithText("தமிழ்").assertExists()
        composeTestRule.onNodeWithText("తెలుగు").assertExists()
        composeTestRule.onNodeWithText("ಕನ್ನಡ").assertExists()
        composeTestRule.onNodeWithText("मराठी").assertExists()
        composeTestRule.onNodeWithText("ગુજરાતી").assertExists()
        composeTestRule.onNodeWithText("עברית").assertExists()
        composeTestRule.onNodeWithText("ไทย").assertExists()
    }

    @Test
    fun pdfListShowsTenByDefaultAndSupportsExpandCollapseAndPersistentHide() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("dashboard_pdf_section_preferences", Context.MODE_PRIVATE)
            .edit().remove("pdf_section_visible").commit()
        val pdfs = (1..11).map { index ->
            DiscoveredPdf(
                id = index.toLong(),
                name = "sample_$index.pdf",
                uri = android.net.Uri.parse("content://test/documents/$index.pdf"),
                sizeBytes = 1024L,
                sizeFormatted = "1 KB",
                lastModified = (100 - index).toLong(),
                formattedDate = "Oct 23, 2026"
            )
        }
        val viewModel = mockk<DashboardViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(DashboardUiState(discoveredPdfs = pdfs))

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToViewer = {},
                    onNavigateToEditor = {},
                    onNavigateToTool = {},
                    onLaunchScanner = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasText("sample_1.pdf"))
        composeTestRule.onNodeWithText("sample_1.pdf").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("sample_11.pdf").assertCountEquals(0)
        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasText("sample_10.pdf"))
        composeTestRule.onNodeWithText("sample_10.pdf").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("sample_11.pdf").assertCountEquals(0)
        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("pdf_show_all_toggle"))
        composeTestRule.onNodeWithTag("pdf_show_all_toggle").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasText("sample_11.pdf"))
        composeTestRule.onNodeWithText("sample_11.pdf").assertIsDisplayed()

        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("pdf_show_all_toggle"))
        composeTestRule.onNodeWithTag("pdf_show_all_toggle").performClick()
        composeTestRule.onAllNodesWithText("sample_11.pdf").assertCountEquals(0)

        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("pdf_section_close"))
        composeTestRule.onNodeWithTag("pdf_section_close").performClick()
        composeTestRule.onNodeWithTag("pdf_show_section").assertExists()
        composeTestRule.onAllNodesWithText("sample_1.pdf").assertCountEquals(0)
        assertEquals(false, context.getSharedPreferences("dashboard_pdf_section_preferences", Context.MODE_PRIVATE).getBoolean("pdf_section_visible", true))

        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("pdf_show_section"))
        composeTestRule.onNodeWithTag("pdf_show_section").performClick()
        composeTestRule.onNodeWithTag("dashboard_list").performScrollToNode(hasText("sample_1.pdf"))
        composeTestRule.onNodeWithText("sample_1.pdf").assertIsDisplayed()
        assertEquals(true, context.getSharedPreferences("dashboard_pdf_section_preferences", Context.MODE_PRIVATE).getBoolean("pdf_section_visible", false))
    }

}