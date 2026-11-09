package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.vibesync.toolkit.pdf.core.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

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

            // PDFBox TextPosition x/y are already converted from PDF's bottom-left origin to the
            // rotated page's top-left origin (points). y marks the glyph baseline; subtract height.
            val left = position.x
            val bottom = position.y
            val top = (bottom - position.height).coerceAtLeast(0f)
            val right = left + position.width
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

/** Top-left page-normalized (0..10,000) rectangle, independent of bitmap resolution. */
data class PdfSearchHighlight(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun toCanvasRect(canvasSize: Size): Rect {
        val width = canvasSize.width.coerceAtLeast(0f)
        val height = canvasSize.height.coerceAtLeast(0f)
        val leftNorm = left.coerceIn(0, COORDINATE_SCALE)
        val topNorm = top.coerceIn(0, COORDINATE_SCALE)
        val rightNorm = right.coerceIn(leftNorm, COORDINATE_SCALE)
        val bottomNorm = bottom.coerceIn(topNorm, COORDINATE_SCALE)
        val factor = COORDINATE_SCALE.toFloat()
        return Rect(
            left = leftNorm / factor * width,
            top = topNorm / factor * height,
            right = rightNorm / factor * width,
            bottom = bottomNorm / factor * height
        )
    }

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
                val originalRanges = (offset until endOffset)
                    .mapNotNull { normalizedToOriginal.getOrNull(it) }
                    .distinct()
                val matchingGlyphs = characterBounds.filter { glyph ->
                    originalRanges.any { range ->
                        range.start < glyph.endOffset && range.endExclusive > glyph.startOffset
                    }
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

    private data class OriginalTextRange(
        val start: Int,
        val endExclusive: Int
    )

    private fun normalizeWithOffsetMap(value: String): Pair<String, List<OriginalTextRange>> {
        // Normalize the complete extracted page so canonically equivalent Indic text compares
        // consistently regardless of whether PDFTextStripper emitted composed or decomposed marks.
        val normalizedText = Normalizer.normalize(value, Normalizer.Form.NFC)
        if (value.isEmpty()) return normalizedText to emptyList()

        // Segment source text by grapheme rather than code point. Every normalized UTF-16 unit
        // keeps the full original grapheme range, allowing one normalized match to highlight all
        // source glyphs (base consonant and dependent vowel/combining marks included).
        val graphemes = BreakIterator.getCharacterInstance(Locale.ROOT).apply {
            setText(value)
        }
        val normalizedChunks = StringBuilder()
        val ranges = ArrayList<OriginalTextRange>(normalizedText.length)
        var start = graphemes.first()
        var end = graphemes.next()
        while (end != BreakIterator.DONE) {
            val normalizedChunk = Normalizer.normalize(
                value.substring(start, end),
                Normalizer.Form.NFC
            )
            normalizedChunks.append(normalizedChunk)
            repeat(normalizedChunk.length) {
                ranges += OriginalTextRange(start = start, endExclusive = end)
            }
            start = end
            end = graphemes.next()
        }

        // Some platform BreakIterator versions can split a canonical combining sequence
        // differently. Fall back to mark-aware source spans while preserving exact map length.
        if (ranges.size != normalizedText.length || normalizedChunks.toString() != normalizedText) {
            ranges.clear()
            var originalCursor = 0
            var normalizedCursor = 0
            while (originalCursor < value.length && normalizedCursor < normalizedText.length) {
                val originalStart = originalCursor
                val firstCodePoint = value.codePointAt(originalCursor)
                originalCursor += Character.charCount(firstCodePoint)
                while (originalCursor < value.length) {
                    val codePoint = value.codePointAt(originalCursor)
                    val type = Character.getType(codePoint)
                    if (type == Character.NON_SPACING_MARK.toInt() ||
                        type == Character.COMBINING_SPACING_MARK.toInt() ||
                        type == Character.ENCLOSING_MARK.toInt()
                    ) {
                        originalCursor += Character.charCount(codePoint)
                    } else {
                        break
                    }
                }
                val chunk = Normalizer.normalize(
                    value.substring(originalStart, originalCursor),
                    Normalizer.Form.NFC
                )
                repeat(chunk.length) {
                    if (normalizedCursor < normalizedText.length) {
                        ranges += OriginalTextRange(originalStart, originalCursor)
                    }
                    normalizedCursor += 1
                }
            }
            while (ranges.size < normalizedText.length) {
                ranges += OriginalTextRange(value.lastIndex, value.length)
            }
            while (ranges.size > normalizedText.length) {
                ranges.removeAt(ranges.lastIndex)
            }
        }

        return normalizedText to ranges
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
