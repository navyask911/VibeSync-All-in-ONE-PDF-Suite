package com.vibesync.toolkit.pdf.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The recognizer consumes on-device bitmaps; it does not upload source documents or images. */
class MlKitOcrEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
) : Closeable {
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
