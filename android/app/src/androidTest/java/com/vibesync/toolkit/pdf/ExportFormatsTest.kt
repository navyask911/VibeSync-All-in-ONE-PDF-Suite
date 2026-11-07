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
import com.vibesync.toolkit.pdf.presentation.viewer.buildJpgPagesZip
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

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
            assertTrue(Regex("""VibeSync_Export_[0-9]{8}_[0-9]{6}[.]pdf""").matches(calls.last().second))

            select("export_jpg_current")
            assertEquals("image/jpeg", calls.last().first)
            assertTrue(Regex("""VibeSync_Export_Page2_[0-9]{8}_[0-9]{6}[.]jpg""").matches(calls.last().second))

            composeRule.onNodeWithTag("export_menu").performClick()
            composeRule.onNodeWithTag("export_jpg_separate").performClick()
            composeRule.waitUntil(20_000) { calls.size >= 3 }
            assertEquals("image/jpeg", calls.last().first)
            assertTrue(Regex("""VibeSync_Export_Page1_[0-9]{8}_[0-9]{6}[.]jpg""").matches(calls.last().second))

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

    @Test
    fun multipagePdfCreatesZipWithOneValidJpegEntryPerPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "export-zip-" + System.nanoTime()).apply { mkdirs() }
        val source = File(work, "pages.pdf")
        val zipFile = File(work, "pages.zip")
        createPdf(source, 3)
        try {
            val progress = mutableListOf<Pair<Int, Int>>()
            buildJpgPagesZip(context, Uri.fromFile(source), zipFile) { completed, total ->
                progress += completed to total
            }
            assertTrue(zipFile.isFile && zipFile.length() > 0L)
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals(listOf("page_001.jpg", "page_002.jpg", "page_003.jpg"), entries.map { it.name })
                entries.forEach { entry ->
                    assertTrue("JPEG entry must not be empty", entry.size > 0L)
                    zip.getInputStream(entry).use { input ->
                        assertEquals(0xFF, input.read())
                        assertEquals(0xD8, input.read())
                    }
                }
            }
            assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
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
