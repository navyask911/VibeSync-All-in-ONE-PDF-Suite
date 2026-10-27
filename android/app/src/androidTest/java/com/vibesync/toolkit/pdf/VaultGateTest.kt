package com.vibesync.toolkit.pdf

import android.content.Context
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
        moved.delete()
    }
}
