package com.vibesync.toolkit.pdf.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The recognizer consumes on-device bitmaps; it does not upload source documents or images. */
class MlKitOcrEngine(
    private val context: Context,
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
) : Closeable {
    suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        require(!bitmap.isRecycled) { "The source image is no longer available." }
        ensureRecognizerModuleReady()
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

    /**
     * The Play Services recognizer model is unbundled. An install task acknowledges the request;
     * the model must be reported available before the first process() call.
     */
    private suspend fun ensureRecognizerModuleReady() {
        val moduleInstallClient = ModuleInstall.getClient(context)
        if (moduleInstallClient.areModulesAvailable(recognizer).awaitResult().areModulesAvailable()) return

        moduleInstallClient.installModules(
            ModuleInstallRequest.newBuilder().addApi(recognizer).build()
        ).awaitResult()

        withTimeout(MODEL_INSTALL_TIMEOUT_MILLIS) {
            while (!moduleInstallClient.areModulesAvailable(recognizer).awaitResult().areModulesAvailable()) {
                delay(MODEL_INSTALL_POLL_INTERVAL_MILLIS)
            }
        }
    }

    private companion object {
        const val MODEL_INSTALL_TIMEOUT_MILLIS = 120_000L
        const val MODEL_INSTALL_POLL_INTERVAL_MILLIS = 500L
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
