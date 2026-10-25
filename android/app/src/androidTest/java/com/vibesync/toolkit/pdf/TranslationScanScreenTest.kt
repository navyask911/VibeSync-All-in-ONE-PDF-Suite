package com.vibesync.toolkit.pdf

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.ocr.TranslationProcessor
import com.vibesync.toolkit.pdf.presentation.translation.TranslationScanScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TranslationScanScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun languagePickersTranslateAndDisplayRtlArabicResult() {
        val fake = FakeProcessor()
        setTestContent(fake)
        composeRule.onNodeWithTag("translation_capture").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_import").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_source_picker").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_target_picker").performClick()
        composeRule.onNodeWithTag("translation_target_ar").performClick()
        composeRule.onNodeWithTag("translation_run").performClick()
        composeRule.waitUntil(15_000) { fake.translatedTarget == "ar" }
        composeRule.onNodeWithTag("translation_original_text").assertTextContains("Hello")
        composeRule.onNodeWithTag("translation_result_text").assertTextContains("مرحبا بالعالم")
        composeRule.onNodeWithTag("translation_export_txt").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_export_pdf").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("en", fake.translatedSource) }
    }

    private fun setTestContent(processor: TranslationProcessor) {
        composeRule.activity.runOnUiThread {
            val composeView = findComposeView(composeRule.activity.window.decorView)
                ?: error("MainActivity ComposeView was not found")
            composeView.setContent {
                VibeSyncPdfTheme {
                    TranslationScanScreen(
                        onNavigateBack = {},
                        processor = processor,
                        initialText = "Hello world"
                    )
                }
            }
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

    private class FakeProcessor : TranslationProcessor {
        var translatedSource: String? = null
        var translatedTarget: String? = null
        override suspend fun isModelReady(sourceLanguage: String, targetLanguage: String): Boolean = true
        override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String {
            translatedSource = sourceLanguage
            translatedTarget = targetLanguage
            return if (targetLanguage == "ar") "مرحبا بالعالم" else "Hello world"
        }
    }
}
