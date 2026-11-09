package com.vibesync.toolkit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.data.model.PageAnnotations
import com.vibesync.toolkit.pdf.data.model.Point2D
import com.vibesync.toolkit.pdf.data.model.VectorStroke
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfEditorFlattenInstrumentedTest {
    @Test
    fun flatteningKeepsVectorCanvasVisibleInExportedPdf() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "flatten-source-${System.nanoTime()}.pdf")
        val output = File(context.cacheDir, "flatten-output-${System.nanoTime()}.pdf")

        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(source)
            }

            val stroke = VectorStroke(
                points = listOf(
                    Point2D(0.15f, 0.40f),
                    Point2D(0.50f, 0.40f),
                    Point2D(0.85f, 0.40f)
                ),
                colorArgb = Color.BLACK,
                strokeWidth = 0.025f
            )
            val result = PdfBoxToolkitEngine(context).flattenAnnotations(
                sourceUri = Uri.fromFile(source),
                pageAnnotationsMap = mapOf(0 to PageAnnotations(pageIndex = 0, strokes = listOf(stroke))),
                screenWidthPx = 595f,
                outputFile = output
            )

            assertTrue("PDFBox flattening should succeed: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue("Flattened PDF must be written with non-empty content.", output.isFile && output.length() > 0L)

            val descriptor = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(descriptor)
            try {
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val annotationPixel = bitmap.getPixel(page.width / 2, (page.height * 0.40f).toInt())
                        assertTrue(
                            "Flattened vector stroke must be visible in the output PDF, pixel=$annotationPixel",
                            Color.red(annotationPixel) < 100 &&
                                Color.green(annotationPixel) < 100 &&
                                Color.blue(annotationPixel) < 100
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                renderer.close()
            }
        } finally {
            source.delete()
            output.delete()
        }
    }
}
