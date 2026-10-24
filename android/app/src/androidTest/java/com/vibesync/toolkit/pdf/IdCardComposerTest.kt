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
            assertEquals(margin15mm, pageHeight - (top.y + top.height), 0.6f)
            assertEquals(margin15mm, bottom.y, 0.6f)
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
                            assertTrue(Color.alpha(rendered.getPixel(rendered.width / 2, rendered.height / 2)) > 0)
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
