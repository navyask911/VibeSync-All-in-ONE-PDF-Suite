package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.editor.EditorUiState
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import com.vibesync.toolkit.pdf.data.model.ToolMode
import com.vibesync.toolkit.pdf.data.model.VectorStroke
import com.vibesync.toolkit.pdf.presentation.editor.PdfEditorScreen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
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
    fun zoomedPageDragStoresNormalizedStrokeCoordinates() {
        val vm = mockk<EditorViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(EditorUiState(toolMode = ToolMode.PEN))
        val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
        coEvery { vm.getPageBitmap(any()) } returns bitmap
        val captured = slot<VectorStroke>()
        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfEditorScreen(documentUri = Uri.parse("content://test/zoomed.pdf"),
                    viewModel = vm, onNavigateBack = {}, onExportComplete = {})
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor_pdf_page").performTouchInput {
            val y = height * 0.45f
            down(0, Offset(width * 0.35f, y)); down(1, Offset(width * 0.65f, y))
            moveTo(0, Offset(width * 0.20f, y)); moveTo(1, Offset(width * 0.80f, y))
            up(0); up(1)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor_canvas_overlay").performTouchInput {
            swipe(start = Offset(width * 0.34f, height * 0.40f),
                end = Offset(width * 0.53f, height * 0.48f), durationMillis = 450)
        }
        composeTestRule.waitForIdle()
        verify(timeout = 5_000, exactly = 1) { vm.addStroke(capture(captured)) }
        assertTrue(captured.captured.points.size >= 2)
        assertTrue(captured.captured.points.all { it.x in 0f..1f && it.y in 0f..1f })
        val firstPoint = captured.captured.points.first()
        assertTrue(
            "actual normalized=(${firstPoint.x}, ${firstPoint.y})",
            firstPoint.x in 0.14026667f..0.24026667f &&
                firstPoint.y in 0.15849286f..0.25849286f
        )
        bitmap.recycle()
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