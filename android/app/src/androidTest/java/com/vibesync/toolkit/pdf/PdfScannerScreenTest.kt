package com.vibesync.toolkit.pdf

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.scanner.PdfScannerScreen
import org.junit.Rule
import org.junit.Test

class PdfScannerScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun scannerExplainsScopedFolderAccessAndOffersAddFolderAction() {
        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfScannerScreen(
                    onNavigateBack = {},
                    onOpenPdf = {},
                    autoLaunchFolderPicker = false
                )
            }
        }

        composeTestRule.onNodeWithText("Smart PDF Scanner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("smart_scan_add_folder").assertIsDisplayed()
        composeTestRule.onNodeWithTag("smart_scan_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Choose Download, WhatsApp, Telegram, or another folder. VibeSync only scans folders you select."
        ).assertIsDisplayed()
    }
}
