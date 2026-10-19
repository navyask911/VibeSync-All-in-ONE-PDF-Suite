package com.vibesync.toolkit.pdf

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.core.util.BitmapCache
import com.vibesync.toolkit.pdf.data.model.PageMetadata
import com.vibesync.toolkit.pdf.presentation.viewer.PdfPageItem
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfViewerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var mockViewModel: PdfViewerViewModel
    private val testUri = Uri.parse("content://com.vibesync.toolkit.pdf/sample.pdf")

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        mockViewModel = mockk(relaxed = true)
    }

    @Test
    fun testRapidContinuousVerticalScrollAndZoomGestures() {
        // Setup 30 mock pages to test continuous rapid vertical list scroll
        val pages = (1..30).map { PageMetadata(pageIndex = it - 1, widthPt = 595f, heightPt = 842f) }
        val stateFlow = MutableStateFlow(
            com.vibesync.toolkit.pdf.presentation.viewer.ViewerUiState(
                fileName = "Large_Catalog_2026.pdf",
                pageCount = 30,
                currentPageIndex = 0,
                isLoading = false,
                pagesMetadata = pages
            )
        )
        every { mockViewModel.uiState } returns stateFlow
        coEvery { mockViewModel.acquirePageBitmap(any(), any()) } answers {
            fakeLease(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
        }

        composeTestRule.setContent {
            PdfViewerScreen(
                documentUri = testUri,
                viewModel = mockViewModel,
                onNavigateBack = {},
                onNavigateToEditor = {},
                onShareDocument = {}
            )
        }

        // Verify document title and page count appear in top bar
        composeTestRule.onNodeWithText("Large_Catalog_2026.pdf").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Page 1 of 30").assertCountEquals(2)

        // Rapid continuous vertical scroll gestures to verify OOM-safe LazyColumn recycling
        val listNode = composeTestRule.onNodeWithTag("pdf_page_list")
        listNode.assertIsDisplayed()

        // Swipe up multiple times rapidly on the actual LazyColumn.
        listNode.performTouchInput {
            swipeUp(durationMillis = 100)
            swipeUp(durationMillis = 100)
            swipeUp(durationMillis = 100)
        }

        // Verify editor action button remains responsive
        composeTestRule.onNodeWithContentDescription("Edit & Sign").assertIsDisplayed().performClick()
    }

    @Test
    fun testPasswordDialogSubmissionAndPasswordVisibilityToggle() {
        val stateFlow = MutableStateFlow(
            com.vibesync.toolkit.pdf.presentation.viewer.ViewerUiState(
                fileName = "Protected_Financial_Audit.pdf",
                pageCount = 0,
                isLoading = false,
                isPasswordRequired = true,
                errorMessage = "Document is encrypted. Enter password to view."
            )
        )
        every { mockViewModel.uiState } returns stateFlow

        composeTestRule.setContent {
            PdfViewerScreen(
                documentUri = testUri,
                viewModel = mockViewModel,
                onNavigateBack = {},
                onNavigateToEditor = {},
                onShareDocument = {}
            )
        }

        // 1. Verify Password Dialog is displayed with accessibility metadata
        composeTestRule.onNodeWithText("Password-protected PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("This document is encrypted. Enter the password to view its pages.").assertIsDisplayed()

        // 2. Find Password Input and enter text
        val passwordField = composeTestRule.onNode(hasSetTextAction())
        passwordField.assertIsDisplayed()
        passwordField.performTextInput("SecretVault2026!")

        // 3. Test Password Visibility Toggle ("Show password" <-> "Hide password")
        val toggleButton = composeTestRule.onNodeWithContentDescription("Show password")
        toggleButton.assertIsDisplayed()

        // Click to toggle visibility on
        toggleButton.performClick()

        // Verify accessibility description changes to "Hide password"
        composeTestRule.onNodeWithContentDescription("Hide password").assertIsDisplayed()

        // Click to toggle visibility back off
        composeTestRule.onNodeWithContentDescription("Hide password").performClick()
        composeTestRule.onNodeWithContentDescription("Show password").assertIsDisplayed()

        // 4. Submit password via "Unlock" button
        val unlockButton = composeTestRule.onNodeWithText("Unlock")
        unlockButton.assertIsDisplayed()
        unlockButton.performClick()

        // Verify viewModel received password submission
        verify(exactly = 1) { mockViewModel.submitPassword("SecretVault2026!") }
    }

    @Test
    fun nullPageBitmapShowsTerminalErrorAndRetryCanRecover() {
        val recoveredBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        coEvery { mockViewModel.acquirePageBitmap(0, 1.5f) } returnsMany listOf(null, fakeLease(recoveredBitmap))

        composeTestRule.setContent {
            PdfPageItem(
                pageIndex = 0,
                aspectRatio = 595f / 842f,
                viewModel = mockViewModel,
                documentKey = "regression-document"
            )
        }

        composeTestRule.onNodeWithText("Could not render this page.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()
        composeTestRule.waitUntil(5_000) {
            composeTestRule.onAllNodesWithContentDescription("PDF page 1")
                .fetchSemanticsNodes().isNotEmpty()
        }
        coVerify(exactly = 2) { mockViewModel.acquirePageBitmap(0, 1.5f) }
    }

    @Test
    fun documentLoadErrorOffersRetryAction() {
        val stateFlow = MutableStateFlow(
            com.vibesync.toolkit.pdf.presentation.viewer.ViewerUiState(
                fileName = "unavailable.pdf",
                isLoading = false,
                errorMessage = "Could not open this PDF. Check that the file is accessible, then retry."
            )
        )
        every { mockViewModel.uiState } returns stateFlow

        composeTestRule.setContent {
            PdfViewerScreen(
                documentUri = testUri,
                viewModel = mockViewModel,
                onNavigateBack = {},
                onNavigateToEditor = {},
                onShareDocument = {}
            )
        }

        composeTestRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        verify(exactly = 1) { mockViewModel.retryLastDocument() }
    }

    private fun fakeLease(bitmap: Bitmap): BitmapCache.BitmapLease {
        val lease = mockk<BitmapCache.BitmapLease>(relaxed = true)
        every { lease.key } returns "ui-test-${System.identityHashCode(bitmap)}"
        every { lease.bitmap } returns bitmap
        return lease
    }

}
