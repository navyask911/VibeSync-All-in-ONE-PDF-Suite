package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.editor.EditorUiState
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import com.vibesync.toolkit.pdf.data.model.ToolMode
import com.vibesync.toolkit.pdf.presentation.editor.HighlighterPalette
import com.vibesync.toolkit.pdf.presentation.editor.PdfEditorScreen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test

class PdfEditorScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun textToolOpensDialogAndPlacesText() {
        val viewModel = mockk<EditorViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(EditorUiState(toolMode = ToolMode.TEXT))
        coEvery { viewModel.getPageBitmap(any()) } returns null

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfEditorScreen(
                    documentUri = Uri.parse("content://test/sample.pdf"),
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onExportComplete = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("editor_tool_Text").performClick()
        composeTestRule.onNodeWithTag("editor_canvas_overlay").performTouchInput { click(center) }
        composeTestRule.onNodeWithText("Add Text Note").assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Hello")
        composeTestRule.onNodeWithText("Place Text").performClick()

        verify(exactly = 1) { viewModel.addTextAnnotation("Hello", any(), any()) }
    }

    @Test
    fun firstPageIsRequestedOnlyAfterDocumentLoadingCompletes() {
        val viewModel = mockk<EditorViewModel>(relaxed = true)
        val state = MutableStateFlow(EditorUiState(isDocumentLoading = true, isDocumentLoaded = false))
        every { viewModel.uiState } returns state
        val renderCalls = AtomicInteger(0)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        coEvery { viewModel.getPageBitmap(any()) } coAnswers {
            renderCalls.incrementAndGet()
            bitmap
        }

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfEditorScreen(
                    documentUri = Uri.parse("content://test/sample.pdf"),
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onExportComplete = {}
                )
            }
        }

        composeTestRule.runOnIdle {
            assert(renderCalls.get() == 0) { "The editor must not request a page before its document is opened." }
            state.value = state.value.copy(
                isDocumentLoading = false,
                isDocumentLoaded = true,
                totalPages = 1
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) { renderCalls.get() == 1 }
        composeTestRule.onNodeWithContentDescription("PDF page canvas").assertIsDisplayed()
    }

    @Test
    fun highlighterUsesVibgyorLightAndRecordsOneContinuousTransparentStroke() {
        val viewModel = mockk<EditorViewModel>(relaxed = true)
        val state = MutableStateFlow(EditorUiState(isDocumentLoading = false, isDocumentLoaded = true))
        every { viewModel.uiState } returns state
        every { viewModel.selectTool(any()) } answers {
            state.value = state.value.copy(toolMode = firstArg())
        }
        every { viewModel.selectColor(any()) } answers {
            state.value = state.value.copy(selectedColor = firstArg())
        }
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        coEvery { viewModel.getPageBitmap(any()) } returns bitmap

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfEditorScreen(
                    documentUri = Uri.parse("content://test/sample.pdf"),
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onExportComplete = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("editor_tool_Highlighter").performClick()
        composeTestRule.onNodeWithTag("editor_color_palette").assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_highlighter_color_violet").performClick()
        composeTestRule.onNodeWithTag("editor_bottom_toolbar").assertIsDisplayed()

        composeTestRule.onNodeWithTag("editor_canvas_overlay").performTouchInput {
            down(0, center)
            moveTo(0, center + Offset(36f, 8f))
            moveTo(0, center + Offset(72f, 18f))
            up(0)
        }

        verify(exactly = 1) {
            viewModel.selectColor(HighlighterPalette.colors.first().color)
        }
        verify(exactly = 1) {
            viewModel.addStroke(match {
                it.isHighlighter &&
                    it.alpha in 0.30f..0.40f &&
                    it.colorArgb == HighlighterPalette.colors.first().color.toArgb() &&
                    it.points.size >= 3
            })
        }
        assert(HighlighterPalette.colors.size == 7)
        assert(HighlighterPalette.colors.all { it.color.alpha == 1f })
    }

    @Test
    fun signatureDialogOpensAndCanBeCancelled() {
        val viewModel = mockk<EditorViewModel>(relaxed = true)
        every { viewModel.uiState } returns MutableStateFlow(EditorUiState())
        coEvery { viewModel.getPageBitmap(any()) } returns null

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfEditorScreen(
                    documentUri = Uri.parse("content://test/sample.pdf"),
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onExportComplete = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("editor_tool_Sign").performClick()
        composeTestRule.onNodeWithText("Draw your signature").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Draw your signature").assertDoesNotExist()
    }
}