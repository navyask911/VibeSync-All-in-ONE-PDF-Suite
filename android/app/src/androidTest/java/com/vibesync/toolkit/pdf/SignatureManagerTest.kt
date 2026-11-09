package com.vibesync.toolkit.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.signature.SignatureRepository
import com.vibesync.toolkit.pdf.presentation.editor.SignatureManagerScreen
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignatureManagerTest {
    private lateinit var context: Application
    private lateinit var vault: SecurityVault
    private lateinit var signatureRepository: SignatureRepository

    @get:Rule
    val composeTestRule = createComposeRule()

    private val roundTripId = "instrumented_round_trip"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        vault = SecurityVault(context)
        vault.clearSignatures()
        signatureRepository = SignatureRepository(context, vault)
        signatureRepository.deleteSignature(roundTripId)
    }

    @After
    fun tearDown() {
        signatureRepository.deleteSignature(roundTripId)
        vault.clearSignatures()
    }

    @Test
    fun saveListRenameDeleteLeavesOneSignatureThatStillDecrypts() {
        val first = Bitmap.createBitmap(80, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val second = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        vault.saveSignature("contract", first)
        vault.saveSignature("initials", second)

        assertEquals(2, vault.listSignatures().size)
        assertTrue(vault.renameSignature("contract", "approved_contract"))
        assertTrue(vault.deleteSignature("initials"))
        val remaining = vault.listSignatures()
        assertEquals(1, remaining.size)
        assertEquals("approved_contract", remaining.single().id)
        val decrypted = vault.getSignature("approved_contract")
        assertNotNull(decrypted)
        assertEquals(80, decrypted!!.width)
        decrypted.recycle()
        first.recycle()
        second.recycle()
    }

    @Test
    fun testSignatureCreationAndStorageRoundTrip() {
        composeTestRule.setContent {
            VibeSyncPdfTheme {
                SignatureManagerScreen(
                    onNavigateBack = {},
                    onSelectSignature = { _, _, _, _, _, _ -> },
                    signatureRepository = signatureRepository
                )
            }
        }

        composeTestRule.onNodeWithTag("signature_create_fab").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("signature_capture_name")
            .performTextInput(roundTripId)
        composeTestRule.onNodeWithTag("signature_capture_canvas").performTouchInput {
            down(Offset(width * 0.15f, height * 0.70f))
            moveTo(Offset(width * 0.35f, height * 0.35f), 100)
            moveTo(Offset(width * 0.55f, height * 0.65f), 100)
            moveTo(Offset(width * 0.82f, height * 0.25f), 100)
            up()
        }
        composeTestRule.onNodeWithTag("signature_capture_save").assertIsEnabled().performClick()

        composeTestRule.waitUntil(15_000) {
            signatureRepository.encryptedFile(roundTripId)?.let { it.isFile && it.length() > 0L } == true &&
                signatureRepository.listSignatures().any { it.id == roundTripId }
        }
        val encryptedFile = signatureRepository.encryptedFile(roundTripId)
        assertNotNull("The signature should be stored as an encrypted file", encryptedFile)
        assertTrue(requireNotNull(encryptedFile).canonicalPath.startsWith(
            java.io.File(context.filesDir, "signatures").canonicalPath
        ))

        val restored = signatureRepository.getSignature(roundTripId)
        assertNotNull("The saved signature should decrypt and decode", restored)
        assertEquals(1200, restored!!.width)
        assertEquals(400, restored.height)
        restored.recycle()
    }

    @Test
    fun signatureRetainsArgbInkColorAndRendersInSavedPreview() {
        val signatureId = "argb_yellow_signature"
        signatureRepository.deleteSignature(signatureId)
        try {
            composeTestRule.setContent {
                VibeSyncPdfTheme {
                    SignatureManagerScreen(
                        onNavigateBack = {},
                        onSelectSignature = { _, _, _, _, _, _ -> },
                        signatureRepository = signatureRepository
                    )
                }
            }

            composeTestRule.onNodeWithTag("signature_create_fab").performClick()
            composeTestRule.onNodeWithTag("signature_capture_name").performTextInput(signatureId)
            composeTestRule.onNodeWithTag("signature_ink_yellow").assertIsDisplayed().performClick()
            composeTestRule.onNodeWithTag("signature_capture_pen_size").assertIsDisplayed()
            composeTestRule.onNodeWithTag("signature_capture_canvas").performTouchInput {
                swipe(
                    start = Offset(width * 0.15f, height * 0.52f),
                    end = Offset(width * 0.85f, height * 0.52f),
                    durationMillis = 350
                )
            }
            composeTestRule.onNodeWithTag("signature_capture_save").assertIsEnabled().performClick()

            composeTestRule.waitUntil(15_000) {
                signatureRepository.encryptedFile(signatureId)?.let { it.isFile && it.length() > 0L } == true &&
                    composeTestRule.onAllNodesWithTag("signature_placement_preview")
                        .fetchSemanticsNodes().isNotEmpty()
            }

            val restored = requireNotNull(signatureRepository.getSignature(signatureId))
            try {
                assertEquals(Bitmap.Config.ARGB_8888, restored.config)
                val pixels = IntArray(restored.width * restored.height)
                restored.getPixels(pixels, 0, restored.width, 0, 0, restored.width, restored.height)
                assertTrue(
                    "Saved preview bitmap should preserve full-alpha yellow pixels",
                    pixels.any { pixel ->
                        android.graphics.Color.alpha(pixel) > 0 &&
                            android.graphics.Color.red(pixel) > 220 &&
                            android.graphics.Color.green(pixel) > 220 &&
                            android.graphics.Color.blue(pixel) < 40
                    }
                )
            } finally {
                restored.recycle()
            }
            composeTestRule.onNodeWithTag("signature_placement_preview").assertIsDisplayed()
        } finally {
            signatureRepository.deleteSignature(signatureId)
        }
    }

    @Test
    fun flattenedSignatureBitmapsAreReleasedAfterSuccessfulFlatten() {
        val viewModel = EditorViewModel(context)
        val signature = Bitmap.createBitmap(72, 28, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        viewModel.addSignature(signature, x = 10f, y = 20f)
        val placedBitmap = requireNotNull(viewModel.uiState.value.currentSignatures.single().bitmap)

        viewModel.releaseSignatureBitmapsAfterFlatten()

        assertTrue("temporary signature bitmap should be recycled after flatten", placedBitmap.isRecycled)
        assertTrue(viewModel.uiState.value.currentSignatures.isEmpty())
    }
}
