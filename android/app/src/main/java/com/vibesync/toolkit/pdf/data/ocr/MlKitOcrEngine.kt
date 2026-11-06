package com.vibesync.toolkit.pdf.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs bundled ML Kit text recognition locally on the supplied page bitmap.
 *
 * The selected recognizer is based on the app's effective resource locale, which includes an
 * AppCompat per-app language selection. ML Kit supports Latin, Chinese, Devanagari, Japanese and
 * Korean scripts. Other scripts use the Latin fallback; their digital PDF text remains searchable,
 * but scanned content in unsupported scripts may not be recognized reliably.
 */
class MlKitOcrEngine(
    context: Context,
    recognizerOverride: TextRecognizer? = null,
    languageTag: String? = null
) : Closeable {
    val selectedScript: OcrScript = OcrScriptSelector.fromLanguageTag(
        languageTag
            ?: AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            ?: context.resources.configuration.locales
                .takeIf { !it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()
    )

    private val recognizer: TextRecognizer = recognizerOverride ?: when (selectedScript) {
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        require(!bitmap.isRecycled) { "The source image is no longer available." }
        val recognized = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
        OcrResult(
            text = recognized.text,
            blocks = recognized.textBlocks.map { block ->
                OcrBlock(
                    boundingBox = block.boundingBox?.let { Rect(it) },
                    text = block.text,
                    language = block.recognizedLanguage.takeIf { it.isNotBlank() }
                )
            }
        )
    }

    override fun close() {
        recognizer.close()
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel(CancellationException("Text recognition was cancelled."))
    }
}
