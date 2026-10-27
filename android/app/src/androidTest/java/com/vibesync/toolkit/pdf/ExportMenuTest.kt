package com.vibesync.toolkit.pdf

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.viewer.ExportMenu
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExportMenuTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun exportMenuOpensAndSelectingTxtRequestsTextPlainSafDestination() {
        val requests = mutableListOf<Pair<String, String>>()
        setTestContent {
            ExportMenu(
                sourceUri = Uri.parse("file:///tmp/unused-export.pdf"),
                testTag = "export_menu",
                onSafLaunch = { mime, name -> requests += mime to name }
            )
        }
        composeRule.onNodeWithTag("export_menu").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("export_txt").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals("text/plain", requests.single().first)
            assertEquals("unused-export.txt", requests.single().second)
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
