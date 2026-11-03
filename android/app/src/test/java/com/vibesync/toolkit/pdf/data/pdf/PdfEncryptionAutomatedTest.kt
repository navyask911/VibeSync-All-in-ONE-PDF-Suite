package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfEncryptionAutomatedTest {
    private lateinit var context: Context
    private lateinit var workDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        workDirectory = File(context.cacheDir, "pdf-encryption-automated-${System.nanoTime()}").apply {
            check(mkdirs())
        }
    }

    @After
    fun tearDown() {
        workDirectory.deleteRecursively()
    }

    @Test
    fun testPdfEncryptionAndDecryptionSuccess() = runBlocking {
        val source = createReadablePdf()
        val encrypted = File(workDirectory, "encrypted-round-trip.pdf")
        val engine = PdfBoxToolkitEngine(context, billingManager = null)

        val result = engine.encryptPdf(
            sourceUri = Uri.fromFile(source),
            userPassword = "test123",
            ownerPassword = "owner123",
            allowPrinting = true,
            allowCopying = true,
            outputFile = encrypted
        )

        assertTrue("PDF encryption failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue("Encrypted PDF was not written.", encrypted.isFile && encrypted.length() > 0L)

        PDDocument.load(encrypted, "test123").use { document ->
            assertTrue("The output PDF must remain encrypted.", document.isEncrypted)
            val extractedText = PDFTextStripper().getText(document)
            assertTrue(
                "The encrypted PDF must retain readable source text. Extracted: $extractedText",
                extractedText.contains(SOURCE_TEXT)
            )
        }
    }

    @Test
    fun testPdfDecryptionFailsWithWrongPassword() = runBlocking {
        val source = createReadablePdf()
        val encrypted = File(workDirectory, "encrypted-wrong-password.pdf")
        val engine = PdfBoxToolkitEngine(context, billingManager = null)

        val result = engine.encryptPdf(
            sourceUri = Uri.fromFile(source),
            userPassword = "test123",
            ownerPassword = "owner123",
            allowPrinting = true,
            allowCopying = true,
            outputFile = encrypted
        )
        assertTrue("Fixture encryption failed: ${result.exceptionOrNull()}", result.isSuccess)

        var rejectedWrongPassword = false
        try {
            PDDocument.load(encrypted, "wrongpassword").use { document ->
                assertFalse("The wrong password unexpectedly opened the encrypted PDF.", document.isEncrypted)
            }
        } catch (_: InvalidPasswordException) {
            rejectedWrongPassword = true
        }
        assertTrue("Opening with the wrong password must throw InvalidPasswordException.", rejectedWrongPassword)
    }

    private fun createReadablePdf(): File {
        val file = File(workDirectory, "source-readable.pdf")
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText(SOURCE_TEXT)
                content.endText()
            }
            document.save(file)
        }
        return file
    }

    private companion object {
        const val SOURCE_TEXT = "VibeSync encryption round-trip text"
    }
}
