package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
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
import com.vibesync.toolkit.pdf.presentation.scanner.BookSplitPreview
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BookSplitPreviewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardBookSplitTileOpensToolAndDividerCanBeDraggedBeforeCommit() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_book_split"))
        composeRule.onNodeWithTag("tool_book_split").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.book_split_title)).assertIsDisplayed()

        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.LTGRAY) }
        var committedGutter = -1
        setTestContent {
            BookSplitPreview(
                source = source,
                initialGutterPx = 200,
                onCommit = { committedGutter = it }
            )
        }
        val gutterNode = composeRule.onNodeWithTag("book_split_gutter")
        val before = gutterNode.fetchSemanticsNode().config.toString()
        composeRule.onNodeWithTag("book_split_preview").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        val after = composeRule.onNodeWithTag("book_split_gutter").fetchSemanticsNode().config.toString()
        assertNotEquals(before, after)
        composeRule.onNodeWithTag("book_split_commit").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertTrue(committedGutter in 1 until source.width)
            assertNotEquals(200, committedGutter)
        }
        source.recycle()
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
