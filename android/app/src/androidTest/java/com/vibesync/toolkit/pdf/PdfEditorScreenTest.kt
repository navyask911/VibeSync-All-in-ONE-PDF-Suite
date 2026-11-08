package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PdfEditorScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createTestPdf(context: android.content.Context, name: String): java.io.File {
        val file = java.io.File(context.cacheDir, name)
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            page.canvas.drawText(name, 32f, 48f, Paint(Paint.ANTI_ALIAS_FLAG))
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
        return file
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
    fun testEditorStateResetsOnDocumentChange() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val documentA = createTestPdf(context, "editor-doc-a.pdf")
        val documentB = createTestPdf(context, "editor-doc-b.pdf")
        val viewModel = EditorViewModel(context)
        val signatureBitmap = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888)

        try {
            composeTestRule.setContent {
                val showEditor = remember { mutableStateOf(true) }
                val activeUri = remember { mutableStateOf(Uri.fromFile(documentA)) }
                VibeSyncPdfTheme {
                    Column {
                        Button(
                            onClick = { showEditor.value = false },
                            modifier = Modifier.testTag("test_editor_back")
                        ) { Text("Back") }
                        Button(
                            onClick = {
                                activeUri.value = Uri.fromFile(documentB)
                                showEditor.value = true
                            },
                            modifier = Modifier.testTag("test_open_doc_b")
                        ) { Text("Open Doc B") }
                        if (showEditor.value) {
                            PdfEditorScreen(
                                documentUri = activeUri.value,
                                viewModel = viewModel,
                                onNavigateBack = { showEditor.value = false },
                                onExportComplete = {}
                            )
                        }
                    }
                }
            }

            composeTestRule.waitUntil(15_000) {
                viewModel.uiState.value.fileName == documentA.name &&
                    viewModel.uiState.value.documentLoadedRevision > 0 &&
                    viewModel.uiState.value.totalPages == 1
            }
            viewModel.addStroke(
                VectorStroke(
                    points = listOf(
                        com.vibesync.toolkit.pdf.data.model.Point2D(0.1f, 0.2f),
                        com.vibesync.toolkit.pdf.data.model.Point2D(0.4f, 0.3f)
                    ),
                    colorArgb = android.graphics.Color.BLACK,
                    strokeWidth = 0.01f
                )
            )
            viewModel.addTextAnnotation("Doc A note", 24f, 32f)
            viewModel.addSignature(
                bitmap = signatureBitmap,
                x = 40f,
                y = 50f,
                saveToVault = false
            )
            assertEquals(1, viewModel.uiState.value.currentStrokes.size)
            assertEquals(1, viewModel.uiState.value.currentTextAnnotations.size)
            assertEquals(1, viewModel.uiState.value.currentSignatures.size)
            val docARevision = viewModel.uiState.value.documentLoadedRevision

            composeTestRule.onNodeWithTag("test_editor_back").performClick()
            composeTestRule.waitUntil(5_000) {
                viewModel.uiState.value.currentStrokes.isEmpty() &&
                    viewModel.uiState.value.currentTextAnnotations.isEmpty() &&
                    viewModel.uiState.value.currentSignatures.isEmpty()
            }
            composeTestRule.waitUntil(5_000) { signatureBitmap.isRecycled }

            composeTestRule.onNodeWithTag("test_open_doc_b").performClick()
            composeTestRule.waitUntil(15_000) {
                viewModel.uiState.value.fileName == documentB.name &&
                    viewModel.uiState.value.documentLoadedRevision > docARevision &&
                    viewModel.uiState.value.totalPages == 1
            }
            assertEquals(0, viewModel.uiState.value.currentStrokes.size)
            assertEquals(0, viewModel.uiState.value.currentTextAnnotations.size)
            assertEquals(0, viewModel.uiState.value.currentSignatures.size)
            assertTrue(!viewModel.uiState.value.canUndo)
            assertTrue(!viewModel.uiState.value.canRedo)
        } finally {
            if (!signatureBitmap.isRecycled) signatureBitmap.recycle()
            documentA.delete()
            documentB.delete()
        }
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