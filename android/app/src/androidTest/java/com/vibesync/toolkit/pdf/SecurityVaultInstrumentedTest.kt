package com.vibesync.toolkit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.vibesync.toolkit.pdf.data.vault.VaultStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityVaultTest {

    private lateinit var context: Context
    private lateinit var securityVault: SecurityVault

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        securityVault = SecurityVault(context)
        securityVault.clearSignatures()
    }

    @After
    fun tearDown() {
        securityVault.clearSignatures()
    }

    @Test
    fun testMasterKeyInitializationAndSignatureVaulting() {
        // Create sample signature bitmap (200x100 transparent bitmap with black curve)
        val originalBitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            for (x in 20 until 180) {
                setPixel(x, 50, Color.BLACK)
            }
        }

        val signatureId = "exec_sign_001"

        // 1. Vault signature via MasterKey AES-256
        securityVault.saveSignature(signatureId, originalBitmap)

        // 2. Retrieve signature from EncryptedSharedPreferences
        val retrievedBitmap = securityVault.getSignature(signatureId)

        assertNotNull("Retrieved signature from encrypted vault must not be null", retrievedBitmap)
        assertEquals(originalBitmap.width, retrievedBitmap!!.width)
        assertEquals(originalBitmap.height, retrievedBitmap.height)

        // Verify pixel integrity (black pixel preserved after PNG compression & decryption)
        val pixelColor = retrievedBitmap.getPixel(50, 50)
        assertEquals("Pixel color must match encrypted payload", Color.BLACK, pixelColor)
    }

    @Test
    fun testDefaultSignatureStorageAndRetrieval() {
        assertFalse("Default signature should not exist initially", securityVault.hasDefaultSignature())
        assertNull("getDefaultSignature should return null when uninitialized", securityVault.getDefaultSignature())

        val defaultBitmap = Bitmap.createBitmap(150, 75, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }

        securityVault.saveDefaultSignature(defaultBitmap)

        assertTrue("hasDefaultSignature must return true after vaulting", securityVault.hasDefaultSignature())

        val retrieved = securityVault.getDefaultSignature()
        assertNotNull(retrieved)
        assertEquals(150, retrieved!!.width)
        assertEquals(75, retrieved.height)
    }

    @Test
    fun testPdfVaultFileImportAndDecryption() {
        val sourceDocument = PdfDocument()
        val sourceBytes = ByteArrayOutputStream().use { output ->
            try {
                val page = sourceDocument.startPage(
                    PdfDocument.PageInfo.Builder(595, 842, 1).create()
                )
                page.canvas.drawText(
                    "Vault import integrity marker",
                    42f,
                    80f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = Color.BLACK }
                )
                sourceDocument.finishPage(page)
                sourceDocument.writeTo(output)
                output.toByteArray()
            } finally {
                sourceDocument.close()
            }
        }

        val storage = VaultStorage(context)
        val progress = mutableListOf<Float>()
        val encrypted = storage.importPdf(
            inputStream = ByteArrayInputStream(sourceBytes),
            displayName = "Integrity_Round_Trip.pdf",
            expectedBytes = sourceBytes.size.toLong(),
            onProgress = { progress += it }
        )
        var decrypted: File? = null
        try {
            assertTrue("Encrypted vault output must exist", encrypted.isFile && encrypted.length() > 0L)
            assertTrue("Vault output must be stored under filesDir/vault",
                encrypted.canonicalFile.parentFile == File(context.filesDir, "vault").canonicalFile)
            assertTrue("Only encrypted .vlt files should be listed", encrypted.name.endsWith(".vlt"))
            assertTrue(storage.listVaultFiles().any { it.canonicalFile == encrypted.canonicalFile })
            assertTrue("Import progress should reach 100%", progress.isNotEmpty() && progress.last() >= 0.999f)

            decrypted = storage.decryptToCache(encrypted)
            assertTrue("Decrypted PDF must be written to the private cache", decrypted.isFile && decrypted.length() > 0L)
            assertArrayEquals("AES-GCM decryption must reproduce every source byte", sourceBytes, decrypted.readBytes())

            val exportDirectory = File(context.cacheDir, "pdf_cache").apply { mkdirs() }
            val appExport = File(exportDirectory, "VibeSync_VaultScannerRegression.pdf")
            appExport.writeBytes(sourceBytes)
            try {
                assertTrue(
                    "The vault scanner should include VibeSync PDFs in internal export cache",
                    storage.listMovableDocuments().any { it.canonicalFile == appExport.canonicalFile }
                )
            } finally {
                appExport.delete()
            }
        } finally {
            decrypted?.delete()
            encrypted.delete()
            storage.clearDecryptedCache()
        }
    }

    @Test
    fun testClearSignaturesPurgesEncryptedStorage() {
        val bitmapA = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val bitmapB = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)

        securityVault.saveSignature("sig_a", bitmapA)
        securityVault.saveSignature("sig_b", bitmapB)
        securityVault.saveDefaultSignature(bitmapA)

        assertNotNull(securityVault.getSignature("sig_a"))
        assertNotNull(securityVault.getSignature("sig_b"))
        assertTrue(securityVault.hasDefaultSignature())

        // Purge
        securityVault.clearSignatures()

        assertNull("sig_a must be deleted", securityVault.getSignature("sig_a"))
        assertNull("sig_b must be deleted", securityVault.getSignature("sig_b"))
        assertFalse("Default signature must be deleted", securityVault.hasDefaultSignature())
    }
}
