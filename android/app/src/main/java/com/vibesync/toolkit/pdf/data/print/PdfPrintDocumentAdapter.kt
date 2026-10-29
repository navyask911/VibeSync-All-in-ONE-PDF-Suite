package com.vibesync.toolkit.pdf.data.print

import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo

/**
 * Copies a complete PDF source without closing the caller-owned output stream.
 * This pure helper is also exercised by fast JVM regression tests.
 */
internal fun copyPdfBytes(
    source: java.io.File,
    output: java.io.OutputStream,
    isCancelled: () -> Boolean = { false }
) {
    if (!source.exists() || !source.isFile || !source.canRead() || source.length() <= 0L) {
        throw java.io.IOException("The source PDF is empty or unreadable.")
    }
    java.io.FileInputStream(source).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (isCancelled()) throw java.util.concurrent.CancellationException("PDF printing was cancelled.")
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
        }
    }
    output.flush()
}

class PdfPrintDocumentAdapter(
    private val context: android.content.Context,
    private val sourceUri: android.net.Uri,
    private val displayName: String,
    preparedSource: java.io.File? = null
) : PrintDocumentAdapter() {
    private val lock = Any()
    private val preparedSource: java.io.File? = preparedSource
    private var stagedSource: java.io.File? = preparedSource
    private var printAttributes: PrintAttributes? = null
    private var pageCount = 0
    private var destinationWriter: java.io.FileOutputStream? = null

    override fun onStart() {
        synchronized(lock) {
            activeAdapter?.takeIf { it !== this }?.cleanup()
            activeAdapter = this
            val readySource = preparedSource?.takeIf {
                it.isFile && it.canRead() && it.length() > 0L
            }
            cleanup(preserveStagedSource = readySource != null)
            stagedSource = readySource ?: stageSource()
            validateSourceFile(stagedSource!!)
        }
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: android.os.CancellationSignal,
        callback: LayoutResultCallback,
        extras: android.os.Bundle?
    ) {
        try {
            val info = prepareLayout(newAttributes, cancellationSignal)
            callback.onLayoutFinished(info, true)
        } catch (error: Exception) {
            if (cancellationSignal.isCanceled || error is java.util.concurrent.CancellationException) callback.onLayoutCancelled()
            else callback.onLayoutFailed(error.localizedMessage ?: "Unable to prepare this PDF for printing.")
        }
    }

    internal fun prepareLayout(
        newAttributes: PrintAttributes,
        cancellationSignal: android.os.CancellationSignal
    ): PrintDocumentInfo = synchronized(lock) {
        if (cancellationSignal.isCanceled) throw java.util.concurrent.CancellationException("Print layout was cancelled.")
        val source = ensureStagedSource()
        validateSourceFile(source)
        val count = android.os.ParcelFileDescriptor.open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                renderer.pageCount.also {
                    if (it <= 0) throw java.io.IOException("The source PDF contains no pages.")
                }
            }
        }
        if (cancellationSignal.isCanceled) throw java.util.concurrent.CancellationException("Print layout was cancelled.")
        printAttributes = newAttributes
        pageCount = count
        PrintDocumentInfo.Builder(normalizePdfName(displayName))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(count)
            .build()
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: android.os.ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal,
        callback: WriteResultCallback
    ) {
        try {
            val written = writeDocument(pages, destination, cancellationSignal)
            callback.onWriteFinished(written)
        } catch (error: Exception) {
            if (cancellationSignal.isCanceled || error is java.util.concurrent.CancellationException) callback.onWriteCancelled()
            else callback.onWriteFailed(error.localizedMessage ?: "Unable to print this PDF.")
        }
        // Android owns the destination descriptor. Do not close it before callback completion.
    }

    internal fun writeDocument(
        pages: Array<out PageRange>,
        destination: android.os.ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal
    ): Array<PageRange> {
        if (cancellationSignal.isCanceled) throw java.util.concurrent.CancellationException("PDF printing was cancelled.")
        val source = synchronized(lock) { ensureStagedSource() }
        validateSourceFile(source)
        val output = java.io.FileOutputStream(destination.fileDescriptor)
        synchronized(lock) {
            runCatching { destinationWriter?.close() }
            destinationWriter = output
        }
        copyPdfBytes(source, output) { cancellationSignal.isCanceled }
        // The print framework may provide a pipe-like descriptor for which fsync() is invalid.
        // Flushing is required here; the source cache file itself is fully flushed and verified.
        output.flush()
        return arrayOf(PageRange.ALL_PAGES)
    }

    override fun onFinish() {
        synchronized(lock) {
            if (activeAdapter === this) activeAdapter = null
            cleanup()
        }
        super.onFinish()
    }

    private fun ensureStagedSource(): java.io.File =
        stagedSource ?: stageSource().also { stagedSource = it }

    private fun validateSourceFile(file: java.io.File) {
        if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) {
            throw java.io.IOException("The source PDF is empty or unreadable.")
        }
    }

    private fun stageSource(): java.io.File {
        val file = java.io.File.createTempFile("print_source_", ".pdf", context.cacheDir)
        try {
            val input = if (sourceUri.scheme == "file") {
                java.io.File(sourceUri.path ?: throw java.io.IOException("The source path is missing.")).inputStream()
            } else {
                context.contentResolver.openInputStream(sourceUri)
                    ?: throw java.io.IOException("Unable to open the selected PDF.")
            }
            input.use { source ->
                java.io.FileOutputStream(file).use { sink ->
                    source.copyTo(sink)
                    sink.flush()
                    sink.fd.sync()
                }
            }
            validateSourceFile(file)
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun cleanup(preserveStagedSource: Boolean = false) {
        runCatching { destinationWriter?.close() }
        destinationWriter = null
        if (!preserveStagedSource) {
            runCatching { stagedSource?.delete() }
            stagedSource = null
        }
        pageCount = 0
        printAttributes = null
    }

    private fun normalizePdfName(name: String): String =
        if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"

    companion object {
        @Volatile
        private var activeAdapter: PdfPrintDocumentAdapter? = null
    }
}
