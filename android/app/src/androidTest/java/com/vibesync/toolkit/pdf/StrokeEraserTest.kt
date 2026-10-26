package com.vibesync.toolkit.pdf

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.data.model.Point2D
import com.vibesync.toolkit.pdf.data.model.ToolMode
import com.vibesync.toolkit.pdf.data.model.VectorStroke
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import com.vibesync.toolkit.pdf.presentation.editor.VectorCanvasOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StrokeEraserTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tapNearStrokeDeletesWholeStrokeAndLeavesOtherStrokesIntact() {
        val viewModel = newViewModel()
        viewModel.addStroke(stroke("target", y = 20f))
        viewModel.addStroke(stroke("other", y = 100f))
        viewModel.selectTool(ToolMode.ERASER)
        showCanvas(viewModel)

        composeTestRule.onNodeWithTag("stroke_eraser_canvas")
            .performTouchInput { click(Offset(50f, 27f)) }
        composeTestRule.runOnIdle {
            assertEquals(listOf("other"), viewModel.uiState.value.currentStrokes.map { it.id })
        }
    }

    @Test
    fun undoRestoresDeletedStrokeAtItsOriginalZOrder() {
        val viewModel = newViewModel()
        val original = listOf(
            stroke("back", y = 20f),
            stroke("deleted-middle", y = 100f),
            stroke("front", y = 180f)
        )
        original.forEach(viewModel::addStroke)
        viewModel.selectTool(ToolMode.ERASER)
        showCanvas(viewModel)

        composeTestRule.onNodeWithTag("stroke_eraser_canvas")
            .performTouchInput { click(Offset(50f, 100f)) }
        composeTestRule.runOnIdle {
            assertEquals(listOf("back", "front"), viewModel.uiState.value.currentStrokes.map { it.id })
            viewModel.undo()
        }
        composeTestRule.runOnIdle {
            assertEquals(original.map { it.id }, viewModel.uiState.value.currentStrokes.map { it.id })
            assertTrue(viewModel.uiState.value.canRedo)
        }
    }

    @Test
    fun tapFarFromEveryStrokeMakesNoChange() {
        val viewModel = newViewModel()
        viewModel.addStroke(stroke("only-stroke", y = 20f))
        viewModel.selectTool(ToolMode.ERASER)
        showCanvas(viewModel)
        val before = viewModel.uiState.value.currentStrokes

        composeTestRule.onNodeWithTag("stroke_eraser_canvas")
            .performTouchInput { click(Offset(180f, 180f)) }
        composeTestRule.runOnIdle {
            assertEquals(before, viewModel.uiState.value.currentStrokes)
            assertFalse(viewModel.uiState.value.canRedo)
        }
    }

    private fun newViewModel(): EditorViewModel =
        EditorViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun stroke(id: String, y: Float) = VectorStroke(
        id = id,
        points = listOf(Point2D(10f, y), Point2D(100f, y)),
        colorArgb = android.graphics.Color.BLACK,
        strokeWidth = 3f
    )

    private fun showCanvas(viewModel: EditorViewModel) {
        composeTestRule.setContent {
            val uiState by viewModel.uiState.collectAsState()
            Box(Modifier.size(240.dp)) {
                VectorCanvasOverlay(
                    toolMode = uiState.toolMode,
                    strokeColor = Color.Blue,
                    strokeWidth = 3f,
                    existingStrokes = uiState.currentStrokes,
                    onStrokeAdded = viewModel::addStroke,
                    onTap = {},
                    modifier = Modifier.fillMaxSize().testTag("stroke_eraser_canvas")
                )
            }
        }
    }
}
