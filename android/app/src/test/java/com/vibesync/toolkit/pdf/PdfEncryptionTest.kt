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

    @Test
    fun encryptingPlainPdfWatermarksBeforeAes128AndCanBeDecrypted() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)
        val work = File(app.cacheDir, "pdf-encrypt-watermark-${System.nanoTime()}").apply { mkdirs() }
        val source = File(work, "plain-source.pdf")
        val encrypted = File(work, "watermarked-encrypted.pdf")
        val decrypted = File(work, "decrypted.pdf")
        val newPassword = "abcd 1234"
        val ownerPassword = "abcd 1234_admin"
        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(source)
            }

            val engine = PdfBoxToolkitEngine(app)
            val encryptResult = engine.encryptPdf(
                sourceUri = Uri.fromFile(source),
                userPassword = newPassword,
                ownerPassword = ownerPassword,
                allowPrinting = true,
                allowCopying = true,
                outputFile = encrypted
            )
            assertTrue(
                "Watermark + AES-128 encryption should succeed: ${encryptResult.exceptionOrNull()}",
                encryptResult.isSuccess
            )

            PDDocument.load(encrypted, newPassword).use { protectedDocument ->
                assertTrue(protectedDocument.isEncrypted)
                assertEquals(1, protectedDocument.numberOfPages)
            }

            val decryptResult = engine.decryptPdf(
                sourceUri = Uri.fromFile(encrypted),
                password = newPassword,
                outputFile = decrypted
            )
            assertTrue("The new password must decrypt the encrypted output.", decryptResult.isSuccess)
            PDDocument.load(decrypted).use { plainDocument ->
                assertFalse(plainDocument.isEncrypted)
                assertEquals(1, plainDocument.numberOfPages)
                assertEquals(
                    "true",
                    plainDocument.documentInformation.getCustomMetadataValue("VibeSync-Watermarked-V2")
                )
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun reencryptingAlreadyProtectedPdfReplacesItsOldPassword() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)
        val work = File(app.cacheDir, "pdf-reencrypt-${System.nanoTime()}").apply { mkdirs() }
        val plainSource = File(work, "source.pdf")
        val originallyEncrypted = File(work, "source-encrypted.pdf")
        val replacedEncryption = File(work, "source-reencrypted.pdf")
        val decrypted = File(work, "source-reencrypted-decrypted.pdf")
        val oldPassword = "old source 123"
        val oldOwnerPassword = "old owner 456"
        val newPassword = "new target 789"
        val newOwnerPassword = "new owner 012"
        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(plainSource)
            }

            val engine = PdfBoxToolkitEngine(app)
            val firstEncryption = engine.encryptPdf(
                sourceUri = Uri.fromFile(plainSource),
                userPassword = oldPassword,
                ownerPassword = oldOwnerPassword,
                allowPrinting = true,
                allowCopying = true,
                outputFile = originallyEncrypted
            )
            assertTrue(
                "Fixture PDF must be encrypted: ${firstEncryption.exceptionOrNull()}",
                firstEncryption.isSuccess
            )

            val replacement = engine.encryptPdf(
                sourceUri = Uri.fromFile(originallyEncrypted),
                userPassword = newPassword,
                ownerPassword = newOwnerPassword,
                allowPrinting = false,
                allowCopying = true,
                outputFile = replacedEncryption,
                oldPassword = oldPassword
            )
            assertTrue(
                "Re-encrypting with the source password should succeed: ${replacement.exceptionOrNull()}",
                replacement.isSuccess
            )

            PDDocument.load(replacedEncryption, newPassword).use { newCredentialDocument ->
                assertTrue(newCredentialDocument.isEncrypted)
                assertEquals(1, newCredentialDocument.numberOfPages)
                assertFalse(newCredentialDocument.currentAccessPermission.canPrint())
                assertTrue(newCredentialDocument.currentAccessPermission.canExtractContent())
            }

            try {
                PDDocument.load(replacedEncryption, oldPassword).use {
                    throw AssertionError("The previous password must no longer unlock the PDF.")
                }
            } catch (_: InvalidPasswordException) {
                // The old security settings were replaced.
            }

            val decryptResult = engine.decryptPdf(
                sourceUri = Uri.fromFile(replacedEncryption),
                password = newPassword,
                outputFile = decrypted
            )
            assertTrue("The replacement password must decrypt the new output.", decryptResult.isSuccess)
            PDDocument.load(decrypted).use { finalDocument ->
                assertFalse(finalDocument.isEncrypted)
                assertEquals(1, finalDocument.numberOfPages)
            }
        } finally {
            work.deleteRecursively()
        }
    }

}
