package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.os.SystemClock
import java.io.IOException
import java.util.UUID
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import com.vibesync.toolkit.pdf.core.util.BitmapCache
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.model.PageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-performance, OOM-safe PDF page rendering engine using android.graphics.pdf.PdfRenderer.
 * Synchronizes native calls to prevent concurrency crashes and coordinates with BitmapCache.
 */
class PdfRendererEngine(private val context: Context) {

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var cachedTempFile: File? = null
    private var currentDocumentId: String = ""
    private val renderLock = Any()
    private val mutex = Mutex()
    // Compatibility API callers receive a Bitmap, not a lease. Pin only a small rolling window
    // so those older callers cannot observe a bitmap recycled between render and draw.
    private val legacyPageLeases = LinkedHashMap<String, BitmapCache.BitmapLease>()

    val pageCount: Int
        get() = synchronized(renderLock) {
            pdfRenderer?.pageCount ?: 0
        }

    /** Returns the currently opened document's cache key for a page, if that page is valid. */
    fun cacheKeyForPage(pageIndex: Int, scale: Float): String? = synchronized(renderLock) {
        val renderer = pdfRenderer
        if (renderer == null || pageIndex < 0 || pageIndex >= renderer.pageCount ||
            !scale.isFinite() || scale <= 0f
        ) {
            null
        } else {
            BitmapCache.generateKey(currentDocumentId, pageIndex, scale.coerceIn(0.1f, 4.0f))
        }
    }

    /**
     * Opens a PDF document from a content or file Uri.
     */
    suspend fun openDocument(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "open.start scheme=" + (uri.scheme ?: "unknown"))
            try {
                closeDocument()
                currentDocumentId = UUID.randomUUID().toString()

                // Stage every provider type into private, seekable storage before native rendering.
                val preparedFile = UriUtils.copyUriToCacheFile(context, uri, "render_")
                cachedTempFile = preparedFile
                currentCoroutineContext().ensureActive()
                if (!preparedFile.isFile || !preparedFile.canRead() || preparedFile.length() <= 0L) {
                    throw IOException("The selected PDF is empty or unreadable.")
                }

                parcelFileDescriptor = ParcelFileDescriptor.open(
                    preparedFile,
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                synchronized(renderLock) {
                    pdfRenderer = PdfRenderer(requireNotNull(parcelFileDescriptor))
                }

                val count = pageCount
                if (count <= 0) {
                    throw IOException("The selected PDF contains no readable pages.")
                }
                Log.i(
                    TAG,
                    "open.success pages=" + count +
                        " bytes=" + preparedFile.length() +
                        " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                )
                Result.success(count)
            } catch (e: CancellationException) {
                Log.d(TAG, "open.cancelled")
                closeDocument()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open PDF (scheme=" + (uri.scheme ?: "unknown") + ")", e)
                closeDocument()
                Result.failure(e)
            }
        }
    }

    /**
     * Returns a leased bitmap for a page. Cache lookup/pinning happens before native rendering,
     * and the reservation remains held through cache insertion and delivery back to the caller.
     */
    suspend fun renderPage(
        pageIndex: Int,
        scale: Float = 1.0f
    ): Bitmap? {
        val lease = renderPageLease(pageIndex, scale) ?: return null
        return synchronized(renderLock) {
            // A document may have closed/replaced while the render coroutine was returning.
            if (
                pdfRenderer == null ||
                currentDocumentId.isBlank() ||
                !lease.key.startsWith("${currentDocumentId}_")
            ) {
                lease.close()
                return@synchronized null
            }

            legacyPageLeases.remove(lease.key)?.let { previous ->
                if (previous !== lease) previous.close()
            }
            legacyPageLeases[lease.key] = lease
            while (legacyPageLeases.size > MAX_LEGACY_PAGE_LEASES) {
                val iterator = legacyPageLeases.entries.iterator()
                val eldest = iterator.next()
                iterator.remove()
                eldest.value.close()
            }
            lease.bitmap
        }
    }

