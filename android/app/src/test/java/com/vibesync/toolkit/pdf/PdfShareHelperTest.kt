package com.vibesync.toolkit.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.presentation.common.PdfShareHelper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
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
        PDFBoxResourceLoader.init(context)
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
    fun chooserSharesOnlyOnePdfWithStandardSendIntentAndReadGrant() {
        val pdf = createPdf("single_${System.nanoTime()}.pdf")
        var stagedPdf: File? = null
        var uriCalls = 0
        val chooser = PdfShareHelper.createChooserIntent(
            context,
            pdf,
            "Share PDF",
            uriProvider = { _, file ->
                uriCalls++
                stagedPdf = file
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
        assertEquals(1, uriCalls)

        @Suppress("DEPRECATION")
        val stream = requireNotNull(sendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(stream.lastPathSegment.orEmpty().endsWith(".pdf"))
        assertEquals("content", stream.scheme)
        assertTrue(sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(sendIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, sendIntent.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)
        assertEquals(0, chooser.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)
        assertEquals(1, sendIntent.clipData?.itemCount)
        assertEquals(1, chooser.clipData?.itemCount)
        assertEquals("application/pdf", sendIntent.clipData?.description?.getMimeType(0))
        assertEquals(stream, sendIntent.clipData?.getItemAt(0)?.uri)
        assertEquals(stream, chooser.clipData?.getItemAt(0)?.uri)

        val staged = requireNotNull(stagedPdf)
        assertEquals(persistentShareDirectory.canonicalFile, staged.parentFile?.canonicalFile)
        assertNotEquals(pdf.canonicalPath, staged.canonicalPath)
        assertArrayEquals(pdf.readBytes(), staged.readBytes())
    }

    @Test
    fun stagingPreservesRealPdfBytesPageGeometryAndRotation() {
        val source = createPdf(
            "rotated_mixed_pages_${System.nanoTime()}.pdf",
            pageCount = 2,
            rotatedSecondPage = true
        )
        var staged: File? = null
        PdfShareHelper.createChooserIntent(
            context,
            source,
            "Share PDF",
            uriProvider = { _, file ->
                staged = file
                Uri.parse("content://test.provider/${file.name}")
            }
        )
        val sharedCopy = requireNotNull(staged)
        assertArrayEquals(source.readBytes(), sharedCopy.readBytes())
        PDDocument.load(source).use { original ->
            PDDocument.load(sharedCopy).use { copied ->
                assertEquals(original.numberOfPages, copied.numberOfPages)
                for (index in 0 until original.numberOfPages) {
                    assertEquals(original.getPage(index).mediaBox.width, copied.getPage(index).mediaBox.width, 0.01f)
                    assertEquals(original.getPage(index).mediaBox.height, copied.getPage(index).mediaBox.height, 0.01f)
                    assertEquals(original.getPage(index).rotation, copied.getPage(index).rotation)
                }
            }
        }
    }

    @Test(expected = java.io.IOException::class)
    fun emptyPdfIsRejectedBeforeFileProviderUriCreation() {
        val empty = File(shareDirectory, "empty_${System.nanoTime()}.pdf").apply { writeBytes(byteArrayOf()) }
        PdfShareHelper.createChooserIntent(
            context,
            empty,
            "Share PDF",
            uriProvider = { _, file -> Uri.parse("content://test.provider/${file.name}") }
        )
    }

    private fun createPdf(name: String, pageCount: Int = 1, rotatedSecondPage: Boolean = false): File {
        val file = File(shareDirectory, name)
        PDDocument().use { document ->
            repeat(pageCount) { index ->
                val page = PDPage(if (rotatedSecondPage && index == 1) PDRectangle(720f, 420f) else PDRectangle(420f, 720f))
                if (rotatedSecondPage && index == 1) page.rotation = 90
                document.addPage(page)
            }
            document.save(file)
        }
        return file
    }
}
