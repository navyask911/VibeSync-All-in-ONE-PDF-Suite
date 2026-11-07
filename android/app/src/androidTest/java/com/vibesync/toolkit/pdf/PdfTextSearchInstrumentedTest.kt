package com.vibesync.toolkit.pdf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.vibesync.toolkit.pdf.data.pdf.PdfTextExtractor
import com.vibesync.toolkit.pdf.data.pdf.PdfUnicodeSearch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfTextSearchInstrumentedTest {
    @Test
    fun nativePdfTextSearchProducesPageNormalizedHighlightBoxes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "positioned-search-${System.nanoTime()}.pdf")
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA_BOLD, 20f)
                    stream.newLineAtOffset(80f, 700f)
                    stream.showText("Find the needle")
                    stream.endText()
                }
                document.save(source)
            }

            val page = PdfTextExtractor(context).extractPages(Uri.fromFile(source)).single()
            assertTrue(page.text.contains("needle"))
            assertFalse("PDFTextStripper must return positioned glyphs.", page.characterBounds.isEmpty())

            val matches = PdfUnicodeSearch.findMatches(
                pageIndex = page.pageIndex,
                text = page.text,
                query = "needle",
                characterBounds = page.characterBounds
            )
            assertEquals(1, matches.size)
            assertFalse("A found text match must have visible overlay bounds.", matches.single().highlights.isEmpty())
            val bounds = matches.single().highlights.single()
            assertTrue(bounds.left in 0 until 10_000)
            assertTrue(bounds.top in 0 until 10_000)
            assertTrue(bounds.right > bounds.left)
            assertTrue(bounds.bottom > bounds.top)
        } finally {
            source.delete()
        }
    }
}