    /**
     * Leased rendering path for UI owners that can release their bitmap deterministically.
     */
    suspend fun renderPageLease(
        pageIndex: Int,
        scale: Float = 1.0f
    ): BitmapCache.BitmapLease? {
        if (!scale.isFinite() || scale <= 0f) return null

        val renderScale = scale.coerceIn(0.1f, 4.0f)
        val cache = BitmapCache.getInstance()
        val parentJob = currentCoroutineContext()[kotlinx.coroutines.Job]
        val pendingLease = AtomicReference<BitmapCache.BitmapLease?>(null)
        // If cancellation lands during native rendering or on the dispatcher hop back to Compose,
        // this callback closes any lease that the canceled caller never received.
        val cancellationHandle = parentJob?.invokeOnCompletion { cause ->
            if (cause != null) pendingLease.getAndSet(null)?.close()
        }

        try {
            val lease = withContext(Dispatchers.IO) {
                val coroutineContext = currentCoroutineContext()
                coroutineContext.ensureActive()

                synchronized(renderLock) {
                    val renderer = pdfRenderer ?: return@synchronized null
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                        return@synchronized null
                    }

                    val cacheKey = BitmapCache.generateKey(currentDocumentId, pageIndex, renderScale)
                    cache.acquire(cacheKey)?.let { cachedLease ->
                        pendingLease.set(cachedLease)
                        return@synchronized cachedLease
                    }

                    var reservation: BitmapCache.BitmapLease? = cache.reserve(cacheKey)
                    var renderedBitmap: Bitmap? = null
                    try {
                        renderedBitmap = try {
                            renderPageBitmap(renderer, pageIndex, renderScale, Bitmap.Config.ARGB_8888)
                        } catch (firstOom: OutOfMemoryError) {
                            Log.w(
                                TAG,
                                "Page render exceeded the memory budget; retrying at reduced scale."
                            )
                            cache.clear()
                            try {
                                renderPageBitmap(
                                    renderer,
                                    pageIndex,
                                    minOf(renderScale, LOW_MEMORY_RETRY_SCALE),
                                    Bitmap.Config.RGB_565
                                )
                            } catch (retryOom: OutOfMemoryError) {
                                Log.e(TAG, "Reduced-memory page render also exhausted memory.", retryOom)
                                return@synchronized null
                            } catch (retryError: Exception) {
                                if (retryError is CancellationException) throw retryError
                                Log.e(
                                    TAG,
                                    "Reduced-memory page render failed: " +
                                        retryError.javaClass.simpleName
                                )
                                return@synchronized null
                            }
                        }

                        coroutineContext.ensureActive()
                        if (renderedBitmap.isRecycled) {
                            return@synchronized null
                        }

                        val completedLease = checkNotNull(reservation)
                        completedLease.attach(renderedBitmap)
                        cache.put(cacheKey, renderedBitmap)
                        pendingLease.set(completedLease)
                        reservation = null
                        completedLease
                    } catch (cancelled: CancellationException) {
                        renderedBitmap?.let { if (!it.isRecycled) it.recycle() }
                        throw cancelled
                    } catch (error: OutOfMemoryError) {
                        renderedBitmap?.let { if (!it.isRecycled) it.recycle() }
                        Log.e(TAG, "Page render failed after memory recovery.", error)
                        null
                    } catch (error: Exception) {
                        renderedBitmap?.let { if (!it.isRecycled) it.recycle() }
                        Log.e(
                            TAG,
                            "Page render failed: " + error.javaClass.simpleName
                        )
                        null
                    } finally {
                        reservation?.close()
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            if (lease != null) pendingLease.compareAndSet(lease, null)
            return lease
        } catch (cancelled: CancellationException) {
            pendingLease.getAndSet(null)?.close()
            throw cancelled
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private data class RenderDimensions(
        val width: Int,
        val height: Int,
        val effectiveScale: Float
    )

    /**
     * Render dimensions are capped before allocation. Very large scanned pages are downscaled
     * rather than overflowing Int dimensions or repeatedly requesting impossible allocations.
     */
    private fun calculateRenderDimensions(
        pageWidth: Int,
        pageHeight: Int,
        requestedScale: Float,
        config: Bitmap.Config
    ): RenderDimensions {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw IOException("PDF page dimensions must be positive.")
        }

        val bytesPerPixel = if (config == Bitmap.Config.RGB_565) 2L else 4L
        val heapBudget = (Runtime.getRuntime().maxMemory() / 8L)
            .coerceAtLeast(MIN_BITMAP_BUDGET_BYTES)
            .coerceAtMost(MAX_BITMAP_BUDGET_BYTES)
        val maxPixels = (heapBudget / bytesPerPixel)
            .coerceAtLeast(1L)
            .coerceAtMost(MAX_BITMAP_PIXELS)
        val requestedPixels = pageWidth.toDouble() * pageHeight.toDouble() *
            requestedScale.toDouble() * requestedScale.toDouble()
        val safeScale = (
            if (requestedPixels.isFinite() && requestedPixels > maxPixels.toDouble()) {
                requestedScale.toDouble() * sqrt(maxPixels.toDouble() / requestedPixels)
            } else {
                requestedScale.toDouble()
            }
        ).coerceAtMost(requestedScale.toDouble()).coerceAtLeast(0.000001)

        var width = (pageWidth.toDouble() * safeScale)
            .toLong().coerceIn(1L, Int.MAX_VALUE.toLong())
        var height = (pageHeight.toDouble() * safeScale)
            .toLong().coerceIn(1L, Int.MAX_VALUE.toLong())

        // At most a handful of proportional adjustments are needed for integer rounding.
        // Avoid pixel-by-pixel decrements, which could stall on pathological page dimensions.
        repeat(3) {
            val pixels = width * height
            if (pixels > maxPixels) {
                val correction = sqrt(maxPixels.toDouble() / pixels.toDouble())
                width = (width.toDouble() * correction).toLong().coerceAtLeast(1L)
                height = (height.toDouble() * correction).toLong().coerceAtLeast(1L)
            }
        }
        if (width * height > maxPixels) {
            if (width >= height) width = (maxPixels / height).coerceAtLeast(1L)
            else height = (maxPixels / width).coerceAtLeast(1L)
        }

        // Use the effective allocated scale, not the requested scale, in PdfRenderer.Page.render.
        val effectiveScale = minOf(
            width.toDouble() / pageWidth.toDouble(),
            height.toDouble() / pageHeight.toDouble()
        ).toFloat().coerceAtLeast(0.000001f)
        return RenderDimensions(width.toInt(), height.toInt(), effectiveScale)
    }

    /** One native page is opened for one render attempt and closed immediately in finally. */
    private fun renderPageBitmap(
        renderer: PdfRenderer,
        pageIndex: Int,
        requestedScale: Float,
        config: Bitmap.Config
    ): Bitmap {
        val page = renderer.openPage(pageIndex)
        var bitmap: Bitmap? = null
        try {
            val dimensions = calculateRenderDimensions(
                page.width,
                page.height,
                requestedScale,
                config
            )
            bitmap = Bitmap.createBitmap(dimensions.width, dimensions.height, config)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply {
                setScale(dimensions.effectiveScale, dimensions.effectiveScale)
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } catch (error: Throwable) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            throw error
        } finally {
            try {
                page.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "Unable to close rendered PDF page.", closeError)
            }
        }
    }

    /**
     * Reads metadata (dimensions) of all pages.
     */
    suspend fun getPagesMetadata(): List<PageMetadata> = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        synchronized(renderLock) {
            val renderer = pdfRenderer ?: return@withContext emptyList()
            val list = mutableListOf<PageMetadata>()
            for (i in 0 until renderer.pageCount) {
                coroutineContext.ensureActive()
                var page: PdfRenderer.Page? = null
                try {
                    page = renderer.openPage(i)
                    list.add(
                        PageMetadata(
                            pageIndex = i,
                            widthPt = page.width.toFloat(),
                            heightPt = page.height.toFloat()
                        )
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    list.add(PageMetadata(pageIndex = i, widthPt = 595f, heightPt = 842f))
                } finally {
                    try {
                        page?.close()
                    } catch (closeError: Exception) {
                        Log.w(TAG, "Error closing metadata page $i", closeError)
                    }
                }
            }
            list
        }
    }

    /**
     * Closes native PDF resources on an I/O dispatcher and serializes cleanup against
     * concurrent document opens/renders.
     */
    suspend fun closeDocumentSafely() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeDocument()
        }
    }

    /**
     * Releases native file descriptors and temporary caches.
     */
    fun closeDocument() {
        synchronized(renderLock) {
            try {
                pdfRenderer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing PdfRenderer", e)
            } finally {
                pdfRenderer = null
            }

            try {
                parcelFileDescriptor?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing ParcelFileDescriptor", e)
            } finally {
                parcelFileDescriptor = null
            }

            cachedTempFile?.delete()
            cachedTempFile = null
            legacyPageLeases.values.toList().forEach { it.close() }
            legacyPageLeases.clear()
            currentDocumentId = ""
            BitmapCache.getInstance().clear()
        }
    }

    companion object {
        private const val TAG = "PdfRendererEngine"
        private const val LOW_MEMORY_RETRY_SCALE = 0.5f
        private const val MIN_BITMAP_BUDGET_BYTES = 512L * 1024L
        private const val MAX_BITMAP_BUDGET_BYTES = 32L * 1024L * 1024L
        private const val MAX_BITMAP_PIXELS = 16_000_000L
        private const val MAX_LEGACY_PAGE_LEASES = 3
    }
}
