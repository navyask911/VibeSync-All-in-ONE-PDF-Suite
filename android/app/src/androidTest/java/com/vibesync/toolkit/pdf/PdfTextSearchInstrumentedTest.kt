package com.vibesync.toolkit.pdf

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
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
import com.vibesync.toolkit.pdf.data.pdf.PdfTextExtractor
import com.vibesync.toolkit.pdf.data.pdf.PdfCharacterBounds
import com.vibesync.toolkit.pdf.data.pdf.PdfSearchHighlight
import com.vibesync.toolkit.pdf.data.pdf.PdfUnicodeSearch
import com.vibesync.toolkit.pdf.presentation.viewer.PdfSearchHighlightCanvas
import com.vibesync.toolkit.pdf.presentation.viewer.SearchHighlightOverlay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfTextSearchInstrumentedTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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

    @Test
    fun testPdfSearchIndicAndEnglishMatchOverlay() {
        val pageText = "English search and Kannada: ಕನ್ನಡ"
        val englishStart = pageText.indexOf("search")
        val kannadaStart = pageText.indexOf("ಕನ್ನಡ")
        val characterBounds = listOf(
            PdfCharacterBounds(
                startOffset = englishStart,
                endOffset = englishStart + "search".length,
                highlight = PdfSearchHighlight(1_000, 1_000, 4_000, 2_500)
            ),
            PdfCharacterBounds(
                startOffset = kannadaStart,
                endOffset = kannadaStart + "ಕನ್ನಡ".length,
                highlight = PdfSearchHighlight(5_000, 5_000, 9_000, 7_000)
            )
        )

        val englishMatches = PdfUnicodeSearch.findMatches(
            pageIndex = 0,
            text = pageText,
            query = "SEARCH",
            characterBounds = characterBounds
        )
        val kannadaMatches = PdfUnicodeSearch.findMatches(
            pageIndex = 0,
            text = pageText,
            query = "ಕನ್ನಡ",
            characterBounds = characterBounds
        )

        assertEquals(1, englishMatches.size)
        assertEquals(1, kannadaMatches.size)
        assertFalse(
            "English result must have a non-empty positioned overlay.",
            englishMatches.single().highlights.isEmpty()
        )
        assertFalse(
            "Kannada result must have a non-empty positioned overlay.",
            kannadaMatches.single().highlights.isEmpty()
        )

        val overlays = buildList {
            englishMatches.single().highlights.forEach {
                add(SearchHighlightOverlay(bounds = it, isCurrent = true))
            }
            kannadaMatches.single().highlights.forEach {
                add(SearchHighlightOverlay(bounds = it, isCurrent = false))
            }
        }
        composeTestRule.setContent {
            Box(
                modifier = Modifier
                    .size(320.dp)
                    .background(Color.Black)
            ) {
                PdfSearchHighlightCanvas(
                    highlightOverlays = overlays,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val overlayNode = composeTestRule.onNodeWithTag("viewer_search_highlights")
        overlayNode.assertIsDisplayed()
        val pixels = overlayNode.captureToImage().toPixelMap()
        fun pixelFor(bounds: PdfSearchHighlight, xFraction: Float, yFraction: Float): Color {
            val x = (
                (bounds.left + (bounds.right - bounds.left) * xFraction) /
                    PdfSearchHighlight.COORDINATE_SCALE.toFloat() * pixels.width
                ).toInt().coerceIn(0, pixels.width - 1)
            val y = (
                (bounds.top + (bounds.bottom - bounds.top) * yFraction) /
                    PdfSearchHighlight.COORDINATE_SCALE.toFloat() * pixels.height
                ).toInt().coerceIn(0, pixels.height - 1)
            return pixels[x, y]
        }

        listOf(
            englishMatches.single().highlights.single(),
            kannadaMatches.single().highlights.single()
        ).forEach { bounds ->
            val pixel = pixelFor(bounds, 0.5f, 0.5f)
            assertTrue(
                "Expected a visible yellow match overlay: $pixel",
                pixel.red > 0.25f && pixel.green > 0.25f
            )
        }

        val activeBounds = englishMatches.single().highlights.single()
        val borderPixel = pixelFor(activeBounds, 0.01f, 0.5f)
        assertTrue(
            "The active search result must have an orange outline: $borderPixel",
            borderPixel.red > borderPixel.green && borderPixel.red > borderPixel.blue
        )
    }

}
