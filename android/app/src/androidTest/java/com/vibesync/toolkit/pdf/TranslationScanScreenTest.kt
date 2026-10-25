package com.vibesync.toolkit.pdf

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.ocr.TranslationProcessor
import com.vibesync.toolkit.pdf.presentation.translation.TranslationScanScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TranslationScanScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun languagePickersTranslateAndDisplayRtlArabicResult() {
        val fake = FakeProcessor()
        composeRule.setContent {
            VibeSyncPdfTheme {
                TranslationScanScreen(
                    onNavigateBack = {},
                    processor = fake,
                    initialText = SOURCE_TEXT
                )
            }
        }

        composeRule.onNodeWithTag("translation_capture").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_import").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_original_text").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_source_picker").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_target_picker").performClick()
        composeRule.onNodeWithTag("translation_target_ar").performClick()
        composeRule.onNodeWithTag("translation_run").performClick()

        composeRule.waitUntil(15_000) { fake.translatedTarget == "ar" && fake.translatedResult != null }
        composeRule.onNodeWithTag("translation_result_text").assertTextContains(ARABIC_RESULT)
        composeRule.onNodeWithTag("translation_export_txt").assertIsDisplayed()
        composeRule.onNodeWithTag("translation_export_pdf").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("en", fake.translatedSource)
            assertEquals("ar", fake.translatedTarget)
            assertEquals(ARABIC_RESULT, fake.translatedResult)
            assertTrue(
                "Arabic target result must contain Arabic-script characters",
                fake.translatedResult.orEmpty().any { it in '\u0600'..'\u06FF' }
            )
        }
    }

    private class FakeProcessor : TranslationProcessor {
        var translatedSource: String? = null
        var translatedTarget: String? = null
        var translatedResult: String? = null

        override suspend fun isModelReady(sourceLanguage: String, targetLanguage: String): Boolean = true

        override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String {
            translatedSource = sourceLanguage
            translatedTarget = targetLanguage
            translatedResult = if (targetLanguage == "ar") TranslationScanScreenTest.ARABIC_RESULT else "Hola mundo"
            return requireNotNull(translatedResult)
        }
    }

    private companion object {
        const val SOURCE_TEXT = "Hello world"
        const val ARABIC_RESULT = "مرحبا بالعالم"
    }
}
