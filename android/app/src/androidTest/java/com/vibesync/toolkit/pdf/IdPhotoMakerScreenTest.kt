package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.tools.IdPhotoMakerScreen
import org.junit.Rule
import org.junit.Test

class IdPhotoMakerScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardTileNavigatesAndFourPresetsSwitchLockedCropGuide() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("tool_id_photo"))
        composeRule.onNodeWithTag("tool_id_photo").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.id_photo_title)).assertIsDisplayed()

        val preview = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180, 120, 80)) }
        try {
            setTestContent { IdPhotoMakerScreen(onNavigateBack = {}, previewBitmap = preview) }
            composeRule.onNodeWithTag("id_photo_pick").assertIsDisplayed()
            composeRule.onNodeWithTag("id_photo_preset_STANDARD_35x45").assertIsDisplayed()
            composeRule.onNodeWithTag("id_photo_preset_STANDARD_2x2in").assertIsDisplayed()
            composeRule.onNodeWithTag("id_photo_preset_STANDARD_35x35").assertIsDisplayed()
            composeRule.onNodeWithTag("id_photo_preset_PASSPORT_US").assertIsDisplayed()
            composeRule.onNodeWithTag("id_photo_save").assertIsEnabled()
            composeRule.onNodeWithTag("id_photo_radio_STANDARD_2x2in").performClick()
            composeRule.onNodeWithTag("id_photo_crop_guide_STANDARD_2x2in").assertIsDisplayed()
        } finally {
            preview.recycle()
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
