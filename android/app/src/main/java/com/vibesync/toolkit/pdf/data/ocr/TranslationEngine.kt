package com.vibesync.toolkit.pdf.data.ocr

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Injectable interface keeps routing and user-consent behavior testable without downloading models. */
interface TranslationProcessor {
    suspend fun isModelReady(sourceLanguage: String, targetLanguage: String): Boolean
    suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String
}

/** ML Kit performs translation on the device. Model downloads occur only after the UI obtains consent. */
class TranslationEngine(@Suppress("UNUSED_PARAMETER") context: Context) : TranslationProcessor {
    override suspend fun isModelReady(sourceLanguage: String, targetLanguage: String): Boolean =
        withContext(Dispatchers.IO) {
            val manager = RemoteModelManager.getInstance()
            val sourceModel = TranslateRemoteModel.Builder(sourceLanguage).build()
            val targetModel = TranslateRemoteModel.Builder(targetLanguage).build()
            manager.isModelDownloaded(sourceModel).awaitResult() &&
                manager.isModelDownloaded(targetModel).awaitResult()
        }

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String = withContext(Dispatchers.Default) {
        require(text.isNotBlank()) { "Recognized text is empty." }
        require(sourceLanguage in TranslateLanguage.getAllLanguages()) { "Choose a supported source language." }
        require(targetLanguage in TranslateLanguage.getAllLanguages()) { "Choose a supported target language." }
        require(sourceLanguage != targetLanguage) { "Choose different source and target languages." }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
        val translator = Translation.getClient(options)
        try {
            // This is the sole model installation call; callers show the disclosure before invoking it.
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            translator.translate(text).awaitResult()
        } finally {
            translator.close()
        }
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
        if (continuation.isActive) continuation.cancel()
    }
}
