package com.vibesync.toolkit.pdf

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfEncryptionTest {
    @Test
    fun aes128EncryptedPdfReloadsWithEncodedPasswordAndDecrypts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)
        val work = File(app.cacheDir, "pdf-aes128-${System.nanoTime()}").apply { mkdirs() }
        val source = File(work, "plain.pdf")
        val encrypted = File(work, "encrypted-aes128.pdf")
        val decrypted = File(work, "decrypted.pdf")
        val userPassword = "Usér-Secure-2026!"
        val ownerPassword = "Admin-Café-2026!"
        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(source)
            }

            val engine = PdfBoxToolkitEngine(app)
            val result = engine.encryptPdf(
                sourceUri = Uri.fromFile(source),
                userPassword = userPassword,
                ownerPassword = ownerPassword,
                allowPrinting = false,
                allowCopying = true,
                outputFile = encrypted
            )
            assertTrue("AES-128 encryption should succeed: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue(encrypted.isFile && encrypted.length() > 0L)

            try {
                PDDocument.load(encrypted).use {
                    throw AssertionError("An AES-128 encrypted PDF must reject an empty password.")
                }
            } catch (_: InvalidPasswordException) {
                // Correctly rejected without a password.
            }

            PDDocument.load(encrypted, userPassword).use { userDocument ->
                assertTrue(userDocument.isEncrypted)
                assertEquals(1, userDocument.numberOfPages)
                val permission: AccessPermission = userDocument.currentAccessPermission
                assertFalse(permission.canPrint())
                assertTrue(permission.canExtractContent())
                assertFalse(permission.isOwnerPermission)
            }
            PDDocument.load(encrypted, ownerPassword).use { ownerDocument ->
                assertTrue(ownerDocument.currentAccessPermission.isOwnerPermission)
            }

            val decryptResult = engine.decryptPdf(
                sourceUri = Uri.fromFile(encrypted),
                password = userPassword,
                outputFile = decrypted
            )
            assertTrue("Decrypting the encrypted PDF should succeed.", decryptResult.isSuccess)
            PDDocument.load(decrypted).use { plainDocument ->
                assertFalse(plainDocument.isEncrypted)
                assertEquals(1, plainDocument.numberOfPages)
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
