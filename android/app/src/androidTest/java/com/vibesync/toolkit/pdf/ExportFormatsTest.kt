package com.vibesync.toolkit.pdf

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.viewer.ExportMenu
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ExportFormatsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pdfJpgZipAndTxtRoutesUseTheirDeclaredSafMimeTypes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "export-formats-" + System.nanoTime()).apply { mkdirs() }
        val source = File(work, "typed-routes.pdf")
        createPdf(source, 2)
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        setTestContent {
            ExportMenu(
                sourceUri = Uri.fromFile(source),
                currentPageIndex = 1,
                testTag = "export_menu",
                onSafLaunch = { mime, name -> calls += mime to name }
            )
        }
        try {
            select("export_pdf")
            assertEquals("application/pdf", calls.last().first)
            assertTrue(calls.last().second.endsWith(".pdf"))

            select("export_jpg_current")
            assertEquals("image/jpeg", calls.last().first)
            assertTrue(calls.last().second.endsWith(".jpg"))
            assertTrue(calls.last().second.contains("page_2"))

            composeRule.onNodeWithTag("export_menu").performClick()
            composeRule.onNodeWithTag("export_jpg_separate").performClick()
            composeRule.waitUntil(20_000) { calls.size >= 3 }
            assertEquals("image/jpeg", calls.last().first)
            assertTrue(calls.last().second.endsWith(".jpg"))

            select("export_jpg_zip")
            assertEquals("application/zip", calls.last().first)
            assertTrue(calls.last().second.endsWith(".zip"))

            select("export_txt")
            assertEquals("text/plain", calls.last().first)
            assertTrue(calls.last().second.endsWith(".txt"))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun select(tag: String) {
        composeRule.onNodeWithTag("export_menu").performClick()
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun createPdf(file: File, pages: Int) {
        PDDocument().use { document ->
            repeat(pages) { document.addPage(PDPage(PDRectangle.A4)) }
            document.save(file)
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
