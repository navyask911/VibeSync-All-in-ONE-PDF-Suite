package com.vibesync.toolkit.pdf

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.common.PdfDocumentActionsSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WatermarkControlTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun watermarkPreferences() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("pdf_watermark_options", Context.MODE_PRIVATE)

    private fun openWatermarkControls(onWatermark: () -> Unit = {}) {
        composeTestRule.setContent {
            VibeSyncPdfTheme {
                PdfDocumentActionsSheet(
                    documentName = "watermark-test.pdf",
                    onDismissRequest = {},
                    onOpen = {},
                    onEdit = {},
                    onRename = {},
                    onSharePdf = {},
                    onShareImages = {},
                    onSplit = {},
                    onCompress = {},
                    onWatermark = onWatermark,
                    onEncrypt = {},
                    onDelete = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("document_action_watermark")
            .performScrollTo()
            .performClick()
    }

    @Test
    fun watermarkActionRevealsTextAndSliderControls() {
        watermarkPreferences().edit().clear().commit()
        openWatermarkControls()

        composeTestRule.onNodeWithTag("watermark_controls_panel").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("watermark_text_field").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("watermark_opacity_slider").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("watermark_angle_slider").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("watermark_apply_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun opacityAndAngleSlidersExposeTheRequiredRanges() {
        watermarkPreferences().edit().clear().commit()
        openWatermarkControls()

        val opacityRange = composeTestRule.onNodeWithTag("watermark_opacity_slider")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range
        val angleRange = composeTestRule.onNodeWithTag("watermark_angle_slider")
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range

        assertEquals(0f, opacityRange.start, 0.001f)
        assertEquals(1f, opacityRange.endInclusive, 0.001f)
        assertEquals(0f, angleRange.start, 0.001f)
        assertEquals(90f, angleRange.endInclusive, 0.001f)
    }

    @Test
    fun applyingWatermarkPersistsTextAndInvokesTheWatermarkAction() {
        watermarkPreferences().edit().clear().commit()
        var callbackInvoked = false
        openWatermarkControls { callbackInvoked = true }

        composeTestRule.onNodeWithTag("watermark_text_field")
            .performScrollTo()
            .performTextClearance()
        composeTestRule.onNodeWithTag("watermark_text_field")
            .performTextInput("INTERNAL USE")
        composeTestRule.onNodeWithTag("watermark_apply_button")
            .performScrollTo()
            .performClick()

        assertTrue(callbackInvoked)
        assertEquals("INTERNAL USE", watermarkPreferences().getString("watermark_text", null))
        assertEquals(150f / 255f, watermarkPreferences().getFloat("watermark_opacity", -1f), 0.01f)
        assertEquals(0f, watermarkPreferences().getFloat("watermark_angle", -1f), 0.01f)
        assertFalse(watermarkPreferences().getBoolean("watermark_custom_enabled", true))
    }

    @Test
    fun applyingSelectedSliderValuesPersistsOpacityAndAngle() {
        watermarkPreferences().edit().clear().commit()
        openWatermarkControls()

        composeTestRule.onNodeWithTag("watermark_opacity_slider")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(0.75f))
            }
        composeTestRule.onNodeWithTag("watermark_angle_slider")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(90f))
            }
        composeTestRule.onNodeWithTag("watermark_apply_button")
            .performScrollTo()
            .performClick()

        assertEquals(0.75f, watermarkPreferences().getFloat("watermark_opacity", -1f), 0.02f)
        assertEquals(90f, watermarkPreferences().getFloat("watermark_angle", -1f), 0.01f)
    }
}
