package com.vibesync.toolkit.pdf

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.ocr.TranslationProcessor
import com.vibesync.toolkit.pdf.presentation.translation.TranslationScanScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TranslationRoutingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mockedTranslatorReceivesDetectedSourceAndSelectedTargetAndShowsResult() {
        val processor = FakeTranslationProcessor(ready = true)
        setTestContent(processor)
        composeRule.onNodeWithTag("translation_run").performClick()
        composeRule.waitUntil(15_000) { processor.calls.isNotEmpty() }
        composeRule.onNodeWithTag("translation_result_text").assertTextContains("Hola mundo")
        composeRule.runOnIdle {
            assertEquals("en", processor.calls.single().first)
            assertEquals("es", processor.calls.single().second)
        }
    }

    @Test
    fun modelNotReadyPromptsConsentAndDeclineAbortsWithoutTranslation() {
        val processor = FakeTranslationProcessor(ready = false)
        setTestContent(processor)
        composeRule.onNodeWithTag("translation_run").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("translation_model_decline").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("translation_model_decline").performClick()
        composeRule.onNodeWithTag("translation_status").assertTextContains("declined")
        composeRule.runOnIdle { assertTrue("Decline must abort the translation request", processor.calls.isEmpty()) }
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
                        initialText = "Hello world from an offline document"
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

    private class FakeTranslationProcessor(private val ready: Boolean) : TranslationProcessor {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun isModelReady(sourceLanguage: String, targetLanguage: String): Boolean = ready
        override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String {
            calls += sourceLanguage to targetLanguage
            return "Hola mundo"
        }
    }
}
