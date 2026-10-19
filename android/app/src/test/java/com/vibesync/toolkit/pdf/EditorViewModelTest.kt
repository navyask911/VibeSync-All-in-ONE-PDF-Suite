package com.vibesync.toolkit.pdf

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.mockk.mockk
import com.vibesync.toolkit.pdf.data.model.VectorStroke
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditorViewModelTest {
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        val app = mockk<Application>(relaxed = true)
        viewModel = EditorViewModel(app)
    }

    @Test
    fun textAnnotationUsesArgbAndParticipatesInUndoRedo() {
        viewModel.selectColor(Color.Red)
        viewModel.addTextAnnotation("Hello", 20f, 30f)

        val added = viewModel.uiState.value.currentTextAnnotations.single()
        assertEquals(Color.Red.toArgb(), added.colorArgb)
        assertTrue(viewModel.uiState.value.canUndo)

        viewModel.undo()
        assertTrue(viewModel.uiState.value.currentTextAnnotations.isEmpty())
        assertTrue(viewModel.uiState.value.canRedo)

        viewModel.redo()
        assertEquals(1, viewModel.uiState.value.currentTextAnnotations.size)
        assertEquals("Hello", viewModel.uiState.value.currentTextAnnotations.single().text)
    }

    @Test
    fun eraserRemovesOnlyAnnotationStrokesAndUndoRestoresThemWithoutRemovingText() {
        viewModel.addTextAnnotation("Keep source text", 12f, 24f)
        viewModel.addStroke(
            VectorStroke(
                points = listOf(
                    com.vibesync.toolkit.pdf.data.model.Point2D(0f, 20f),
                    com.vibesync.toolkit.pdf.data.model.Point2D(80f, 20f)
                ),
                colorArgb = Color.Yellow.toArgb(),
                strokeWidth = 8f,
                isHighlighter = true,
                alpha = 0.35f
            )
        )

        viewModel.eraseStrokeAt(x = 40f, y = 21f, radius = 8f)

        assertTrue(viewModel.uiState.value.currentStrokes.isEmpty())
        assertEquals("Keep source text", viewModel.uiState.value.currentTextAnnotations.single().text)

        viewModel.undo()
        assertEquals(1, viewModel.uiState.value.currentStrokes.size)
        assertEquals("Keep source text", viewModel.uiState.value.currentTextAnnotations.single().text)
    }

    @Test
    fun signatureAndStrokeCanBothBeRevertedIndependently() {
        val stroke = VectorStroke(
            points = listOf(
                com.vibesync.toolkit.pdf.data.model.Point2D(1f, 1f),
                com.vibesync.toolkit.pdf.data.model.Point2D(5f, 5f)
            ),
            colorArgb = Color.Blue.toArgb(),
            strokeWidth = 4f
        )
        viewModel.addStroke(stroke)
        assertEquals(1, viewModel.uiState.value.currentStrokes.size)

        viewModel.undo()
        assertTrue(viewModel.uiState.value.currentStrokes.isEmpty())

        viewModel.redo()
        assertEquals(1, viewModel.uiState.value.currentStrokes.size)
    }
}