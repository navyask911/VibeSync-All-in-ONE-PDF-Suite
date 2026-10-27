package com.vibesync.toolkit.pdf

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import com.vibesync.toolkit.pdf.presentation.settings.ResolutionProfilePreferencePanel
import com.vibesync.toolkit.pdf.presentation.tools.ConvertImagesScreen
import com.vibesync.toolkit.pdf.presentation.tools.PdfQuickActionScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class ResolutionProfileSelectorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectorShowsFourOptionsAndPersistsSelectionInRtlLayout() {
        ResolutionProfilePrefs(composeRule.activity).write(ResolutionProfile.STANDARD)
        setTestContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ResolutionProfilePreferencePanel(testTag = "resolution_profile_test_panel")
            }
        }
        composeRule.onAllNodesWithTag("resolution_profile_SMALLEST").assertCountEquals(1)
        composeRule.onAllNodesWithTag("resolution_profile_STANDARD").assertCountEquals(1)
        composeRule.onAllNodesWithTag("resolution_profile_LARGEST").assertCountEquals(1)
        composeRule.onAllNodesWithTag("resolution_profile_HD_AUTO").assertCountEquals(1)
        composeRule.onNodeWithTag("resolution_profile_STANDARD").assertIsSelected()
        composeRule.onNodeWithTag("resolution_profile_LARGEST").performClick()
        composeRule.onNodeWithTag("resolution_profile_LARGEST").assertIsSelected()
        assertEquals(ResolutionProfile.LARGEST, ResolutionProfilePrefs(composeRule.activity).read())

        setTestContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ResolutionProfilePreferencePanel(testTag = "resolution_profile_test_panel")
            }
        }
        composeRule.onNodeWithTag("resolution_profile_LARGEST").assertIsSelected()
    }

    @Test
    fun compressionScreenShowsPersistedProfileSelector() {
        setTestContent {
            PdfQuickActionScreen("compress", Uri.parse("file:///tmp/resolution-profile-test.pdf"), {}, {})
        }
        composeRule.onNodeWithTag("resolution_profile_compression_selector").assertIsDisplayed()
        composeRule.onNodeWithTag("resolution_profile_STANDARD").assertIsDisplayed()
    }

    @Test
    fun imagesToPdfScreenShowsPersistedProfileSelector() {
        setTestContent {
            ConvertImagesScreen(onNavigateBack = {}, onSuccess = {})
        }
        composeRule.onNodeWithTag("resolution_profile_images_to_pdf_selector").assertIsDisplayed()
        composeRule.onNodeWithTag("resolution_profile_STANDARD").assertIsDisplayed()
    }

    @Test
    fun pdfExportNameDialogShowsProfileSelector() {
        val file = File(composeRule.activity.cacheDir, "resolution-profile-export-test.pdf")
            .apply { writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)) }
        composeRule.activity.runOnUiThread {
            val method = MainActivity::class.java.getDeclaredMethod(
                "shareFiles", List::class.java, String::class.java, String::class.java,
                Boolean::class.javaPrimitiveType!!
            )
            method.isAccessible = true
            method.invoke(composeRule.activity, listOf(file), "application/pdf", "Test_Export", false)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("resolution_profile_export_selector").assertIsDisplayed()
    }

    /**
     * MainActivity already owns the Activity's Compose content. Replace that ComposeView's
     * content for isolated screen tests instead of asking ComposeTestRule to install a second
     * content root, which throws "has already set content" on this Activity.
     */
    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.activity.runOnUiThread {
            val composeView = findComposeView(composeRule.activity.window.decorView)
                ?: error("MainActivity ComposeView was not found")
            composeView.setContent {
                VibeSyncPdfTheme {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun findComposeView(view: View): ComposeView? {
        if (view is ComposeView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findComposeView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
