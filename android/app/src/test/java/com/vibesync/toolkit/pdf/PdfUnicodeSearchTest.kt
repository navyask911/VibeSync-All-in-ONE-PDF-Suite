package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.data.ocr.OcrScript
import com.vibesync.toolkit.pdf.data.ocr.OcrScriptSelector
import com.vibesync.toolkit.pdf.data.pdf.PdfUnicodeSearch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfUnicodeSearchTest {
    @Test
    fun findsKnownWordInDefaultEnglishLanguageCaseInsensitively() {
        val matches = PdfUnicodeSearch.findMatches(
            pageIndex = 0,
            text = "Welcome to VibeSync PDF Suite. Search remains on your device.",
            query = "vibesync"
        )

        assertEquals(1, matches.size)
        assertTrue(matches.single().snippet.contains("VibeSync"))
        assertEquals(0, matches.single().pageIndex)
    }

    @Test
    fun findsWordsInUnicodeScriptsWithoutAsciiConversion() {
        val pageText = "ಕನ್ನಡ हिन्दी العربية español 日本語 한국어"

        assertEquals(1, PdfUnicodeSearch.findMatches(0, pageText, "ಕನ್ನಡ").size)
        assertEquals(1, PdfUnicodeSearch.findMatches(0, pageText, "हिन्दी").size)
        assertEquals(1, PdfUnicodeSearch.findMatches(0, pageText, "العربية").size)
        assertEquals(1, PdfUnicodeSearch.findMatches(0, pageText, "español").size)
        assertEquals(1, PdfUnicodeSearch.findMatches(0, pageText, "日本語").size)
        assertEquals(1, PdfUnicodeSearch.findMatches(0, pageText, "한국어").size)
    }

    @Test
    fun findsCanonicallyEquivalentUnicodeText() {
        val decomposed = "Cafe\u0301 documents"
        val composedQuery = "café"

        assertEquals(1, PdfUnicodeSearch.findMatches(0, decomposed, composedQuery).size)
    }

    @Test
    fun choosesMlKitRecognizerFromSelectedAppLanguage() {
        assertEquals(OcrScript.LATIN, OcrScriptSelector.fromLanguageTag("en"))
        assertEquals(OcrScript.LATIN, OcrScriptSelector.fromLanguageTag("es-MX"))
        assertEquals(OcrScript.DEVANAGARI, OcrScriptSelector.fromLanguageTag("hi-IN"))
        assertEquals(OcrScript.DEVANAGARI, OcrScriptSelector.fromLanguageTag("mr"))
        assertEquals(OcrScript.CHINESE, OcrScriptSelector.fromLanguageTag("zh-TW"))
        assertEquals(OcrScript.JAPANESE, OcrScriptSelector.fromLanguageTag("ja-JP"))
        assertEquals(OcrScript.KOREAN, OcrScriptSelector.fromLanguageTag("ko-KR"))
        assertEquals(OcrScript.LATIN, OcrScriptSelector.fromLanguageTag("kn-IN"))
    }
}
