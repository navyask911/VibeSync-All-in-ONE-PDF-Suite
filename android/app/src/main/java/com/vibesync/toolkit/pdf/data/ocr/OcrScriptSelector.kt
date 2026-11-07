package com.vibesync.toolkit.pdf.data.ocr

import java.util.Locale

/** Character-script model families supported by bundled ML Kit Text Recognition v2. */
enum class OcrScript {
    LATIN,
    CHINESE,
    DEVANAGARI,
    JAPANESE,
    KOREAN
}

/**
 * Maps an Android/app language tag to the script recognizer ML Kit actually provides.
 *
 * ML Kit ships script models, not a model for every app language. The available bundled families
 * are Latin, Chinese, Devanagari, Japanese and Korean. Unsupported scripts (including Kannada,
 * Tamil, Telugu, Gujarati, Malayalam, Bengali, Punjabi, Sinhala, Arabic, Hebrew and Thai) fall
 * back to Latin; the fallback is graceful but is not equivalent to native script OCR.
 */
object OcrScriptSelector {
    fun fromLanguageTag(languageTag: String?): OcrScript {
        val language = Locale.forLanguageTag(languageTag.orEmpty()).language.lowercase(Locale.ROOT)
        return when (language) {
            "zh" -> OcrScript.CHINESE
            "hi", "mr", "ne" -> OcrScript.DEVANAGARI
            "ja" -> OcrScript.JAPANESE
            "ko" -> OcrScript.KOREAN
            else -> OcrScript.LATIN
        }
    }
}
