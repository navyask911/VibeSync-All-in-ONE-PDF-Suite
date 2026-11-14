package com.vibesync.toolkit.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.presentation.common.PdfShareHelper
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PdfShareHelperTest {
    private lateinit var context: Context
    private lateinit var shareDirectory: File
    private lateinit var persistentShareDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shareDirectory = File(context.cacheDir, "pdf_cache/share_helper_tests").apply { mkdirs() }
        persistentShareDirectory = File(context.filesDir, "shared_pdfs").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        persistentShareDirectory.deleteRecursively()
    }

    @Test
    fun chooserStagesPdfInFilesPathAndAddsPreviewMetadataAndPdfClipMimeType() {
        val pdf = File(shareDirectory, "single.pdf").apply {
            writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46))
        }
        var stagedFile: File? = null

        val chooser = PdfShareHelper.createChooserIntent(
            context,
            listOf(pdf),
            "Share PDF",
            uriProvider = { _, file ->
                stagedFile = file
                Uri.parse("content://test.provider/${file.name}")
            }
        )
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNotNull(target)
        val sendIntent = requireNotNull(target)
        assertEquals(Intent.ACTION_SEND, sendIntent.action)
        assertEquals("application/pdf", sendIntent.type)
        assertEquals(pdf.name, sendIntent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(pdf.name, sendIntent.getStringExtra(Intent.EXTRA_SUBJECT))
        @Suppress("DEPRECATION")
        val stream = sendIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        assertNotNull(stream)
        assertEquals("content", requireNotNull(stream).scheme)
        assertTrue(sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(stream, sendIntent.clipData?.getItemAt(0)?.uri)
        assertEquals(stream, chooser.clipData?.getItemAt(0)?.uri)
        assertEquals("application/pdf", sendIntent.clipData?.description?.getMimeType(0))
        assertEquals("application/pdf", chooser.clipData?.description?.getMimeType(0))

        val staged = requireNotNull(stagedFile)
        assertEquals(persistentShareDirectory.canonicalFile, staged.parentFile?.canonicalFile)
        assertNotEquals(pdf.canonicalPath, staged.canonicalPath)
        assertArrayEquals(pdf.readBytes(), staged.readBytes())
    }

    @Test
    fun multiplePdfShareUsesSendMultipleAndIncludesEveryUriInClipData() {
        val first = File(shareDirectory, "first.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val second = File(shareDirectory, "second.pdf").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        val chooser = PdfShareHelper.createChooserIntent(
            context,
            listOf(first, second),
            "Share PDFs",
            forceMultiple = true,
            uriProvider = { _, file -> Uri.parse("content://test.provider/${file.name}") }
        )
        @Suppress("DEPRECATION")
        val target = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)

        assertEquals(Intent.ACTION_SEND_MULTIPLE, target.action)
        assertEquals("application/pdf", target.type)
        assertEquals(first.name, target.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(first.name, target.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(2, target.clipData?.itemCount)
        assertEquals(2, chooser.clipData?.itemCount)
        assertEquals("application/pdf", target.clipData?.description?.getMimeType(0))
        assertEquals("application/pdf", chooser.clipData?.description?.getMimeType(0))
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test(expected = java.io.IOException::class)
    fun emptyPdfIsRejectedBeforeFileProviderUriCreation() {
        val empty = File(shareDirectory, "empty.pdf").apply { writeBytes(byteArrayOf()) }
        PdfShareHelper.createChooserIntent(
            context,
            listOf(empty),
            "Share PDF",
            uriProvider = { _, file -> Uri.parse("content://test.provider/${file.name}") }
        )
    }
}
