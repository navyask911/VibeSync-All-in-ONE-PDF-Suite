package com.vibesync.toolkit.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.presentation.common.PdfShareHelper
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import kotlin.math.abs
import java.security.Security

@RunWith(AndroidJUnit4::class)
class PdfFileProviderInstrumentationTest {


    @Test
    fun sharedPdfUriOpensAndRendersTheActualFirstPageWithoutChangingPageGeometry() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "whatsapp_preview_${System.nanoTime()}.pdf")
        var staged: File? = null
        try {
            PDDocument().use { document ->
                val firstPage = PDPage(PDRectangle.A4)
                document.addPage(firstPage)
                PDPageContentStream(document, firstPage).use { stream ->
                    stream.setNonStrokingColor(255, 0, 0)
                    stream.addRect(0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                    stream.fill()
                }
                document.addPage(PDPage(PDRectangle(420f, 720f)).apply { rotation = 90 })
                document.save(source)
            }

            val chooser = PdfShareHelper.createChooserIntent(
                context,
                listOf(source),
                "Share PDF",
                uriProvider = { shareContext, file ->
                    staged = file
                    UriUtils.getShareableUri(shareContext, file)
                }
            )
            @Suppress("DEPRECATION")
            val sendIntent = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)
            @Suppress("DEPRECATION")
            val sharedUri = requireNotNull(sendIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)
            assertEquals(Intent.ACTION_SEND, sendIntent.action)
            assertEquals("application/pdf", sendIntent.type)
            assertEquals("application/pdf", sendIntent.clipData?.description?.getMimeType(0))
            assertTrue(sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertArrayEquals(source.readBytes(), requireNotNull(staged).readBytes())

            context.contentResolver.openFileDescriptor(sharedUri, "r").use { descriptor ->
                assertNotNull(descriptor)
                PdfRenderer(requireNotNull(descriptor)).use { renderer ->
                    assertEquals(2, renderer.pageCount)
                    renderer.openPage(0).use { firstPage ->
                        assertTrue("First page should retain portrait geometry.", firstPage.width < firstPage.height)
                        val bitmap = Bitmap.createBitmap(firstPage.width, firstPage.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            firstPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertEquals("The actual first page should render through the shared URI.", Color.RED, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    renderer.openPage(1).use { secondPage ->
                        assertTrue("The second page rotation should remain landscape when rendered.", secondPage.width > secondPage.height)
                    }
                }
            }
        } finally {
            source.delete()
            staged?.delete()
        }
    }

    @Test
    fun sharePreparedEncryptedPdfHasNoPermissionLockAndRendersItsFirstPage() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        if (Security.getProvider("BC") == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }

        val plain = File(context.cacheDir, "whatsapp_plain_source_${System.nanoTime()}.pdf")
        val marked = File(context.cacheDir, "whatsapp_marked_source_${System.nanoTime()}.pdf")
        val source = File(context.cacheDir, "whatsapp_encrypted_source_${System.nanoTime()}.pdf")
        val prepared = File(context.cacheDir, "whatsapp_prepared_${System.nanoTime()}.pdf")
        var staged: File? = null
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.setNonStrokingColor(255, 0, 0)
                    stream.addRect(0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                    stream.fill()
                }
                document.save(plain)
            }

            val engine = PdfBoxToolkitEngine(context, billingManager = null)
            // Mark once before locking the PDF. The share path must preserve this mark while
            // rebuilding the outgoing PDF structure and removing its extraction restrictions.
            val firstWatermark = engine.watermarkPdf(
                sourceUri = android.net.Uri.fromFile(plain),
                watermarkText = PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
                outputFile = marked
            )
            assertTrue("Initial watermark failed: ${firstWatermark.exceptionOrNull()}", firstWatermark.isSuccess)

            PDDocument.load(marked).use { document ->
                val permissions = AccessPermission().apply {
                    setCanExtractContent(false)
                    setCanModify(false)
                    setCanPrint(false)
                }
                val policy = StandardProtectionPolicy("owner-password", "", permissions).apply {
                    encryptionKeyLength = 128
                    setPreferAES(true)
                }
                document.protect(policy)
                document.save(source)
            }

            val preparedResult = engine.watermarkPdf(
                sourceUri = android.net.Uri.fromFile(source),
                watermarkText = PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
                outputFile = prepared,
                removeSecurityForSharing = true
            )
            assertTrue("Share preparation failed: ${preparedResult.exceptionOrNull()}", preparedResult.isSuccess)
            assertTrue(prepared.isFile && prepared.length() > 0L)

            PDDocument.load(prepared).use { output ->
                assertFalse("The outgoing copy must not retain PDF encryption.", output.isEncrypted)
                assertTrue("The outgoing copy must permit thumbnail content extraction.", output.currentAccessPermission.canExtractContent())
                assertTrue("The outgoing copy must not retain a modify restriction.", output.currentAccessPermission.canModify())
                assertEquals(1, output.numberOfPages)
                assertEquals(
                    "An existing watermark must not be duplicated during share normalization.",
                    "true",
                    output.documentInformation.getCustomMetadataValue("VibeSync-Watermarked-V2")
                )
                assertEquals(
                    "The source watermark must remain represented by a single image.",
                    1,
                    output.getPage(0).resources?.xObjectNames?.count() ?: 0
                )
                assertEquals(PDRectangle.A4.width, output.getPage(0).mediaBox.width, 0.01f)
                assertEquals(PDRectangle.A4.height, output.getPage(0).mediaBox.height, 0.01f)
            }

            val chooser = PdfShareHelper.createChooserIntent(
                context,
                listOf(prepared),
                "Share PDF",
                uriProvider = { shareContext, file ->
                    staged = file
                    UriUtils.getShareableUri(shareContext, file)
                }
            )
            @Suppress("DEPRECATION")
            val sendIntent = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent)
            @Suppress("DEPRECATION")
            val sharedUri = requireNotNull(sendIntent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri)
            assertEquals(Intent.ACTION_SEND, sendIntent.action)
            assertEquals("application/pdf", sendIntent.type)
            assertEquals("content", sharedUri.scheme)
            assertEquals(sharedUri, sendIntent.clipData?.getItemAt(0)?.uri)
            assertEquals(sharedUri, chooser.clipData?.getItemAt(0)?.uri)
            assertTrue(sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

            context.contentResolver.openFileDescriptor(sharedUri, "r").use { descriptor ->
                assertNotNull(descriptor)
                PdfRenderer(requireNotNull(descriptor)).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { firstPage ->
                        assertTrue("First page must remain portrait.", firstPage.width < firstPage.height)
                        val bitmap = Bitmap.createBitmap(firstPage.width, firstPage.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            firstPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertEquals(
                                "Share copy must render the actual first page.",
                                Color.RED,
                                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            plain.delete()
            marked.delete()
            source.delete()
            prepared.delete()
            staged?.delete()
        }
    }

    @Test
    fun sharePreparationFlattensFormsAndAnnotationsConvertsGrayImagesAndUsesPdf14() = runBlocking<Unit> {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "whatsapp_compat_source_${System.nanoTime()}.pdf")
        val prepared = File(context.cacheDir, "whatsapp_compat_prepared_${System.nanoTime()}.pdf")

        try {
            PDDocument().use { document ->
                document.version = 1.7f
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)

                val form = PDAcroForm(document)
                document.documentCatalog.acroForm = form
                val field = PDTextField(form).apply { partialName = "PreviewField" }
                form.fields.add(field)
                val fieldWidget = field.widgets.first().apply {
                    rectangle = PDRectangle(36f, 700f, 180f, 28f)
                    this.page = page
                }
                page.annotations.add(fieldWidget)
                page.annotations.add(PDAnnotationText().apply {
                    rectangle = PDRectangle(12f, 12f, 24f, 24f)
                    contents = "Preview-only note"
                })

                val nonRgbImage = PDImageXObject(
                    document,
                    ByteArrayInputStream(byteArrayOf(0x40)),
                    null,
                    1,
                    1,
                    8,
                    PDDeviceGray.INSTANCE
                )
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(nonRgbImage, 36f, 36f, 120f, 80f)
                }
                document.save(source)
            }

            val engine = PdfBoxToolkitEngine(context, billingManager = null)
            val result = engine.watermarkPdf(
                sourceUri = android.net.Uri.fromFile(source),
                watermarkText = PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
                outputFile = prepared,
                removeSecurityForSharing = true
            )
            assertTrue("Share normalization failed: ${result.exceptionOrNull()}", result.isSuccess)

            PDDocument.load(source).use { original ->
                assertNotNull("The original AcroForm must remain untouched.", original.documentCatalog.acroForm)
                assertTrue("The original annotations must remain untouched.", original.getPage(0).annotations.isNotEmpty())
                val originalResources = requireNotNull(original.getPage(0).resources)
                val originalImages = mutableListOf<PDImageXObject>()
                originalResources.xObjectNames.forEach { resourceName ->
                    (originalResources.getXObject(resourceName) as? PDImageXObject)?.let(originalImages::add)
                }
                assertTrue(originalImages.any { it.colorSpace.name == "DeviceGray" })
            }

            PDDocument.load(prepared).use { output ->
                assertEquals("Outgoing shares must use PDF 1.4.", 1.4f, output.version, 0f)
                assertEquals("The outgoing copy must not keep interactive form fields.", null, output.documentCatalog.acroForm)
                assertTrue("The outgoing copy must not keep page annotations.", output.getPage(0).annotations.isEmpty())
                val outputResources = requireNotNull(output.getPage(0).resources)
                val outputImages = mutableListOf<PDImageXObject>()
                outputResources.xObjectNames.forEach { resourceName ->
                    (outputResources.getXObject(resourceName) as? PDImageXObject)?.let(outputImages::add)
                }
                assertTrue("The normalized PDF should still contain embedded images.", outputImages.isNotEmpty())
                assertTrue(
                    "All embedded images should use standard DeviceRGB color space.",
                    outputImages.all { it.colorSpace.name == "DeviceRGB" }
                )
            }

            ParcelFileDescriptor.open(prepared, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { firstPage ->
                        val bitmap = Bitmap.createBitmap(firstPage.width, firstPage.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            firstPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val sample = bitmap.getPixel(
                                (firstPage.width * 0.16f).toInt(),
                                (firstPage.height * 0.91f).toInt()
                            )
                            assertTrue(
                                "Converted DeviceGray image must render as neutral RGB content. pixel=$sample",
                                abs(Color.red(sample) - Color.green(sample)) < 16 &&
                                    abs(Color.green(sample) - Color.blue(sample)) < 16 &&
                                    Color.red(sample) in 30..110
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            source.delete()
            prepared.delete()
        }
    }

    @Test
    fun fileProviderOpensEveryAppOwnedPdfOutputRoot() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuredDirectories = listOf(
            File(context.cacheDir, "pdf_cache"),
            File(context.filesDir, "documents"),
            File(context.filesDir, "scans"),
            File(context.filesDir, "VibeSyncScans"),
            File(context.filesDir, "shared_pdfs")
        )

        configuredDirectories.forEachIndexed { index, directory ->
            check(directory.exists() || directory.mkdirs()) {
                "Could not create provider test directory: ${directory.absolutePath}"
            }
            val pdf = File(directory, "provider_root_${index}.pdf")
            try {
                pdf.writeBytes("%PDF-1.4\n% FileProvider test\n".toByteArray(Charsets.US_ASCII))
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    pdf
                )

                assertEquals("content", uri.scheme)
                context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                    assertNotNull("Provider could not open ${pdf.absolutePath}", descriptor)
                }
            } finally {
                pdf.delete()
            }
        }
    }
}
