package com.vibesync.toolkit.pdf

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.vibesync.toolkit.pdf.data.pdf.PdfCharacterBounds
import com.vibesync.toolkit.pdf.data.pdf.PdfSearchHighlight
import com.vibesync.toolkit.pdf.data.pdf.PdfTextExtractor
import com.vibesync.toolkit.pdf.data.pdf.PdfUnicodeSearch
import com.vibesync.toolkit.pdf.presentation.viewer.PdfSearchHighlightLayer
import com.vibesync.toolkit.pdf.presentation.viewer.SearchHighlightOverlay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfTextSearchInstrumentedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testPdfSearchIndicAndEnglishMatchOverlay() {
        val kannada = "ಕೋ"
        val extractedText = "English needle $kannada text"
        val englishStart = extractedText.indexOf("needle")
        val indicStart = extractedText.indexOf(kannada)
        val glyphBounds = listOf(
            PdfCharacterBounds(
                startOffset = englishStart,
                endOffset = englishStart + "needle".length,
                highlight = PdfSearchHighlight(900, 1200, 4100, 1850)
            ),
            PdfCharacterBounds(
                startOffset = indicStart,
                endOffset = indicStart + kannada.length,
                highlight = PdfSearchHighlight(4700, 1200, 8200, 1850)
            )
        )

        val englishMatches = PdfUnicodeSearch.findMatches(
            pageIndex = 0,
            text = extractedText,
            query = "needle",
            characterBounds = glyphBounds
        )
        val decomposedIndicQuery = java.text.Normalizer.normalize(
            kannada,
            java.text.Normalizer.Form.NFD
        )
        val indicMatches = PdfUnicodeSearch.findMatches(
            pageIndex = 0,
            text = extractedText,
            query = decomposedIndicQuery,
            characterBounds = glyphBounds
        )

        assertEquals(1, englishMatches.size)
        assertEquals(1, indicMatches.size)
        assertFalse(englishMatches.single().highlights.isEmpty())
        assertFalse(indicMatches.single().highlights.isEmpty())
        val canvasSize = androidx.compose.ui.geometry.Size(300f, 400f)
        val englishRect = englishMatches.single().highlights.single().toCanvasRect(canvasSize)
        val indicRect = indicMatches.single().highlights.single().toCanvasRect(canvasSize)
        assertTrue(englishRect.width > 0f && englishRect.height > 0f)
        assertTrue(indicRect.width > 0f && indicRect.height > 0f)

        val overlays = listOf(
            SearchHighlightOverlay(englishMatches.single().highlights.single(), isCurrent = false),
            SearchHighlightOverlay(indicMatches.single().highlights.single(), isCurrent = true)
        )
        composeTestRule.setContent {
            Box(Modifier.size(300.dp, 400.dp)) {
                PdfSearchHighlightLayer(
                    highlightOverlays = overlays,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        composeTestRule.onNodeWithTag("viewer_search_highlights").assertIsDisplayed()
        val highlightNode = composeTestRule.onNodeWithTag("viewer_search_highlights")
        val renderedBounds = highlightNode.fetchSemanticsNode().boundsInRoot
        assertTrue(renderedBounds.width > 0f && renderedBounds.height > 0f)

        // Ensure the active-result outline is actually painted, not only represented by a Canvas node.
        val pixels = highlightNode.captureToImage().toPixelMap()
        var orangePixels = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                if (pixel.alpha > 0.8f && pixel.red > 0.8f &&
                    pixel.green in 0.35f..0.75f && pixel.blue < 0.2f
                ) {
                    orangePixels += 1
                }
            }
        }
        assertTrue("The active PDF search match must render an orange outline.", orangePixels > 0)
    }

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
