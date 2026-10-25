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
    private val displayName: String
) : PrintDocumentAdapter() {
    private val lock = Any()
    private var stagedSource: java.io.File? = null
    private var sourceDescriptor: android.os.ParcelFileDescriptor? = null
    private var renderer: android.graphics.pdf.PdfRenderer? = null
    private var printAttributes: PrintAttributes? = null
    private var pageCount = 0

    override fun onStart() {
        synchronized(lock) {
            activeAdapter?.takeIf { it !== this }?.cleanup()
            activeAdapter = this
            cleanup()
            stagedSource = stageSource()
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
            if (cancellationSignal.isCanceled) {
                callback.onLayoutCancelled()
                return
            }
            synchronized(lock) {
                printAttributes = newAttributes
                ensureRenderer()
                if (cancellationSignal.isCanceled) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(normalizePdfName(displayName))
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pageCount)
                    .build()
                callback.onLayoutFinished(info, oldAttributes != newAttributes)
            }
        } catch (error: Exception) {
            callback.onLayoutFailed(error.localizedMessage ?: "Unable to prepare this PDF for printing.")
        }
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: android.os.ParcelFileDescriptor,
        cancellationSignal: android.os.CancellationSignal,
        callback: WriteResultCallback
    ) {
        val output = android.graphics.pdf.PdfDocument()
        try {
            val sourceRenderer = synchronized(lock) {
                ensureRenderer()
                renderer ?: throw java.io.IOException("The source PDF renderer is unavailable.")
            }
            val attributes = printAttributes
            val dpi = (attributes?.resolution?.horizontalDpi ?: DEFAULT_DPI).coerceIn(MINIMUM_DPI, MAXIMUM_DPI)
            val mediaSize = attributes?.mediaSize
            val outputPageWidthPt = mediaSize?.let { it.widthMils * POINTS_PER_INCH / MILS_PER_INCH } ?: DEFAULT_A4_WIDTH_PT
            val outputPageHeightPt = mediaSize?.let { it.heightMils * POINTS_PER_INCH / MILS_PER_INCH } ?: DEFAULT_A4_HEIGHT_PT
            val allPages = pages.isEmpty() || pages.any { it == PageRange.ALL_PAGES }

            for (index in 0 until sourceRenderer.pageCount) {
                if (!allPages && pages.none { index in it.start..it.end }) continue
                if (cancellationSignal.isCanceled) {
                    callback.onWriteCancelled()
                    return
                }
                sourceRenderer.openPage(index).use { sourcePage ->
                    val sourceWidth = sourcePage.width.coerceAtLeast(1)
                    val sourceHeight = sourcePage.height.coerceAtLeast(1)
                    val memoryDpi = memoryBoundDpi(sourceWidth, sourceHeight, dpi)
                    val bitmapWidth = (sourceWidth.toDouble() * memoryDpi / POINTS_PER_INCH).toInt().coerceAtLeast(1)
                    val bitmapHeight = (sourceHeight.toDouble() * memoryDpi / POINTS_PER_INCH).toInt().coerceAtLeast(1)
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        bitmapWidth,
                        bitmapHeight,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
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
                            index + 1
                        ).create()
                        val page = output.startPage(info)
                        val bounds = android.graphics.RectF(
                            0f,
                            0f,
                            outputPageWidthPt,
                            outputPageHeightPt
                        )
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
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (error: Exception) {
            if (cancellationSignal.isCanceled) callback.onWriteCancelled()
            else callback.onWriteFailed(error.localizedMessage ?: "Unable to print this PDF.")
        } finally {
            output.close()
            runCatching { destination.close() }
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
            input.use { source -> java.io.FileOutputStream(file).use { sink -> source.copyTo(sink) } }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun cleanup() {
        runCatching { renderer?.close() }
        renderer = null
        runCatching { sourceDescriptor?.close() }
        sourceDescriptor = null
        runCatching { stagedSource?.delete() }
        stagedSource = null
        pageCount = 0
        printAttributes = null
    }

    private fun memoryBoundDpi(width: Int, height: Int, requestedDpi: Int): Int {
        val maxPixels = (Runtime.getRuntime().maxMemory().coerceAtLeast(32L * 1024L * 1024L) / 4L / 4L)
            .coerceAtLeast(1L)
        val nativePixels = width.toLong() * height.toLong()
        if (nativePixels <= maxPixels) return requestedDpi
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / nativePixels.toDouble())
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
