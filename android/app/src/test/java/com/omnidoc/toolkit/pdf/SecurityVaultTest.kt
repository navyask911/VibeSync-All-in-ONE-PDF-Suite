package com.omnidoc.toolkit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.omnidoc.toolkit.pdf.core.security.SecurityVault
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
