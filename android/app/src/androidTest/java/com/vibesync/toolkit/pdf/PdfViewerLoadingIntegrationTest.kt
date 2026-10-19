package com.vibesync.toolkit.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.core.util.BitmapCache
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfViewerLoadingIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun realProviderPdfLoadsFirstPageAndRendersKnownContent() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(application)
        val cacheDir = File(application.cacheDir, "pdf_cache").apply { mkdirs() }
        val source = File(cacheDir, "viewer_integration_${System.nanoTime()}.pdf")
        val store = ViewModelStore()
        val viewModel = PdfViewerViewModel(application)
        store.put("pdf-viewer-integration", viewModel)
        var firstPageLease: BitmapCache.BitmapLease? = null
        var secondPageLease: BitmapCache.BitmapLease? = null

        try {
            createKnownPdf(source, pageCount = 2)
            val uri = UriUtils.getShareableUri(application, source)
            assertEquals("The integration must use a real content:// URI.", "content", uri.scheme)

            viewModel.loadDocument(uri)
            val loaded = withTimeout(20_000L) {
                viewModel.uiState.first { !it.isLoading }
            }

            assertNull("A valid provider PDF must not report a load error.", loaded.errorMessage)
            assertFalse(loaded.isLoading)
            assertEquals(2, loaded.pageCount)
            assertEquals(0, loaded.pagesMetadata.first().pageIndex)

            firstPageLease = withTimeout(10_000L) {
                viewModel.acquirePageBitmap(pageIndex = 0, scale = 1.5f)
            }
            assertNotNull(firstPageLease)
            assertFalse(firstPageLease!!.bitmap.isRecycled)
            assertTrue(containsRedContent(firstPageLease!!.bitmap))

            secondPageLease = withTimeout(10_000L) {
                viewModel.acquirePageBitmap(pageIndex = 1, scale = 1.5f)
            }
            assertNotNull(secondPageLease)
            assertFalse(secondPageLease!!.bitmap.isRecycled)
            assertTrue(containsRedContent(secondPageLease!!.bitmap))
        } finally {
            firstPageLease?.close()
            secondPageLease?.close()
            store.clear()
            source.delete()
        }
    }

    @Test
    fun corruptPdfReachesVisibleTerminalErrorInsteadOfLoadingForever() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val source = File(application.cacheDir, "viewer_corrupt_${System.nanoTime()}.pdf")
        val store = ViewModelStore()
        val viewModel = PdfViewerViewModel(application)
        store.put("pdf-viewer-corrupt", viewModel)

        try {
            source.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
            viewModel.loadDocument(Uri.fromFile(source))
            val failed = withTimeout(20_000L) {
                viewModel.uiState.first { !it.isLoading }
            }

            assertFalse("Corrupt input must reach a terminal state.", failed.isLoading)
            assertEquals(0, failed.pageCount)
            assertNotNull("Corrupt input must show an actionable error.", failed.errorMessage)
        } finally {
            store.clear()
            source.delete()
        }
    }


    @Test
    fun composeViewerDisplaysRealFirstPageAndRemovesDocumentSpinner() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(application)
        val cacheDir = File(application.cacheDir, "pdf_cache").apply { mkdirs() }
        val source = File(cacheDir, "viewer_ui_${System.nanoTime()}.pdf")
        val store = ViewModelStore()
        val viewModel = PdfViewerViewModel(application)
        store.put("pdf-viewer-compose-success", viewModel)

        try {
            createKnownPdf(source, pageCount = 2)
            val uri = UriUtils.getShareableUri(application, source)
            composeTestRule.setContent {
                PdfViewerScreen(
                    documentUri = uri,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onNavigateToEditor = {},
                    onShareDocument = {}
                )
            }

            composeTestRule.waitUntil(timeoutMillis = 20_000L) {
                composeTestRule.onAllNodesWithContentDescription("PDF page 1")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithContentDescription("PDF page 1").assertIsDisplayed()
            assertTrue(
                composeTestRule.onAllNodesWithTag("pdf_document_loading")
                    .fetchSemanticsNodes().isEmpty()
            )
        } finally {
            composeTestRule.runOnIdle { store.clear() }
            source.delete()
        }
    }

    @Test
    fun composeViewerShowsActionableErrorForCorruptPdfInsteadOfGlobalSpinner() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val source = File(application.cacheDir, "viewer_ui_corrupt_${System.nanoTime()}.pdf")
        val store = ViewModelStore()
        val viewModel = PdfViewerViewModel(application)
        store.put("pdf-viewer-compose-corrupt", viewModel)

        try {
            source.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
            val uri = Uri.fromFile(source)
            composeTestRule.setContent {
                PdfViewerScreen(
                    documentUri = uri,
                    viewModel = viewModel,
                    onNavigateBack = {},
                    onNavigateToEditor = {},
                    onShareDocument = {}
                )
            }

            composeTestRule.waitUntil(timeoutMillis = 20_000L) {
                composeTestRule.onAllNodesWithText("Error opening PDF")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithText("Error opening PDF").assertIsDisplayed()
            composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
            assertTrue(
                composeTestRule.onAllNodesWithTag("pdf_document_loading")
                    .fetchSemanticsNodes().isEmpty()
            )
        } finally {
            composeTestRule.runOnIdle { store.clear() }
            source.delete()
        }
    }

    private fun createKnownPdf(file: File, pageCount: Int) {
        PDDocument().use { document ->
            repeat(pageCount) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.setNonStrokingColor(220, 20, 60)
                    content.addRect(50f, 50f, 100f, 100f)
                    content.fill()
                }
            }
            document.save(file)
        }
    }

    private fun containsRedContent(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 100).coerceAtLeast(1)
        val stepY = (bitmap.height / 100).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val red = AndroidColor.red(pixel)
                val green = AndroidColor.green(pixel)
                val blue = AndroidColor.blue(pixel)
                if (red > 150 && red > green * 1.5 && red > blue * 1.5) return true
            }
        }
        return false
    }
}
