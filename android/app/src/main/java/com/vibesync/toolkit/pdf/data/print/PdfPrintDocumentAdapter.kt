package com.vibesync.toolkit.pdf.data.print

import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo

/**
 * Android platform print adapter. It rasterizes one PDF page at a time and streams the resulting
 * document to the system-provided ParcelFileDescriptor. It intentionally uses no cloud SDK.
 */
class PdfPrintDocumentAdapter(
    private val context: android.content.Context,
    private val sourceUri: android.net.Uri,
    private val displayName: String,
    preparedSource: java.io.File? = null
) : PrintDocumentAdapter() {
    private val lock = Any()
    private val preparedSource: java.io.File? = preparedSource
    private var stagedSource: java.io.File? = preparedSource
    private var sourceDescriptor: android.os.ParcelFileDescriptor? = null
    private var renderer: android.graphics.pdf.PdfRenderer? = null
    private var printAttributes: PrintAttributes? = null
    private var pageCount = 0

    override fun onStart() {
        synchronized(lock) {
            activeAdapter?.takeIf { it !== this }?.cleanup()
            activeAdapter = this
            val readySource = preparedSource?.takeIf {
                it.isFile && it.canRead() && it.length() > 0L
            }
            cleanup(preserveStagedSource = readySource != null)
            stagedSource = readySource ?: stageSource()
            if (!stagedSource!!.isFile || stagedSource!!.length() <= 0L) {
                throw java.io.IOException("The print source is empty or unreadable.")
            }
            // The launcher validates prepared sources before invoking PrintManager.print().
            // Layout still revalidates renderer/page-count state via onLayout.
            runCatching { ensureRenderer() }
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
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        } catch (error: Exception) {
            if (cancellationSignal.isCanceled) callback.onLayoutCancelled()
            else callback.onLayoutFailed(error.localizedMessage ?: "Unable to prepare this PDF for printing.")
        }
    }

    /** Performs the layout work separately so the same operation can be verified without SDK-private callbacks. */
    internal fun prepareLayout(
        newAttributes: PrintAttributes,
        cancellationSignal: android.os.CancellationSignal
    ): PrintDocumentInfo = synchronized(lock) {
        if (cancellationSignal.isCanceled) {
            throw java.util.concurrent.CancellationException("Print layout was cancelled.")
        }
        printAttributes = newAttributes
        ensureRenderer()
        if (cancellationSignal.isCanceled) {
            throw java.util.concurrent.CancellationException("Print layout was cancelled.")
        }
        PrintDocumentInfo.Builder(normalizePdfName(displayName))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: android.os.ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal,
        callback: WriteResultCallback
    ) {
        try {
            callback.onWriteFinished(writeDocument(pages, destination, cancellationSignal))
        } catch (error: Exception) {
            if (cancellationSignal.isCanceled || error is java.util.concurrent.CancellationException) {
                callback.onWriteCancelled()
            } else {
                callback.onWriteFailed(error.localizedMessage ?: "Unable to print this PDF.")
            }
        } finally {
            runCatching { destination.close() }
        }
    }

    /** Rasterizes the selected source pages and writes a PDF to the provided descriptor. */
    internal fun writeDocument(
        pages: Array<out PageRange>,
        destination: android.os.ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal
    ): Array<PageRange> {
        val output = android.graphics.pdf.PdfDocument()
        try {
            val sourceRenderer = synchronized(lock) {
                ensureRenderer()
                renderer ?: throw java.io.IOException("The source PDF renderer is unavailable.")
            }
            val attributes = printAttributes
            val dpi = (attributes?.resolution?.horizontalDpi ?: DEFAULT_DPI)
                .coerceIn(MINIMUM_DPI, MAXIMUM_DPI)
            val mediaSize = attributes?.mediaSize
            val outputPageWidthPt = mediaSize?.let {
                it.widthMils * POINTS_PER_INCH / MILS_PER_INCH
            } ?: DEFAULT_A4_WIDTH_PT
            val outputPageHeightPt = mediaSize?.let {
                it.heightMils * POINTS_PER_INCH / MILS_PER_INCH
            } ?: DEFAULT_A4_HEIGHT_PT
            val allPages = pages.isEmpty() || pages.any { it == PageRange.ALL_PAGES }

            var writtenPages = 0
            for (index in 0 until sourceRenderer.pageCount) {
                if (!allPages && pages.none { index in it.start..it.end }) continue
                if (cancellationSignal.isCanceled) {
                    throw java.util.concurrent.CancellationException("PDF printing was cancelled.")
                }
                sourceRenderer.openPage(index).use { sourcePage ->
                    val sourceWidth = sourcePage.width.coerceAtLeast(1)
                    val sourceHeight = sourcePage.height.coerceAtLeast(1)
                    val memoryDpi = memoryBoundDpi(sourceWidth, sourceHeight, dpi)
                    val bitmapWidth = (sourceWidth.toDouble() * memoryDpi / POINTS_PER_INCH)
                        .toInt().coerceAtLeast(1)
                    val bitmapHeight = (sourceHeight.toDouble() * memoryDpi / POINTS_PER_INCH)
                        .toInt().coerceAtLeast(1)
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        bitmapWidth,
                        bitmapHeight,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    try {
                        sourcePage.render(
                            bitmap,
                            null,
                            null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                        )
                        val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                            outputPageWidthPt.toInt().coerceAtLeast(1),
                            outputPageHeightPt.toInt().coerceAtLeast(1),
                            writtenPages + 1
                        ).create()
                        val page = output.startPage(info)
                        val scale = minOf(
                            outputPageWidthPt / bitmap.width.coerceAtLeast(1),
                            outputPageHeightPt / bitmap.height.coerceAtLeast(1)
                        )
                        val drawWidth = bitmap.width * scale
                        val drawHeight = bitmap.height * scale
                        val destinationRect = android.graphics.RectF(
                            (outputPageWidthPt - drawWidth) / 2f,
                            (outputPageHeightPt - drawHeight) / 2f,
                            (outputPageWidthPt + drawWidth) / 2f,
                            (outputPageHeightPt + drawHeight) / 2f
                        )
                        page.canvas.drawBitmap(
                            bitmap,
                            null,
                            destinationRect,
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        )
                        output.finishPage(page)
                        writtenPages++
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            java.io.FileOutputStream(destination.fileDescriptor).use { stream ->
                output.writeTo(stream)
                stream.flush()
                destination.fileDescriptor.sync()
            }
            return if (writtenPages == 0) emptyArray() else arrayOf(PageRange(0, writtenPages - 1))
        } finally {
            output.close()
        }
    }

    override fun onFinish() {
        synchronized(lock) {
            if (activeAdapter === this) activeAdapter = null
            cleanup()
        }
        super.onFinish()
    }

    private fun ensureRenderer() {
        if (renderer != null) return
        val source = stagedSource ?: stageSource().also { stagedSource = it }
        val descriptor = android.os.ParcelFileDescriptor.open(
            source,
            android.os.ParcelFileDescriptor.MODE_READ_ONLY
        )
        try {
            val newRenderer = android.graphics.pdf.PdfRenderer(descriptor)
            sourceDescriptor = descriptor
            renderer = newRenderer
            pageCount = newRenderer.pageCount
            if (pageCount <= 0) throw java.io.IOException("The source PDF contains no pages.")
        } catch (error: Exception) {
            descriptor.close()
            throw error
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
            if (!file.isFile || !file.canRead() || file.length() <= 0L) {
                throw java.io.IOException("The selected PDF is empty or unreadable.")
            }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun cleanup(preserveStagedSource: Boolean = false) {
        runCatching { renderer?.close() }
        renderer = null
        runCatching { sourceDescriptor?.close() }
        sourceDescriptor = null
        if (!preserveStagedSource) {
            runCatching { stagedSource?.delete() }
            stagedSource = null
        }
        pageCount = 0
        printAttributes = null
    }

    private fun memoryBoundDpi(width: Int, height: Int, requestedDpi: Int): Int {
        val maxPixels = (Runtime.getRuntime().maxMemory().coerceAtLeast(32L * 1024L * 1024L) / 4L / 4L)
            .coerceAtLeast(1L).toDouble()
        val scaleAtRequestedDpi = requestedDpi.toDouble() / POINTS_PER_INCH
        val requestedPixels = width.toDouble() * scaleAtRequestedDpi *
            height.toDouble() * scaleAtRequestedDpi
        if (requestedPixels <= maxPixels) return requestedDpi
        val scale = kotlin.math.sqrt(maxPixels / requestedPixels)
        return (requestedDpi * scale).toInt().coerceIn(MINIMUM_DPI, requestedDpi)
    }

    private fun normalizePdfName(name: String): String =
        if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"

    companion object {
        const val DEFAULT_DPI = 300
        const val POINTS_PER_INCH = 72f
        const val MILS_PER_INCH = 1000f
        const val DEFAULT_A4_WIDTH_PT = 595f
        const val DEFAULT_A4_HEIGHT_PT = 842f
        private const val MINIMUM_DPI = 72
        private const val MAXIMUM_DPI = 600

        @Volatile
        private var activeAdapter: PdfPrintDocumentAdapter? = null
    }
}
