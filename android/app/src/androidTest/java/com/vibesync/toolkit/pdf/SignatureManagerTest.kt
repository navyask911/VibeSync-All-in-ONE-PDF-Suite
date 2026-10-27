package com.vibesync.toolkit.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignatureManagerTest {
    private lateinit var context: Application
    private lateinit var vault: SecurityVault

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        vault = SecurityVault(context)
        vault.clearSignatures()
    }

    @After
    fun tearDown() {
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
