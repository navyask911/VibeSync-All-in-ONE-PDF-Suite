package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.vibesync.toolkit.pdf.core.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** PDF glyph offsets paired with a page-normalized visible rectangle. */
data class PdfCharacterBounds(
    val startOffset: Int,
    val endOffset: Int,
    val highlight: PdfSearchHighlight
)

/** Unicode text and the positioned glyphs that make up one zero-based PDF page. */
data class PdfPageText(
    val pageIndex: Int,
    val text: String,
    val characterBounds: List<PdfCharacterBounds> = emptyList()
)

/**
 * Extracts page-local text and coordinates through PDFBox. TextPosition locations are converted
 * to normalized page coordinates so overlays remain aligned with PdfRenderer bitmaps at any
 * adaptive render scale.
 */
class PdfTextExtractor(context: Context) {
    private val appContext = context.applicationContext

    suspend fun extractPages(sourceUri: Uri): List<PdfPageText> = withContext(Dispatchers.IO) {
        val temporaryFile = UriUtils.copyUriToCacheFile(appContext, sourceUri, "viewer_search")
        try {
            PDDocument.load(temporaryFile).use { document ->
                val pages = ArrayList<PdfPageText>(document.numberOfPages)
                for (pageIndex in 0 until document.numberOfPages) {
                    currentCoroutineContext().ensureActive()
                    val stripper = PositionedPdfTextStripper().apply {
                        setStartPage(pageIndex + 1)
                        setEndPage(pageIndex + 1)
                        setSortByPosition(true)
                    }
                    val text = stripper.getText(document)
                    pages += PdfPageText(
                        pageIndex = pageIndex,
                        text = text,
                        characterBounds = stripper.characterBounds.toList()
                    )
                }
                pages
            }
        } finally {
            temporaryFile.delete()
        }
    }
}

/**
 * Captures the actual ordered TextPosition list used by PDFTextStripper.writeString. The emitted
 * offset counter mirrors the stripper's word/line separators, so each glyph is tied to the exact
 * Unicode substring searched by PdfUnicodeSearch.
 */
private class PositionedPdfTextStripper : PDFTextStripper() {
    val characterBounds = mutableListOf<PdfCharacterBounds>()
    private var emittedTextOffset = 0

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        var localSearchOffset = 0
        textPositions.forEach { position ->
            val unicode = position.unicode ?: return@forEach
            if (unicode.isEmpty()) return@forEach
            val localOffset = text.indexOf(unicode, localSearchOffset)
            if (localOffset < 0) return@forEach

            val pageWidth = (if (position.rotation % 180 == 0) {
                position.pageWidth
            } else {
                position.pageHeight
            }).coerceAtLeast(1f)
            val pageHeight = (if (position.rotation % 180 == 0) {
                position.pageHeight
            } else {
                position.pageWidth
            }).coerceAtLeast(1f)

            // TextPosition x/y are page-rotation-adjusted. Y is measured to the glyph baseline
            // from the top edge, so subtract glyph height to get the visible top coordinate.
            val left = position.x
            val top = position.y - position.height
            val right = left + position.width
            val bottom = top + position.height
            val bounds = PdfSearchHighlight(
                left = normalizedCoordinate(left, pageWidth),
                top = normalizedCoordinate(top, pageHeight),
                right = normalizedCoordinate(right, pageWidth),
                bottom = normalizedCoordinate(bottom, pageHeight)
            )
            characterBounds += PdfCharacterBounds(
                startOffset = emittedTextOffset + localOffset,
                endOffset = emittedTextOffset + localOffset + unicode.length,
                highlight = bounds
            )
            localSearchOffset = localOffset + unicode.length
        }
        super.writeString(text, textPositions)
        emittedTextOffset += text.length
    }

    override fun writeWordSeparator() {
        super.writeWordSeparator()
        emittedTextOffset += 1
    }

    override fun writeLineSeparator() {
        super.writeLineSeparator()
        emittedTextOffset += System.lineSeparator().length
    }

    private fun normalizedCoordinate(value: Float, extent: Float): Int =
        (value / extent * PdfSearchHighlight.COORDINATE_SCALE)
            .toInt()
            .coerceIn(0, PdfSearchHighlight.COORDINATE_SCALE)
}

/** Page-normalized (0..10,000) bounds; independent of the render bitmap's pixel dimensions. */
data class PdfSearchHighlight(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    companion object {
        const val COORDINATE_SCALE = 10_000
    }
}

