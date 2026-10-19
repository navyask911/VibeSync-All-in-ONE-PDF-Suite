package com.vibesync.toolkit.pdf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.core.util.BitmapCache
import com.vibesync.toolkit.pdf.data.pdf.PdfRendererEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfRendererEngineInstrumentedTest {

    @Test
    fun concurrentRequestsReuseLeasedBitmapAndCloseSafelyDuringContinuousScrolling() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "renderer_concurrency_${System.nanoTime()}.pdf")
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
                    async(Dispatchers.IO) { engine.renderPage(pageIndex = 0, scale = 1.25f) }
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
}
