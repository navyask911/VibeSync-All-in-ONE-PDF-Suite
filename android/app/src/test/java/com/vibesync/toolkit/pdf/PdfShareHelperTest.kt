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
    private lateinit var thumbnailDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        shareDirectory = File(context.cacheDir, "pdf_cache/share_helper_tests").apply {
            mkdirs()
        }
        persistentShareDirectory = File(context.filesDir, "shared_pdfs").apply {
            deleteRecursively()
            mkdirs()
        }
        thumbnailDirectory = File(context.cacheDir, "pdf_cache/share_helper_thumbnails").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        persistentShareDirectory.deleteRecursively()
        thumbnailDirectory.deleteRecursively()
    }

    @Test
    fun chooserAttachesPdfAndFirstPageJpegAsSendMultipleWithReadGrants() {
        val pdf = createPdf("single_${System.nanoTime()}.pdf")
        var stagedPdf: File? = null
        var previewJpeg: File? = null

        val chooser = PdfShareHelper.createChooserIntent(
            context,
            listOf(pdf),
            "Share PDF",
            uriProvider = { _, file ->
                when (file.extension.lowercase()) {
                    "pdf" -> stagedPdf = file
                    "jpg" -> previewJpeg = file
                }
                Uri.parse("content://test.provider/${file.name}")
            },
            thumbnailGenerator = { _, sourcePdf ->
                createThumbnailFixture(sourcePdf).also { previewJpeg = it }
            }
        )
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNotNull(target)
        val sendIntent = requireNotNull(target)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, sendIntent.action)
        assertEquals("application/pdf", sendIntent.type)
        assertEquals(pdf.name, sendIntent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals(pdf.name, sendIntent.getStringExtra(Intent.EXTRA_SUBJECT))

        @Suppress("DEPRECATION")
        val streams = requireNotNull(sendIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(2, streams.size)
        assertTrue(streams[0].lastPathSegment.orEmpty().endsWith(".pdf"))
        assertTrue(streams[1].lastPathSegment.orEmpty().endsWith(".jpg"))
        assertEquals("content", streams[0].scheme)
        assertEquals("content", streams[1].scheme)

        assertTrue(sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(sendIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, sendIntent.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)
        assertEquals(0, chooser.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)

        assertEquals(2, sendIntent.clipData?.itemCount)
        assertEquals(2, chooser.clipData?.itemCount)
        assertEquals(streams[0], sendIntent.clipData?.getItemAt(0)?.uri)
        assertEquals(streams[1], sendIntent.clipData?.getItemAt(1)?.uri)
        assertEquals("application/pdf", sendIntent.clipData?.description?.getMimeType(0))
        assertEquals("image/jpeg", sendIntent.clipData?.description?.getMimeType(1))
        assertEquals(streams[0], chooser.clipData?.getItemAt(0)?.uri)
        assertEquals(streams[1], chooser.clipData?.getItemAt(1)?.uri)

        val staged = requireNotNull(stagedPdf)
        assertEquals(persistentShareDirectory.canonicalFile, staged.parentFile?.canonicalFile)
        assertNotEquals(pdf.canonicalPath, staged.canonicalPath)
        assertArrayEquals(pdf.readBytes(), staged.readBytes())
        val preview = requireNotNull(previewJpeg)
        assertEquals(thumbnailDirectory.canonicalFile, preview.parentFile?.canonicalFile)
        assertTrue(preview.length() >= 4L)
    }

    @Test
    fun multiplePdfShareIncludesPdfAndThumbnailUriPairsInOrder() {
        val first = createPdf("first_${System.nanoTime()}.pdf")
        val second = createPdf("second_${System.nanoTime()}.pdf")

        val chooser = PdfShareHelper.createChooserIntent(
            context,
            listOf(first, second),
            "Share PDFs",
            forceMultiple = true,
            uriProvider = { _, file -> Uri.parse("content://test.provider/${file.name}") },
            thumbnailGenerator = { _, sourcePdf -> createThumbnailFixture(sourcePdf) }
        )
        @Suppress("DEPRECATION")
        val target = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)
        @Suppress("DEPRECATION")
        val streams = requireNotNull(target.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))

        assertEquals(Intent.ACTION_SEND_MULTIPLE, target.action)
        assertEquals("application/pdf", target.type)
        assertEquals(4, streams.size)
        assertTrue(streams[0].lastPathSegment.orEmpty().endsWith(".pdf"))
        assertTrue(streams[1].lastPathSegment.orEmpty().endsWith(".jpg"))
        assertTrue(streams[2].lastPathSegment.orEmpty().endsWith(".pdf"))
        assertTrue(streams[3].lastPathSegment.orEmpty().endsWith(".jpg"))
        assertEquals(4, target.clipData?.itemCount)
        assertEquals(4, chooser.clipData?.itemCount)
        streams.forEachIndexed { index, uri ->
            assertEquals(uri, target.clipData?.getItemAt(index)?.uri)
            assertEquals(uri, chooser.clipData?.getItemAt(index)?.uri)
        }
        assertEquals("application/pdf", target.clipData?.description?.getMimeType(0))
        assertEquals("image/jpeg", target.clipData?.description?.getMimeType(1))
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, target.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)
        assertEquals(0, chooser.flags and Intent.FLAG_ACTIVITY_NO_HISTORY)
    }

    @Test
    fun stagingPreservesARealPdfPagesSizesRotationAndBytes() {
        val source = createPdf("rotated_mixed_pages_${System.nanoTime()}.pdf", pageCount = 2, rotatedSecondPage = true)
        var staged: File? = null

        PdfShareHelper.createChooserIntent(
            context,
            listOf(source),
            "Share PDF",
            uriProvider = { _, file ->
                if (file.extension.equals("pdf", ignoreCase = true)) staged = file
                Uri.parse("content://test.provider/${file.name}")
            },
            thumbnailGenerator = { _, sourcePdf -> createThumbnailFixture(sourcePdf) }
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
        val empty = File(shareDirectory, "empty_${System.nanoTime()}.pdf").apply {
            writeBytes(byteArrayOf())
        }
        PdfShareHelper.createChooserIntent(
            context,
            listOf(empty),
            "Share PDF",
            uriProvider = { _, file -> Uri.parse("content://test.provider/${file.name}") }
        )
    }

    private fun createPdf(
        name: String,
        pageCount: Int = 1,
        rotatedSecondPage: Boolean = false
    ): File {
        val file = File(shareDirectory, name)
        PDDocument().use { document ->
            repeat(pageCount) { index ->
                val page = PDPage(if (rotatedSecondPage && index == 1) {
                    PDRectangle(720f, 420f)
                } else {
                    PDRectangle(420f, 720f)
                })
                if (rotatedSecondPage && index == 1) page.rotation = 90
                document.addPage(page)
            }
            document.save(file)
        }
        return file
    }

    private fun createThumbnailFixture(sourcePdf: File): File {
        val file = File(
            thumbnailDirectory,
            "${sourcePdf.nameWithoutExtension}_${System.nanoTime()}.jpg"
        )
        // The JVM test injects a deterministic JPEG-signature fixture; the native PdfRenderer
        // is exercised by PdfFileProviderInstrumentationTest on the Android emulator.
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        return file
    }
}
