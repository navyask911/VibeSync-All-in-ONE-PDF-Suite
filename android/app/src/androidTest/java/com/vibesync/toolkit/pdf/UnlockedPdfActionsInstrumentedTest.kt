package com.vibesync.toolkit.pdf

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.presentation.viewer.writeWatermarkedPdfToOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class UnlockedPdfActionsInstrumentedTest {
    @Test
    fun unlockedAes128SessionSupportsSafSaveAndSharePreparation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val password = "Viewer-Session-2026!"
        val plain = File.createTempFile("plain_actions_", ".pdf", context.cacheDir)
        val protected = UriUtils.createExportCacheFile(context, "protected_actions")
        val unlocked = UriUtils.createExportCacheFile(context, "unlocked_actions")
        val saved = UriUtils.createExportCacheFile(context, "saved_actions")
        val firstShare = UriUtils.createExportCacheFile(context, "share_view_actions")
        val finalShare = UriUtils.createExportCacheFile(context, "share_final_actions")
        val engine = PdfBoxToolkitEngine(context)

        try {
            PDDocument().use { document ->
                repeat(2) { document.addPage(PDPage(PDRectangle.A4)) }
                document.save(plain)
            }
            engine.encryptPdf(
                Uri.fromFile(plain),
                password,
                "Owner-Viewer-2026!",
                allowPrinting = true,
                allowCopying = true,
                outputFile = protected
            ).getOrThrow()
            PDDocument.load(protected, password).use { assertTrue("Fixture must be encrypted.", it.isEncrypted) }

            val sessionFile = engine.decryptPdf(Uri.fromFile(protected), password, unlocked).getOrThrow()
            PDDocument.load(sessionFile).use { document ->
                assertFalse("Verified session is readable without a password.", document.isEncrypted)
                assertEquals(2, document.numberOfPages)
            }

            // Same output-stream helper used by the CreateDocument/SAF Download / Save flow.
            FileOutputStream(saved).use { output ->
                writeWatermarkedPdfToOutput(context, Uri.fromFile(sessionFile), output)
            }
            PDDocument.load(saved).use { document ->
                assertFalse("Saved PDF must be readable without the password.", document.isEncrypted)
                assertEquals(2, document.numberOfPages)
            }

            // Exact PDFBox transformations performed by the Activity's share route.
            engine.watermarkPdf(
                sourceUri = Uri.fromFile(sessionFile),
                watermarkText = PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
                outputFile = firstShare
            ).getOrThrow()
            engine.watermarkPdf(
                sourceUri = Uri.fromFile(firstShare),
                watermarkText = PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
                outputFile = finalShare
            ).getOrThrow()
            val shareUri = UriUtils.getShareableUri(context, finalShare)
            assertEquals("content", shareUri.scheme)
            val sharedStream = context.contentResolver.openInputStream(shareUri)
            assertNotNull("FileProvider should expose the prepared share PDF.", sharedStream)
            sharedStream!!.use { input ->
                assertTrue("The FileProvider response contains PDF bytes.",
                    input.readBytes().take(5).toByteArray().contentEquals("%PDF-".toByteArray()))
            }
            PDDocument.load(finalShare).use { document ->
                assertFalse("Shared PDF must be unencrypted.", document.isEncrypted)
                assertEquals(2, document.numberOfPages)
            }
        } finally {
            plain.delete()
            protected.delete()
            unlocked.delete()
            saved.delete()
            firstShare.delete()
            finalShare.delete()
        }
    }
}
