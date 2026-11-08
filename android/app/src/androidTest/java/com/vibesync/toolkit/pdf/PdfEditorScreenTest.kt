package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateOf
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.presentation.editor.EditorUiState
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import com.vibesync.toolkit.pdf.data.model.ToolMode
import com.vibesync.toolkit.pdf.data.model.VectorStroke
import com.vibesync.toolkit.pdf.data.model.Point2D
import com.vibesync.toolkit.pdf.presentation.editor.PdfEditorScreen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

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

    @Test
    fun testEditorStateResetsOnDocumentChange() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(application)
        val documentA = File(application.cacheDir, "editor-state-a-${System.nanoTime()}.pdf")
        val documentB = File(application.cacheDir, "editor-state-b-${System.nanoTime()}.pdf")
        listOf(documentA, documentB).forEach { file ->
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(file)
            }
        }

        val viewModelStore = ViewModelStore()
        val viewModel = ViewModelProvider(
            viewModelStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[EditorViewModel::class.java]
        val currentUri = mutableStateOf(Uri.fromFile(documentA))
        val editorVisible = mutableStateOf(true)

        try {
            composeTestRule.setContent {
                VibeSyncPdfTheme {
                    if (editorVisible.value) {
                        PdfEditorScreen(
                            documentUri = currentUri.value,
                            viewModel = viewModel,
                            onNavigateBack = { editorVisible.value = false },
                            onExportComplete = {}
                        )
                    }
                }
            }

            composeTestRule.waitUntil(15_000) {
                viewModel.uiState.value.fileName == documentA.name &&
                    viewModel.uiState.value.totalPages == 1
            }
            composeTestRule.runOnIdle {
                viewModel.addStroke(
                    VectorStroke(
                        points = listOf(Point2D(0.1f, 0.1f), Point2D(0.2f, 0.2f)),
                        colorArgb = android.graphics.Color.YELLOW,
                        strokeWidth = 0.025f,
                        isHighlighter = true
                    )
                )
                viewModel.addTextAnnotation("Doc A annotation", 0.25f, 0.25f)
                viewModel.addSignature(
                    Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888),
                    x = 12f,
                    y = 18f,
                    width = 24f,
                    height = 16f,
                    saveToVault = false
                )
                assertEquals(1, viewModel.uiState.value.currentStrokes.size)
                assertEquals(1, viewModel.uiState.value.currentTextAnnotations.size)
                assertEquals(1, viewModel.uiState.value.currentSignatures.size)
            }

            composeTestRule.onNodeWithTag("editor_navigate_back").performClick()
            composeTestRule.waitUntil(5_000) {
                val state = viewModel.uiState.value
                state.currentStrokes.isEmpty() &&
                    state.currentTextAnnotations.isEmpty() &&
                    state.currentSignatures.isEmpty()
            }

            composeTestRule.runOnIdle {
                currentUri.value = Uri.fromFile(documentB)
                editorVisible.value = true
            }
            composeTestRule.waitUntil(15_000) {
                val state = viewModel.uiState.value
                state.fileName == documentB.name &&
                    state.totalPages == 1 &&
                    state.currentStrokes.isEmpty() &&
                    state.currentTextAnnotations.isEmpty() &&
                    state.currentSignatures.isEmpty()
            }

            assertEquals(0, viewModel.uiState.value.currentStrokes.size)
            assertEquals(0, viewModel.uiState.value.currentTextAnnotations.size)
            assertEquals(0, viewModel.uiState.value.currentSignatures.size)
        } finally {
            runCatching {
                composeTestRule.runOnIdle { editorVisible.value = false }
            }
            runCatching { viewModel.resetEditorState() }
            viewModelStore.clear()
            documentA.delete()
            documentB.delete()
        }
    }

}