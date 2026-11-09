package com.vibesync.toolkit.pdf

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.data.vault.VaultStorage
import com.vibesync.toolkit.pdf.presentation.vault.VaultGate
import com.vibesync.toolkit.pdf.presentation.vault.VaultScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun successfulMockAuthenticationUnlocksVaultAndScreenStartsLocked() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = VaultStorage(context)
        var pendingResult: ((Boolean) -> Unit)? = null
        composeRule.setContent {
            VaultScreen(
                onNavigateBack = {},
                onAuthenticate = { callback -> pendingResult = callback },
                storage = storage
            )
        }

        composeRule.onNodeWithTag("vault_lock_message").assertExists()
        composeRule.onNodeWithTag("vault_file_list").assertDoesNotExist()
        composeRule.onNodeWithTag("vault_unlock_button").performClick()
        composeRule.runOnIdle { pendingResult?.invoke(true) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("vault_file_list").assertExists()
        assertEquals(VaultGate.Decision.GRANTED, VaultGate.decisionFor(success = true))
    }

    @Test
    fun failedAuthenticationAndBiometricLockoutAreDenied() {
        assertEquals(VaultGate.Decision.DENIED, VaultGate.decisionFor(success = false))
        assertEquals(
            VaultGate.Decision.DENIED,
            VaultGate.decisionFor(success = false, errorCode = BiometricPrompt.ERROR_LOCKOUT)
        )
        assertEquals(
            VaultGate.Decision.DENIED,
            VaultGate.decisionFor(success = false, errorCode = BiometricPrompt.ERROR_LOCKOUT_PERMANENT)
        )
    }

    @Test
    fun vaultPdfItemOpensThroughInternalViewerCallbackWithoutExternalChooser() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = android.graphics.pdf.PdfDocument()
        val sourceBytes = ByteArrayOutputStream().use { output ->
            try {
                val page = source.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
                )
                page.canvas.drawText(
                    "Internal VibeSync vault viewer",
                    36f,
                    80f,
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
                )
                source.finishPage(page)
                source.writeTo(output)
                output.toByteArray()
            } finally {
                source.close()
            }
        }
        val storage = VaultStorage(context)
        val encrypted = storage.importPdf(
            inputStream = ByteArrayInputStream(sourceBytes),
            displayName = "internal_viewer_route_test.pdf",
            expectedBytes = sourceBytes.size.toLong()
        )
        val openedUri = java.util.concurrent.atomic.AtomicReference<android.net.Uri?>(null)

        try {
            composeRule.setContent {
                VaultScreen(
                    onNavigateBack = {},
                    onAuthenticate = { callback -> callback(true) },
                    storage = storage,
                    onOpenPdf = { uri -> openedUri.set(uri) }
                )
            }

            composeRule.onNodeWithTag("vault_unlock_button").performClick()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("vault_open_${encrypted.name}")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("vault_open_${encrypted.name}").performClick()
            composeRule.waitUntil(10_000) { openedUri.get() != null }

            val uri = requireNotNull(openedUri.get())
            assertEquals("file", uri.scheme)
            val decrypted = File(requireNotNull(uri.path))
            assertTrue("Internal decrypted cache file must exist", decrypted.isFile)
            assertEquals(
                File(context.cacheDir, "pdf_cache").canonicalFile,
                decrypted.canonicalFile.parentFile
            )
            assertTrue(decrypted.readBytes().contentEquals(sourceBytes))
        } finally {
            storage.clearDecryptedCache()
            encrypted.delete()
        }
    }

    @Test
    fun movingSavedPdfIntoVaultRemovesItFromMainScanDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scans = File(context.filesDir, "scans").apply { mkdirs() }
        val source = File(scans, "vault_move_test.pdf").apply { writeText("sample-pdf-placeholder") }
        val storage = VaultStorage(context)

        assertTrue(storage.listMovableScans().any { it == source })
        val moved = storage.moveIntoVault(source)

        assertFalse(source.exists())
        assertTrue(moved.isFile)
        assertFalse(storage.listMovableScans().any { it.name == source.name })
        assertTrue(storage.listVaultFiles().any { it.name == moved.name })
        assertEquals("vault_move_test.pdf", storage.displayName(moved))
        val ciphertextText = String(moved.readBytes(), Charsets.ISO_8859_1)
        assertFalse("Vault contents must not remain plaintext at rest.", ciphertextText.contains("sample-pdf-placeholder"))

        val decrypted = storage.decryptToCache(moved)
        assertEquals("sample-pdf-placeholder", decrypted.readText())
        decrypted.delete()
        storage.clearDecryptedCache()
        moved.delete()
    }
}
