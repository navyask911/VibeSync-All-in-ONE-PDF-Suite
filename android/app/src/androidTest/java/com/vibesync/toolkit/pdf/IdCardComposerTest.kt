package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.data.pdf.IdCardComposer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IdCardComposerTest {
    @Test
    fun composesTwoCenteredAspectPreservingSidesOntoOneA4Page() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val front = Bitmap.createBitmap(360, 220, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220, 40, 40)) }
        val back = Bitmap.createBitmap(220, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 60, 220)) }
        val output = File(context.cacheDir, "id-card-composer-" + System.nanoTime() + ".pdf")
        try {
            IdCardComposer.compose(front, back, output).getOrThrow()
            PDDocument.load(output).use { document ->
                assertEquals(1, document.numberOfPages)
                val page = document.getPage(0)
                assertEquals(PDRectangle.A4.width, page.mediaBox.width, 0.2f)
                assertEquals(PDRectangle.A4.height, page.mediaBox.height, 0.2f)
            }
            val (top, bottom) = IdCardComposer.placements(front.width, front.height, back.width, back.height)
            val margin15mm = 15f * 72f / 25.4f
            val gap10mm = 10f * 72f / 25.4f
            val pageHeight = PDRectangle.A4.height
            assertTrue(pageHeight - (top.y + top.height) >= margin15mm - 0.6f)
            assertTrue(bottom.y >= margin15mm - 0.6f)
            assertTrue(top.y - (bottom.y + bottom.height) >= gap10mm - 0.2f)
            assertEquals(front.width.toFloat() / front.height, top.width / top.height, 0.001f)
            assertEquals(back.width.toFloat() / back.height, bottom.width / bottom.height, 0.001f)

            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue(rendered.width > 0 && rendered.height > 0)
                            // Sample inside each placed image, not the intentional blank gap
                            // between front/back (which can be transparent in PdfRenderer).
                            val frontX = ((top.x + top.width / 2f) / PDRectangle.A4.width * rendered.width)
                                .toInt().coerceIn(0, rendered.width - 1)
                            val frontY = ((PDRectangle.A4.height - (top.y + top.height / 2f)) /
                                PDRectangle.A4.height * rendered.height).toInt().coerceIn(0, rendered.height - 1)
                            val backX = ((bottom.x + bottom.width / 2f) / PDRectangle.A4.width * rendered.width)
                                .toInt().coerceIn(0, rendered.width - 1)
                            val backY = ((PDRectangle.A4.height - (bottom.y + bottom.height / 2f)) /
                                PDRectangle.A4.height * rendered.height).toInt().coerceIn(0, rendered.height - 1)
                            val frontPixel = rendered.getPixel(frontX, frontY)
                            val backPixel = rendered.getPixel(backX, backY)
                            assertTrue(
                                "Front placement did not render the red source image: " + Integer.toHexString(frontPixel),
                                Color.red(frontPixel) > Color.blue(frontPixel) + 80
                            )
                            assertTrue(
                                "Back placement did not render the blue source image: " + Integer.toHexString(backPixel),
                                Color.blue(backPixel) > Color.red(backPixel) + 80
                            )
                        } finally {
                            rendered.recycle()
                        }
                    }
                }
            }
        } finally {
            front.recycle()
            back.recycle()
            output.delete()
        }
    }
}
