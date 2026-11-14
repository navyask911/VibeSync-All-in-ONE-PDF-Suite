package com.vibesync.toolkit.pdf

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.common.PdfDocumentActionsSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WatermarkControlTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun watermarkPreferences() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("pdf_watermark_options", Context.MODE_PRIVATE)

    /**
     * These controls are Pro-only. Every test using this helper must state the
     * entitlement explicitly so a Free default cannot hide the controls.
     */
    private fun openWatermarkControls(
        isProActive: Boolean,
        onWatermark: () -> Unit = {},
        onNavigateToPro: () -> Unit = {}
    ) {
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
                    onDelete = {},
                    isProActive = isProActive,
                    onNavigateToPro = onNavigateToPro,
                    onSaveWatermarkSettings = { text, opacity, angle ->
                        // Simulate the successful Pro ViewModel save boundary.
                        watermarkPreferences().edit()
                            .putString("watermark_text", text.trim())
                            .putFloat("watermark_opacity", opacity.coerceIn(0f, 1f))
                            .putFloat("watermark_angle", angle.coerceIn(0f, 90f))
                            .putBoolean("watermark_custom_enabled", true)
                            .commit()
                    }
                )
            }
        }
        composeTestRule.onNodeWithTag("document_action_watermark")
            .assertExists()
            .performScrollTo()
            .performClick()
    }

    @Test
    fun watermarkActionRevealsTextAndSliderControls() {
        watermarkPreferences().edit().clear().commit()
        openWatermarkControls(isProActive = true)

        composeTestRule.waitForIdle()
        // The sheet is a vertically scrollable Column (not a LazyColumn), so
        // assert reachability before asking Compose to scroll the node into view.
        composeTestRule.onNodeWithTag("watermark_controls_tag")
            .assertExists()
            .performScrollTo()
        composeTestRule.onNodeWithTag("watermark_text_field").assertExists()
        composeTestRule.onNodeWithTag("watermark_opacity_slider").assertExists()
        composeTestRule.onNodeWithTag("watermark_angle_slider").assertExists()
        composeTestRule.onNodeWithTag("watermark_apply_button").assertExists()
    }

    @Test
    fun opacityAndAngleSlidersExposeTheRequiredRanges() {
        watermarkPreferences().edit().clear().commit()
        openWatermarkControls(isProActive = true)

        val opacitySlider = composeTestRule.onNodeWithTag("watermark_opacity_slider")
        opacitySlider.assertExists().performScrollTo()
        val opacityRange = opacitySlider
            .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].range

        val angleSlider = composeTestRule.onNodeWithTag("watermark_angle_slider")
        angleSlider.assertExists().performScrollTo()
        val angleRange = angleSlider
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
        openWatermarkControls(isProActive = true) { callbackInvoked = true }

        composeTestRule.onNodeWithTag("watermark_text_field")
            .assertExists()
            .performScrollTo()
            .performTextClearance()
        composeTestRule.onNodeWithTag("watermark_text_field")
            .performTextInput("INTERNAL USE")
        composeTestRule.onNodeWithTag("watermark_apply_button")
            .assertExists()
            .performScrollTo()
            .performClick()

        assertTrue(callbackInvoked)
        assertEquals("INTERNAL USE", watermarkPreferences().getString("watermark_text", null))
        assertEquals(150f / 255f, watermarkPreferences().getFloat("watermark_opacity", -1f), 0.01f)
        assertEquals(0f, watermarkPreferences().getFloat("watermark_angle", -1f), 0.01f)
        assertTrue(watermarkPreferences().getBoolean("watermark_custom_enabled", false))
    }

    @Test
    fun applyingSelectedSliderValuesPersistsOpacityAndAngle() {
        watermarkPreferences().edit().clear().commit()
        openWatermarkControls(isProActive = true)

        val opacitySlider = composeTestRule.onNodeWithTag("watermark_opacity_slider")
        opacitySlider.assertExists()
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(0.75f))
            }
        val angleSlider = composeTestRule.onNodeWithTag("watermark_angle_slider")
        angleSlider.assertExists()
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(90f))
            }
        composeTestRule.onNodeWithTag("watermark_apply_button")
            .assertExists()
            .performScrollTo()
            .performClick()

        assertEquals(0.75f, watermarkPreferences().getFloat("watermark_opacity", -1f), 0.02f)
        assertEquals(90f, watermarkPreferences().getFloat("watermark_angle", -1f), 0.01f)
        assertTrue(watermarkPreferences().getBoolean("watermark_custom_enabled", false))
    }
}
