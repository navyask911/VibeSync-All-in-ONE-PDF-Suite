package com.vibesync.toolkit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import com.vibesync.toolkit.pdf.presentation.editor.SignatureManagerScreen
import com.vibesync.toolkit.pdf.presentation.scanner.ManualCropScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var vault: SecurityVault

    @Before
    fun setUp() {
        vault = SecurityVault(ApplicationProvider.getApplicationContext<Context>())
        vault.clearSignatures()
    }

    @After
    fun tearDown() {
        vault.clearSignatures()
    }

    @Test
    fun manualCropScreenDisplaysFourCornerEditorAndCommitsCroppedBitmap() {
        val source = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        var committed: Bitmap? = null
        composeRule.setContent {
            ManualCropScreen(
                onNavigateBack = {},
                onCommit = { committed = it },
                initialBitmap = source
            )
        }

        composeRule.onNodeWithTag("manual_crop_handles").assertExists()
        composeRule.onNodeWithTag("manual_crop_commit").assertExists().performClick()
        composeRule.runOnIdle {
            assertNotNull(committed)
            assertEquals(48, committed!!.width)
            assertEquals(32, committed!!.height)
            assertTrue(!committed!!.isRecycled)
        }
        committed?.recycle()
        source.recycle()
    }

    @Test
    fun signatureManagerShowsEncryptedEntryAndOffersResizableRotatablePlacement() {
        val saved = Bitmap.createBitmap(64, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        vault.saveSignature("test-signature", saved)
        saved.recycle()
        var placed: Bitmap? = null
        var outputWidth = 0f
        var outputHeight = 0f
        var outputRotation = 0f
        composeRule.setContent {
            SignatureManagerScreen(
                onNavigateBack = {},
                onSelectSignature = { bitmap, _, _, width, height, rotation ->
                    placed = bitmap
                    outputWidth = width
                    outputHeight = height
                    outputRotation = rotation
                },
                securityVault = vault
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("signature_item_test-signature").assertExists()
        composeRule.onNodeWithTag("signature_select_test-signature").performClick()
        composeRule.onNodeWithTag("signature_placement_preview").assertExists()
        composeRule.onNodeWithTag("signature_size_slider").assertExists()
        composeRule.onNodeWithTag("signature_rotation_slider").assertExists()
        composeRule.onNodeWithTag("signature_place_button").performClick()
        composeRule.runOnIdle {
            assertNotNull(placed)
            assertTrue(outputWidth > 0f)
            assertTrue(outputHeight > 0f)
            assertEquals(0f, outputRotation, 0.001f)
        }
        placed?.recycle()
    }
}
