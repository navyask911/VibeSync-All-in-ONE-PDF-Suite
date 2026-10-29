package com.vibesync.toolkit.pdf.data.print

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException

/**
 * Copies a complete PDF source without closing the caller-owned output stream.
 * This pure helper is also exercised by fast JVM regression tests.
 */
internal fun copyPdfBytes(
    source: File,
    output: OutputStream,
    isCancelled: () -> Boolean = { false }
) {
    if (!source.exists() || !source.isFile || !source.canRead() || source.length() <= 0L) {
        throw IOException("The source PDF is empty or unreadable.")
    }
    FileInputStream(source).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (isCancelled()) throw CancellationException("PDF printing was cancelled.")
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
    preparedSource: File? = null
) : PrintDocumentAdapter() {
    private val lock = Any()
    private val preparedSource: File? = preparedSource
    private var stagedSource: File? = preparedSource
    private var printAttributes: PrintAttributes? = null
    private var pageCount = 0
    private var destinationWriter: FileOutputStream? = null

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
            if (cancellationSignal.isCanceled || error is CancellationException) callback.onLayoutCancelled()
            else callback.onLayoutFailed(error.localizedMessage ?: "Unable to prepare this PDF for printing.")
        }
    }

    internal fun prepareLayout(
        newAttributes: PrintAttributes,
        cancellationSignal: android.os.CancellationSignal
    ): PrintDocumentInfo = synchronized(lock) {
        if (cancellationSignal.isCanceled) throw CancellationException("Print layout was cancelled.")
        val source = ensureStagedSource()
        validateSourceFile(source)
        val count = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.pageCount.also {
                    if (it <= 0) throw IOException("The source PDF contains no pages.")
                }
            }
        }
        if (cancellationSignal.isCanceled) throw CancellationException("Print layout was cancelled.")
        printAttributes = newAttributes
        pageCount = count
        PrintDocumentInfo.Builder(normalizePdfName(displayName))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(count)
            .build()
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal,
        callback: WriteResultCallback
    ) {
        try {
            val written = writeDocument(pages, destination, cancellationSignal)
            callback.onWriteFinished(written)
        } catch (error: Exception) {
            if (cancellationSignal.isCanceled || error is CancellationException) callback.onWriteCancelled()
            else callback.onWriteFailed(error.localizedMessage ?: "Unable to print this PDF.")
        }
        // Android owns the destination descriptor. Do not close it before callback completion.
    }

    internal fun writeDocument(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal
    ): Array<PageRange> {
        if (cancellationSignal.isCanceled) throw CancellationException("PDF printing was cancelled.")
        val source = synchronized(lock) { ensureStagedSource() }
        validateSourceFile(source)
        val output = FileOutputStream(destination.fileDescriptor)
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

    private fun ensureStagedSource(): File =
        stagedSource ?: stageSource().also { stagedSource = it }

    private fun validateSourceFile(file: File) {
        if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) {
            throw IOException("The source PDF is empty or unreadable.")
        }
    }

    private fun stageSource(): File {
        val file = File.createTempFile("print_source_", ".pdf", context.cacheDir)
        try {
            val input = if (sourceUri.scheme == "file") {
                File(sourceUri.path ?: throw IOException("The source path is missing.")).inputStream()
            } else {
                context.contentResolver.openInputStream(sourceUri)
                    ?: throw IOException("Unable to open the selected PDF.")
            }
            input.use { source ->
                FileOutputStream(file).use { sink ->
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
