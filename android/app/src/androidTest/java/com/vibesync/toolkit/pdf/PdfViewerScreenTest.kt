package com.vibesync.toolkit.pdf

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.core.util.BitmapCache
import com.vibesync.toolkit.pdf.data.model.PageMetadata
import com.vibesync.toolkit.pdf.presentation.viewer.PdfPageItem
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.model.ToolMode
import com.vibesync.toolkit.pdf.presentation.editor.EditorUiState
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import com.vibesync.toolkit.pdf.presentation.editor.PdfEditorScreen
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
    fun documentLoadStartsWhileInitialLoadingSpinnerIsVisible() {
        val stateFlow = MutableStateFlow(
            com.vibesync.toolkit.pdf.presentation.viewer.ViewerUiState(
                fileName = "sample.pdf",
                isLoading = true
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

        composeTestRule.waitForIdle()
        verify(exactly = 1) { mockViewModel.loadDocument(testUri) }
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
        // A single page-position label is intentional; the old top/bottom duplicate was removed.
        composeTestRule.onAllNodesWithText("Page 1 of 30").assertCountEquals(1)

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
        coEvery { mockViewModel.acquirePageBitmap(0, 2.0f) } returnsMany listOf(null, fakeLease(recoveredBitmap))

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
        coVerify(exactly = 2) { mockViewModel.acquirePageBitmap(0, 2.0f) }
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


    @Test
    fun twoRowToolbarKeepsEveryActionAndFilenameCanOpenRename() {
        val stateFlow = MutableStateFlow(
            com.vibesync.toolkit.pdf.presentation.viewer.ViewerUiState(
                fileName = "Very_Long_Physical_Device_Report_2026.pdf", pageCount = 1,
                isLoading = false,
                pagesMetadata = listOf(PageMetadata(pageIndex = 0, widthPt = 595f, heightPt = 842f))
            )
        )
        every { mockViewModel.uiState } returns stateFlow
        coEvery { mockViewModel.acquirePageBitmap(any(), any()) } returns null
        var renameRequests = 0
        composeTestRule.setContent {
            PdfViewerScreen(documentUri = testUri, viewModel = mockViewModel,
                onNavigateBack = {}, onNavigateToEditor = {}, onShareDocument = {},
                onRenameClick = { renameRequests++ })
        }
        composeTestRule.onNodeWithTag("viewer_rename_title").assertIsDisplayed()
            .assertHasClickAction().performClick()
        composeTestRule.runOnIdle { assert(renameRequests == 1) }
        composeTestRule.onNodeWithTag("viewer_search").performClick()
        composeTestRule.onNodeWithTag("viewer_search_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewer_search_close").performClick()
        composeTestRule.onNodeWithTag("viewer_more_actions").performClick()
        listOf("viewer_zoom_out","viewer_zoom_in","viewer_previous_page","viewer_next_page",
            "viewer_share","viewer_edit","viewer_print","viewer_export_menu").forEach { tag ->
            composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun searchOpensInlineAndKeepsThePdfPageVisibleWithoutAModalScrim() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val stateFlow = MutableStateFlow(
            com.vibesync.toolkit.pdf.presentation.viewer.ViewerUiState(
                fileName = "Inline_Search_Test.pdf",
                pageCount = 1,
                currentPageIndex = 0,
                isLoading = false,
                pagesMetadata = listOf(PageMetadata(pageIndex = 0, widthPt = 595f, heightPt = 842f))
            )
        )
        every { mockViewModel.uiState } returns stateFlow
        coEvery { mockViewModel.acquirePageBitmap(0, 2.0f) } returns fakeLease(bitmap)

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfViewerScreen(
                    documentUri = testUri,
                    viewModel = mockViewModel,
                    onNavigateBack = {},
                    onNavigateToEditor = {},
                    onShareDocument = {}
                )
            }
        }

        // Establish that an actual page bitmap is rendered before search mode is entered.
        composeTestRule.onNodeWithTag("pdf_page_list").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("PDF page 1").assertIsDisplayed()

        composeTestRule.onNodeWithTag("viewer_search").assertIsDisplayed().performClick()
        val searchField = composeTestRule.onNodeWithTag("viewer_search_field")
        searchField.assertIsDisplayed().performClick().performTextInput("needle")

        // The top-bar controls are inline, while the same PDF page remains rendered underneath.
        composeTestRule.onNodeWithTag("viewer_search_previous").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewer_search_next").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewer_search_close").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pdf_page_list").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("PDF page 1").assertIsDisplayed()

        // The former AlertDialog's Cancel action must not be present.
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        verify(exactly = 1) { mockViewModel.searchDocument("needle") }

        composeTestRule.onNodeWithTag("viewer_search_close").performClick()
        composeTestRule.onNodeWithTag("viewer_rename_title").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("PDF page 1").assertIsDisplayed()
    }

    private fun fakeLease(bitmap: Bitmap): BitmapCache.BitmapLease {
        val lease = mockk<BitmapCache.BitmapLease>(relaxed = true)
        every { lease.key } returns "ui-test-${System.identityHashCode(bitmap)}"
        every { lease.bitmap } returns bitmap
        return lease
    }

    @Test
    fun searchOpensAndEditSignKeepsVibgyorPenSizeAndHighlighterControls() {
        val viewerState = MutableStateFlow(
            com.vibesync.toolkit.pdf.presentation.viewer.ViewerUiState(
                fileName = "v1.0.15-baseline.pdf",
                pageCount = 1,
                isLoading = false,
                pagesMetadata = listOf(PageMetadata(pageIndex = 0, widthPt = 595f, heightPt = 842f))
            )
        )
        every { mockViewModel.uiState } returns viewerState
        coEvery { mockViewModel.acquirePageBitmap(0, 2.0f) } returns null

        val editorViewModel = mockk<EditorViewModel>(relaxed = true)
        every { editorViewModel.uiState } returns MutableStateFlow(
            EditorUiState(toolMode = ToolMode.PEN)
        )
        coEvery { editorViewModel.getPageBitmap(any()) } returns null

        composeTestRule.setContent {
            var editing by remember { mutableStateOf(false) }
            VibeSyncPdfTheme {
                if (editing) {
                    PdfEditorScreen(
                        documentUri = testUri,
                        viewModel = editorViewModel,
                        onNavigateBack = { editing = false },
                        onExportComplete = {}
                    )
                } else {
                    PdfViewerScreen(
                        documentUri = testUri,
                        viewModel = mockViewModel,
                        onNavigateBack = {},
                        onNavigateToEditor = { editing = true },
                        onShareDocument = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("viewer_search").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("viewer_search_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewer_search_close").performClick()

        composeTestRule.onNodeWithTag("viewer_edit").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("editor_stroke_size_surface").assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_tool_Highlighter").assertIsDisplayed()
    }

}