/** A single result in the PDF's page order. */
data class PdfSearchMatch(
    val pageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val snippet: String,
    /** Compatibility single rectangle, also used by OCR block-based matches. */
    val highlight: PdfSearchHighlight? = null,
    /** One rectangle per line for native PDF text matches. */
    val highlights: List<PdfSearchHighlight> = emptyList()
)

/**
 * Performs case-insensitive Unicode matching after canonical NFC normalization while retaining
 * a mapping from normalized match offsets back to the original PDFTextStripper string offsets.
 */
object PdfUnicodeSearch {
    private const val SNIPPET_RADIUS = 36

    fun findMatches(
        pageIndex: Int,
        text: String,
        query: String,
        highlight: PdfSearchHighlight? = null,
        characterBounds: List<PdfCharacterBounds> = emptyList()
    ): List<PdfSearchMatch> {
        if (query.isBlank() || text.isEmpty()) return emptyList()

        val (normalizedText, normalizedToOriginal) = normalizeWithOffsetMap(text)
        val normalizedQuery = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)
        if (normalizedQuery.isEmpty()) return emptyList()

        val matches = mutableListOf<PdfSearchMatch>()
        var searchFrom = 0
        while (searchFrom <= normalizedText.length - normalizedQuery.length) {
            val offset = normalizedText.indexOf(normalizedQuery, searchFrom, ignoreCase = true)
            if (offset < 0) break

            val endOffset = offset + normalizedQuery.length
            val snippetStart = (offset - SNIPPET_RADIUS).coerceAtLeast(0)
            val snippetEnd = (endOffset + SNIPPET_RADIUS).coerceAtMost(normalizedText.length)
            val snippet = normalizedText.substring(snippetStart, snippetEnd)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
            val nativeHighlights = if (characterBounds.isEmpty()) {
                emptyList()
            } else {
                val originalOffsets = (offset until endOffset)
                    .mapNotNull { normalizedToOriginal.getOrNull(it) }
                    .toHashSet()
                val matchingGlyphs = characterBounds.filter { glyph ->
                    originalOffsets.any { it >= glyph.startOffset && it < glyph.endOffset }
                }
                mergeGlyphBounds(matchingGlyphs.map { it.highlight })
            }

            matches += PdfSearchMatch(
                pageIndex = pageIndex,
                startOffset = offset,
                endOffset = endOffset,
                snippet = snippet,
                highlight = highlight ?: nativeHighlights.firstOrNull(),
                highlights = nativeHighlights
            )
            searchFrom = endOffset.coerceAtLeast(offset + 1)
        }
        return matches
    }

    private fun normalizeWithOffsetMap(value: String): Pair<String, List<Int>> {
        val normalized = StringBuilder()
        val offsets = mutableListOf<Int>()
        var cursor = 0
        while (cursor < value.length) {
            val start = cursor
            cursor += Character.charCount(value.codePointAt(cursor))
            while (cursor < value.length) {
                val nextCodePoint = value.codePointAt(cursor)
                when (Character.getType(nextCodePoint)) {
                    Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(),
                    Character.ENCLOSING_MARK.toInt() -> cursor += Character.charCount(nextCodePoint)
                    else -> break
                }
            }
            val normalizedChunk = Normalizer.normalize(value.substring(start, cursor), Normalizer.Form.NFC)
            normalized.append(normalizedChunk)
            repeat(normalizedChunk.length) { offsets += start }
        }
        return normalized.toString() to offsets
    }

    /** Join adjacent glyph rectangles on the same text line into a single readable highlight. */
    private fun mergeGlyphBounds(rectangles: List<PdfSearchHighlight>): List<PdfSearchHighlight> {
        if (rectangles.isEmpty()) return emptyList()
        val sorted = rectangles.sortedWith(compareBy<PdfSearchHighlight> { it.top }.thenBy { it.left })
        val lines = mutableListOf<MutableList<PdfSearchHighlight>>()
        sorted.forEach { rectangle ->
            val line = lines.lastOrNull { existing ->
                val top = existing.minOf { it.top }
                val bottom = existing.maxOf { it.bottom }
                rectangle.top <= bottom && rectangle.bottom >= top
            }
            if (line == null) lines += mutableListOf(rectangle) else line += rectangle
        }
        return lines.map { line ->
            PdfSearchHighlight(
                left = line.minOf { it.left },
                top = line.minOf { it.top },
                right = line.maxOf { it.right },
                bottom = line.maxOf { it.bottom }
            )
        }
    }
}
