package com.vibesync.toolkit.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.presentation.common.PdfShareHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfFileProviderInstrumentationTest {


    @Test
    fun sharedPdfUriOpensAndRendersTheActualFirstPageWithoutChangingPageGeometry() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "whatsapp_preview_${System.nanoTime()}.pdf")
        var staged: File? = null
        try {
            PDDocument().use { document ->
                val firstPage = PDPage(PDRectangle.A4)
                document.addPage(firstPage)
                PDPageContentStream(document, firstPage).use { stream ->
                    stream.setNonStrokingColor(255, 0, 0)
                    stream.addRect(0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                    stream.fill()
                }
                document.addPage(PDPage(PDRectangle(420f, 720f)).apply { rotation = 90 })
                document.save(source)
            }

            val chooser = PdfShareHelper.createChooserIntent(
                context,
                listOf(source),
                "Share PDF",
                uriProvider = { shareContext, file ->
                    staged = file
                    UriUtils.getShareableUri(shareContext, file)
                }
            )
            @Suppress("DEPRECATION")
            val sendIntent = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)
            @Suppress("DEPRECATION")
            val sharedUri = requireNotNull(sendIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)
            assertEquals(Intent.ACTION_SEND, sendIntent.action)
            assertEquals("application/pdf", sendIntent.type)
            assertEquals("application/pdf", sendIntent.clipData?.description?.getMimeType(0))
            assertTrue(sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertArrayEquals(source.readBytes(), requireNotNull(staged).readBytes())

            context.contentResolver.openFileDescriptor(sharedUri, "r").use { descriptor ->
                assertNotNull(descriptor)
                PdfRenderer(requireNotNull(descriptor)).use { renderer ->
                    assertEquals(2, renderer.pageCount)
                    renderer.openPage(0).use { firstPage ->
                        assertTrue("First page should retain portrait geometry.", firstPage.width < firstPage.height)
                        val bitmap = Bitmap.createBitmap(firstPage.width, firstPage.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            firstPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertEquals("The actual first page should render through the shared URI.", Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    renderer.openPage(1).use { secondPage ->
                        assertTrue("The second page rotation should remain landscape when rendered.", secondPage.width > secondPage.height)
                    }
                }
            }
        } finally {
            source.delete()
            staged?.delete()
        }
    }

    @Test
    fun fileProviderOpensEveryAppOwnedPdfOutputRoot() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuredDirectories = listOf(
            File(context.cacheDir, "pdf_cache"),
            File(context.filesDir, "documents"),
            File(context.filesDir, "scans"),
            File(context.filesDir, "VibeSyncScans"),
            File(context.filesDir, "shared_pdfs")
        )

        configuredDirectories.forEachIndexed { index, directory ->
            check(directory.exists() || directory.mkdirs()) {
                "Could not create provider test directory: ${directory.absolutePath}"
            }
            val pdf = File(directory, "provider_root_${index}.pdf")
            try {
                pdf.writeBytes("%PDF-1.4\n% FileProvider test\n".toByteArray(Charsets.US_ASCII))
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    pdf
                )

                assertEquals("content", uri.scheme)
                context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                    assertNotNull("Provider could not open ${pdf.absolutePath}", descriptor)
                }
            } finally {
                pdf.delete()
            }
        }
    }
}
