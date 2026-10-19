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
        val startedAt = SystemClock.elapsedRealtime()
        val requestId = UUID.randomUUID().toString()
        val operationContext = currentCoroutineContext()
        var stage = "copy_source"
        var preparedFile: File? = null
        var transferredToRenderer = false
        Log.i(TAG, "open.start request=$requestId scheme=${uri.scheme ?: "unknown"}")

        try {
            // Stage each source independently before taking the native-renderer mutex. A blocked
            // provider read must not hold the shared renderer lock or prevent a different request
            // from preparing its source. The staged file is adopted only after this request wins.
            val copyStartedAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "open.stage request=$requestId stage=copy_source status=start")
            preparedFile = UriUtils.copyUriToCacheFile(
                context = context,
                uri = uri,
                prefix = "render_",
                cancellationCheck = { operationContext.ensureActive() },
                onProgress = { bytes ->
                    Log.d(
                        TAG,
                        "open.copy_progress request=$requestId bytes=$bytes " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - copyStartedAt}"
                    )
                }
            )
            operationContext.ensureActive()
            val stagedFile = requireNotNull(preparedFile)
            Log.i(
                TAG,
                "open.stage request=$requestId stage=copy_source status=success " +
                    "bytes=${stagedFile.length()} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - copyStartedAt}"
            )

            mutex.withLock {
                operationContext.ensureActive()
                stage = "close_previous_document"
                closeDocument()
                synchronized(renderLock) { currentDocumentId = requestId }

                try {
                    stage = "validate_cache"
                    if (!stagedFile.isFile || !stagedFile.canRead() || stagedFile.length() <= 0L) {
                        throw IOException("The selected PDF is empty or unreadable.")
                    }

                    cachedTempFile = stagedFile
                    transferredToRenderer = true

                    stage = "open_descriptor"
                    val descriptorStartedAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "open.stage request=$requestId stage=open_descriptor status=start")
                    parcelFileDescriptor = ParcelFileDescriptor.open(
                        stagedFile,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    Log.i(
                        TAG,
                        "open.stage request=$requestId stage=open_descriptor status=success " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - descriptorStartedAt}"
                    )

                    stage = "initialize_renderer"
                    val rendererStartedAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "open.stage request=$requestId stage=initialize_renderer status=start")
                    synchronized(renderLock) {
                        operationContext.ensureActive()
                        pdfRenderer = PdfRenderer(requireNotNull(parcelFileDescriptor))
                    }
                    Log.i(
                        TAG,
                        "open.stage request=$requestId stage=initialize_renderer status=success " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - rendererStartedAt}"
                    )

                    stage = "read_page_count"
                    Log.i(TAG, "open.stage request=$requestId stage=read_page_count status=start")
                    val count = pageCount
                    if (count <= 0) throw IOException("The selected PDF contains no readable pages.")
                    Log.i(
                        TAG,
                        "open.success request=$requestId pages=$count bytes=${stagedFile.length()} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    Result.success(count)
                } catch (cancelled: CancellationException) {
                    if (currentDocumentId == requestId) closeDocument()
                    throw cancelled
                } catch (error: OutOfMemoryError) {
                    Log.e(
                        TAG,
                        "open.failed request=$requestId stage=$stage category=OutOfMemoryError",
                        error
                    )
                    if (currentDocumentId == requestId) closeDocument()
                    throw error
                } catch (error: Exception) {
                    Log.e(
                        TAG,
                        "open.failed request=$requestId stage=$stage " +
                            "category=${error.javaClass.simpleName} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                        error
                    )
                    if (currentDocumentId == requestId) closeDocument()
                    Result.failure(error)
                }
            }
        } catch (cancelled: CancellationException) {
            Log.i(
                TAG,
                "open.cancelled request=$requestId stage=$stage " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            throw cancelled
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "open.failed request=$requestId stage=$stage category=OutOfMemoryError", error)
            throw error
        } catch (error: Exception) {
            Log.e(
                TAG,
                "open.failed request=$requestId stage=$stage category=${error.javaClass.simpleName} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                error
            )
            Result.failure(error)
        } finally {
            if (!transferredToRenderer) {
                preparedFile?.let { if (it.exists()) it.delete() }
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
        val startedAt = SystemClock.elapsedRealtime()
        var stage = "open_page"
        var bitmap: Bitmap? = null
        Log.d(
            TAG,
            "render.start document=$currentDocumentId page=$pageIndex scale=$requestedScale"
        )
        val page = try {
            Log.d(TAG, "render.stage document=$currentDocumentId page=$pageIndex stage=open_page status=start")
            renderer.openPage(pageIndex)
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "render.failed document=$currentDocumentId page=$pageIndex stage=$stage " +
                    "category=${error.javaClass.simpleName}",
                error
            )
            throw error
        }

        try {
            stage = "calculate_dimensions"
            val dimensions = calculateRenderDimensions(
                page.width,
                page.height,
                requestedScale,
                config
            )
            Log.d(
                TAG,
                "render.stage document=$currentDocumentId page=$pageIndex stage=$stage " +
                    "width=${dimensions.width} height=${dimensions.height} config=$config"
            )

            stage = "allocate_bitmap"
            Log.d(TAG, "render.stage document=$currentDocumentId page=$pageIndex stage=$stage status=start")
            bitmap = Bitmap.createBitmap(dimensions.width, dimensions.height, config)
            bitmap.eraseColor(Color.WHITE)

            val matrix = Matrix().apply {
                setScale(dimensions.effectiveScale, dimensions.effectiveScale)
            }
            stage = "native_page_render"
            Log.d(TAG, "render.stage document=$currentDocumentId page=$pageIndex stage=$stage status=start")
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            Log.i(
                TAG,
                "render.success document=$currentDocumentId page=$pageIndex " +
                    "width=${bitmap.width} height=${bitmap.height} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return bitmap
        } catch (error: Throwable) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            Log.e(
                TAG,
                "render.failed document=$currentDocumentId page=$pageIndex stage=$stage " +
                    "category=${error.javaClass.simpleName} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                error
            )
            throw error
        } finally {
            try {
                page.close()
            } catch (closeError: Exception) {
                Log.w(
                    TAG,
                    "render.page_close_failed document=$currentDocumentId page=$pageIndex",
                    closeError
                )
            }
        }
    }

    /**
     * Reads metadata for all pages. Each page is handled in a separate renderer-lock section so a
     * large PDF's metadata scan does not monopolize the renderer and delay the first visible page.
     */
    suspend fun getPagesMetadata(): List<PageMetadata> {
        val count = pageCount
        if (count <= 0) return emptyList()
        return getPagesMetadata(0, count)
    }

    /**
     * Reads the half-open page range [startIndex, endExclusive). The document identity is captured
     * once; if the document is replaced while a batch is running, the stale batch stops.
     */
    suspend fun getPagesMetadata(
        startIndex: Int,
        endExclusive: Int
    ): List<PageMetadata> = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val expectedDocumentId = synchronized(renderLock) { currentDocumentId }
        if (expectedDocumentId.isBlank()) return@withContext emptyList()

        val start = startIndex.coerceAtLeast(0)
        val end = endExclusive.coerceAtLeast(start)
        val result = ArrayList<PageMetadata>((end - start).coerceAtMost(256))
        for (pageIndex in start until end) {
            coroutineContext.ensureActive()
            val metadata = synchronized(renderLock) {
                if (currentDocumentId != expectedDocumentId) return@synchronized null
                val renderer = pdfRenderer ?: return@synchronized null
                if (pageIndex >= renderer.pageCount) return@synchronized null

                var page: PdfRenderer.Page? = null
                val pageStartedAt = SystemClock.elapsedRealtime()
                Log.d(
                    TAG,
                    "metadata.page_start document=$expectedDocumentId page=$pageIndex"
                )
                try {
                    page = renderer.openPage(pageIndex)
                    val metadata = PageMetadata(
                        pageIndex = pageIndex,
                        widthPt = page.width.toFloat(),
                        heightPt = page.height.toFloat()
                    )
                    Log.d(
                        TAG,
                        "metadata.page_success document=$expectedDocumentId page=$pageIndex " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - pageStartedAt}"
                    )
                    metadata
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(
                        TAG,
                        "metadata.page_failed document=$expectedDocumentId page=$pageIndex " +
                            "category=${error.javaClass.simpleName}",
                        error
                    )
                    PageMetadata(pageIndex = pageIndex, widthPt = 595f, heightPt = 842f)
                } finally {
                    try {
                        page?.close()
                    } catch (closeError: Exception) {
                        Log.w(
                            TAG,
                            "metadata.page_close_failed document=$expectedDocumentId page=$pageIndex",
                            closeError
                        )
                    }
                }
            }
            if (metadata == null) break
            result.add(metadata)
        }

        Log.d(
            TAG,
            "metadata.batch_complete document=$expectedDocumentId start=$start " +
                "requested=${end - start} completed=${result.size}"
        )
        result
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
