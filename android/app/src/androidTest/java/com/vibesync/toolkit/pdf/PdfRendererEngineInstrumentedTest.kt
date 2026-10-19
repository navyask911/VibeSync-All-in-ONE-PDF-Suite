package com.vibesync.toolkit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.core.util.BitmapCache
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.pdf.PdfRendererEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfRendererEngineInstrumentedTest {

    @Test
    fun concurrentRequestsReuseLeasedBitmapAndCloseSafelyDuringContinuousScrolling() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "renderer_concurrency_${System.nanoTime()}.pdf")
        PDFBoxResourceLoader.init(context)
        val engine = PdfRendererEngine(context)
        val leases = mutableListOf<BitmapCache.BitmapLease>()

        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(file)
            }

            assertEquals(1, engine.openDocument(Uri.fromFile(file)).getOrThrow())

            val rendered = withTimeout(15_000L) {
                (1..8).map {
                    async(Dispatchers.IO) { engine.renderPageLease(pageIndex = 0, scale = 1.25f) }
                }.awaitAll()
            }
            assertTrue("Every page request should produce a lease.", rendered.all { it != null })
            leases += rendered.filterNotNull()

            val bitmap = leases.first().bitmap
            assertTrue(
                "A rendered bitmap must have valid positive dimensions.",
                bitmap.width > 0 && bitmap.height > 0
            )
            assertTrue(
                "Concurrent cache hits should share the cached bitmap.",
                leases.all { it.bitmap === bitmap }
            )

            // Evict the cache while the UI still owns leases: visible bitmaps must survive until
            // the final holder is released, even if the document is closed during scrolling.
            engine.closeDocumentSafely()
            assertFalse("A leased bitmap must survive cache eviction.", bitmap.isRecycled)

            leases.forEach { it.close() }
            assertTrue(
                "The bitmap should be recycled only after the final lease closes.",
                bitmap.isRecycled
            )
            leases.clear()
        } finally {
            leases.forEach { it.close() }
            engine.closeDocumentSafely()
            file.delete()
        }
    }

    @Test
    fun providerBackedContentUriOpensAndRendersKnownContentAcrossPages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val cacheDir = File(context.cacheDir, "pdf_cache")
        assertTrue("The cache directory must be available to the FileProvider.", cacheDir.isDirectory || cacheDir.mkdirs())
        val source = File(cacheDir, "renderer_provider_${System.nanoTime()}.pdf")
        val engine = PdfRendererEngine(context)
        val leases = mutableListOf<BitmapCache.BitmapLease>()

        try {
            PDDocument().use { document ->
                repeat(2) {
                    val page = PDPage(PDRectangle.A4)
                    document.addPage(page)
                    PDPageContentStream(document, page).use { content ->
                        content.setNonStrokingColor(220, 20, 60)
                        content.addRect(50f, 50f, 100f, 100f)
                        content.fill()
                    }
                }
                document.save(source)
            }

            val providerUri = UriUtils.getShareableUri(context, source)
            assertEquals("content", providerUri.scheme)
            assertEquals(2, withTimeout(15_000L) { engine.openDocument(providerUri).getOrThrow() })

            val metadata = withTimeout(15_000L) { engine.getPagesMetadata(0, 2) }
            assertEquals("Metadata must be obtained for both pages.", 2, metadata.size)
            assertEquals(0, metadata[0].pageIndex)
            assertEquals(1, metadata[1].pageIndex)
            assertTrue(metadata.all { it.widthPt > 0f && it.heightPt > 0f })

            for (pageIndex in 0..1) {
                val lease = withTimeout(15_000L) {
                    engine.renderPageLease(pageIndex = pageIndex, scale = 1.5f)
                } ?: throw AssertionError("Expected a rendered bitmap lease for page $pageIndex")
                leases += lease
                val bitmap = lease.bitmap
                assertFalse("A rendered page must not be recycled.", bitmap.isRecycled)
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                assertTrue(
                    "Page $pageIndex must contain the known red vector rectangle, not a blank placeholder.",
                    containsRedContent(bitmap)
                )
            }
        } finally {
            leases.forEach { it.close() }
            engine.closeDocumentSafely()
            source.delete()
        }
    }

    @Test
    fun cancelledPartialUriCopyDeletesItsTemporaryFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "cancel_source_${System.nanoTime()}.bin")
        source.writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() })
        val cacheDir = File(context.cacheDir, "pdf_cache").apply { mkdirs() }
        val before = cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("cancel_test_") }
            .map { it.name }
            .toSet()
        var cancellationChecks = 0

        try {
            try {
                UriUtils.copyUriToCacheFile(
                    context = context,
                    uri = Uri.fromFile(source),
                    prefix = "cancel_test_",
                    cancellationCheck = {
                        cancellationChecks += 1
                        if (cancellationChecks >= 3) {
                            throw CancellationException("Test cancellation during partial copy")
                        }
                    }
                )
                fail("A cancelled copy should throw CancellationException.")
            } catch (_: CancellationException) {
                // Expected: the cancellation path must delete the partial cache file.
            }

            val after = cacheDir.listFiles()
                .orEmpty()
                .filter { it.name.startsWith("cancel_test_") }
                .map { it.name }
                .toSet()
            assertEquals("No partial temporary copy may remain.", before, after)
        } finally {
            source.delete()
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
