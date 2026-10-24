package com.vibesync.toolkit.pdf

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.scanner.ScanHistoryEntry
import com.vibesync.toolkit.pdf.presentation.scanner.ScanHistoryScreen
import com.vibesync.toolkit.pdf.presentation.scanner.ScanHistoryUiState
import com.vibesync.toolkit.pdf.presentation.scanner.ScannerHistoryViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ScanHistoryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyHistoryOffersNewScanAction() {
        val viewModel = mockk<ScannerHistoryViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(ScanHistoryUiState())
        var newScanRequested = false
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                ScanHistoryScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onNewScan = { newScanRequested = true },
                    onOpenScan = {},
                    onSharePdf = {},
                    onShareImages = {},
                    onSaveToDownloads = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("scan_history_empty").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.scan_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("scan_history_empty_new_scan").performClick()
        assertTrue(newScanRequested)
    }

    @Test
    fun savedScanShowsMetadataAndManagementMenu() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.filesDir, "scans_ui_test").apply { mkdirs() }
        val scan = File(folder, "Scan_123.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val state = ScanHistoryUiState(
            scans = listOf(
                ScanHistoryEntry(file = scan, pageCount = 2, scannedAtMillis = 1_792_000_000_000L, pageImageCount = 2)
            )
        )
        val viewModel = mockk<ScannerHistoryViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(state)

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                ScanHistoryScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onNewScan = {},
                    onOpenScan = {},
                    onSharePdf = {},
                    onShareImages = {},
                    onSaveToDownloads = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Scan_123").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.scan_page_count, 2)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("scan_thumbnail_Scan_123.pdf").performTouchInput { longClick() }
        composeTestRule.onNodeWithText(context.getString(R.string.action_share_jpg_images)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.scan_save_downloads)).performScrollTo().assertIsDisplayed()
        folder.deleteRecursively()
    }
}
