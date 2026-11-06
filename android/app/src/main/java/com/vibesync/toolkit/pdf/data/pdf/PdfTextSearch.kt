package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.vibesync.toolkit.pdf.core.util.UriUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Unicode text extracted from one zero-based PDF page. */
data class PdfPageText(
    val pageIndex: Int,
    val text: String
)

/**
 * Extracts page-local text through PDFBox. Kotlin/JVM strings are Unicode strings; no lossy
 * byte-to-ASCII conversion is performed, so the PDF's ToUnicode mappings are retained.
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
                    val stripper = PDFTextStripper().apply {
                        setStartPage(pageIndex + 1)
                        setEndPage(pageIndex + 1)
                        setSortByPosition(true)
                    }
                    pages += PdfPageText(pageIndex, stripper.getText(document))
                }
                pages
            }
        } finally {
            temporaryFile.delete()
        }
    }
}

/** Pixel-space bounds returned by ML Kit for an OCR-recognized block on the rendered page. */
data class PdfSearchHighlight(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** A single result in the PDF's page order. */
data class PdfSearchMatch(
    val pageIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val snippet: String,
    val highlight: PdfSearchHighlight? = null
)

/**
 * Performs case-insensitive Unicode matching after canonical NFC normalization. Case folding is
 * applied by String.indexOf(ignoreCase = true), which is locale-independent and leaves scripts
 * without case distinctions (for example, Kannada, Hindi and Arabic) unchanged.
 */
object PdfUnicodeSearch {
    private const val SNIPPET_RADIUS = 36

    fun findMatches(
        pageIndex: Int,
        text: String,
        query: String,
        highlight: PdfSearchHighlight? = null
    ): List<PdfSearchMatch> {
        if (query.isBlank() || text.isEmpty()) return emptyList()

        val normalizedText = Normalizer.normalize(text, Normalizer.Form.NFC)
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

            matches += PdfSearchMatch(
                pageIndex = pageIndex,
                startOffset = offset,
                endOffset = endOffset,
                snippet = snippet,
                highlight = highlight
            )
            searchFrom = endOffset.coerceAtLeast(offset + 1)
        }
        return matches
    }
}
