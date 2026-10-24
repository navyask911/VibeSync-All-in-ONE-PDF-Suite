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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
    fun rapidCacheClearAndReacquireNeverRecycleBitmapsExposedToUiLeases() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "renderer_cache_refresh_${System.nanoTime()}.pdf")
        PDFBoxResourceLoader.init(context)
        val engine = PdfRendererEngine(context)
        val cache = BitmapCache.getInstance()
        val leases = mutableListOf<BitmapCache.BitmapLease>()
        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(file)
            }
            assertEquals(1, engine.openDocument(Uri.fromFile(file)).getOrThrow())
            repeat(8) {
                val lease = requireNotNull(engine.renderPageLease(0, 1.0f))
                leases += lease
                cache.clear()
                assertTrue("Every bitmap already leased by UI remains valid.", leases.all { !it.bitmap.isRecycled })
            }
            leases.forEach { it.close() }
            assertTrue("Leased bitmaps remain valid after rapid cache clear/reload cycles.", leases.all { !it.bitmap.isRecycled })
        } finally {
            leases.forEach { runCatching { it.close() } }
            engine.closeDocumentSafely()
            file.delete()
        }
    }

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

            // Stress eviction while Compose may still have recorded draw commands for this bitmap.
            val cache = BitmapCache.getInstance()
            repeat(5) { cache.clear() }
            engine.closeDocumentSafely()
            assertFalse("A leased bitmap must survive cache eviction.", bitmap.isRecycled)

            leases.forEach { it.close() }
            assertFalse(
                "Closing the final lease must not recycle a Bitmap that Compose may still be drawing.",
                bitmap.isRecycled
            )
            cache.clear()
            assertFalse("Cache/history refresh after lease release must remain draw-safe.", bitmap.isRecycled)
            leases.clear()
        } finally {
            leases.forEach { it.close() }
            engine.closeDocumentSafely()
            file.delete()
        }
    }
}
