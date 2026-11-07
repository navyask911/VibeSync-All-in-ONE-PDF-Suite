package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.vibesync.toolkit.pdf.presentation.editor.PdfEditorScreen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PdfEditorScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun editorWaitsForDocumentReadyBeforeRenderingAndEnablesSave() {
        val viewModel = mockk<EditorViewModel>(relaxed = true)
        val stateFlow = MutableStateFlow(EditorUiState(isDocumentReady = false))
        val bitmap = Bitmap.createBitmap(80, 112, Bitmap.Config.ARGB_8888)
        val bitmapRequests = AtomicInteger(0)
        every { viewModel.uiState } returns stateFlow
        coEvery { viewModel.getPageBitmap(0) } coAnswers {
            bitmapRequests.incrementAndGet()
            bitmap
        }

        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfEditorScreen(
                    documentUri = Uri.parse("content://test/ready.pdf"),
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onExportComplete = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("editor_save").assertIsNotEnabled()
        assertEquals(0, bitmapRequests.get())

        stateFlow.value = stateFlow.value.copy(isDocumentReady = true, totalPages = 1)
        composeTestRule.waitUntil(5_000) { bitmapRequests.get() == 1 }
        composeTestRule.onNodeWithTag("editor_save").assertIsEnabled()
    }

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