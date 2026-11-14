package com.vibesync.toolkit.pdf

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.ComposeView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.scanner.BatchReviewSheet
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanPage
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanScreen
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class BatchReviewSheetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardBatchTileOpensToolAndReviewSupportsReorderDeleteAndCompile() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_batch_scan"))
        composeRule.onNodeWithTag("tool_batch_scan").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.batch_scan_title)).assertIsDisplayed()

        val a = page("a")
        val b = page("b")
        val c = page("c")
        val pages = mutableStateListOf(a, b, c)
        setTestContent {
            BatchReviewSheet(
                pages = pages,
                onMovePage = { from, to ->
                    if (from in pages.indices && to in pages.indices && from != to) {
                        val moved = pages.removeAt(from)
                        pages.add(to, moved)
                    }
                },
                onDeletePage = { id -> pages.removeAll { it.id == id } },
                onAddPages = {},
                onCompile = { _ -> },
                onClearAll = { pages.clear() }
            )
        }
        composeRule.onNodeWithTag("batch_page_count").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_compile").assertIsEnabled()
        composeRule.onNodeWithTag("batch_move_right_a")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf("b", "a", "c"), pages.map { it.id }) }
        composeRule.onNodeWithTag("batch_delete_a")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf("b", "c"), pages.map { it.id }) }
        composeRule.onNodeWithTag("batch_drag_b", useUnmergedTree = true).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf("c", "b"), pages.map { it.id }) }
        composeRule.onNodeWithTag("batch_page_count").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_review_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_compile").performClick()
        composeRule.onNodeWithTag("batch_page_range_input").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_page_range_confirm").performClick()
        composeRule.onNodeWithTag("batch_clear_all").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), pages.map { it.id }) }
        listOf(a.file, b.file, c.file).forEach { it.delete() }
    }

    @Test
    fun emptyBatchFolderHasDeleteControlAndIsRemovedFromTheDevice() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scansRoot = File(app.filesDir, "VibeSyncScans")
        assertTrue(scansRoot.exists() || scansRoot.mkdirs())
        val folder = File(
            scansRoot,
            BatchScanViewModel.BATCH_FOLDER_PREFIX + "ui_delete_" + System.nanoTime()
        )
        assertTrue(folder.mkdirs())
        val viewModel = BatchScanViewModel(app)
        val deleteTag = "batch_delete_folder_${folder.name}"

        try {
            setTestContent {
                BatchScanScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onStartCapture = {},
                    onOpenPdf = {}
                )
            }

            composeRule.onNodeWithTag("batch_folder_list")
                .performScrollToNode(hasTestTag(deleteTag))
            composeRule.onNodeWithTag(deleteTag)
                .assertIsDisplayed()
                .performClick()
            composeRule.onNodeWithTag("batch_delete_folder_confirm")
                .assertIsDisplayed()
                .performClick()

            composeRule.waitUntil(10_000L) {
                !folder.exists() && viewModel.state.value.batchFolders.none { it.id == folder.name }
            }
            composeRule.onNodeWithTag(deleteTag).assertDoesNotExist()
            assertFalse(folder.exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun page(id: String): BatchScanPage {
        val file = File.createTempFile("batchreview", ".jpg", composeRule.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(80, 40, 120)) }
        try {
            FileOutputStream(file).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
        return BatchScanPage(id, file, 32, 48, 32L * 48L * 4L)
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.activity.runOnUiThread {
            val composeView = findComposeView(composeRule.activity.window.decorView)
                ?: error("MainActivity ComposeView was not found")
            composeView.setContent { VibeSyncPdfTheme { content() } }
        }
        composeRule.waitForIdle()
    }

    private fun findComposeView(view: View): ComposeView? {
        if (view is ComposeView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
