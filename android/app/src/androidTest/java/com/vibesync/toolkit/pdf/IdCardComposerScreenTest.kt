package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.tools.IdCardComposerScreen
import org.junit.Rule
import org.junit.Test

class IdCardComposerScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardTileNavigatesAndSaveRequiresBothFrontAndBack() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_id_card"))
        composeRule.onNodeWithTag("tool_id_card").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.id_card_title)).assertIsDisplayed()

        val front = Bitmap.createBitmap(120, 72, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val back = Bitmap.createBitmap(72, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            setTestContent { IdCardComposerScreen(onNavigateBack = {}, previewFront = null, previewBack = null) }
            composeRule.onNodeWithTag("id_card_save").assertIsNotEnabled()
            setTestContent { IdCardComposerScreen(onNavigateBack = {}, previewFront = front, previewBack = null) }
            composeRule.onNodeWithTag("id_card_save").assertIsNotEnabled()
            setTestContent { IdCardComposerScreen(onNavigateBack = {}, previewFront = front, previewBack = back) }
            composeRule.onNodeWithTag("id_card_layout_preview").assertIsDisplayed()
            composeRule.onNodeWithTag("id_card_pick_front").assertIsDisplayed()
            composeRule.onNodeWithTag("id_card_pick_back").assertIsDisplayed()
            composeRule.onNodeWithTag("id_card_save").assertIsEnabled()
        } finally {
            front.recycle()
            back.recycle()
        }
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
