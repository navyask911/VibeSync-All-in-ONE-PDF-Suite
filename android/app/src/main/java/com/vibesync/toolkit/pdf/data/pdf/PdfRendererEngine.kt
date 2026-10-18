package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
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
            try {
                closeDocument()

                currentDocumentId = uri.toString().hashCode().toString()
                
                // Copy to cache file first to guarantee seekable file descriptor even with virtual cloud SAF providers
                cachedTempFile = UriUtils.copyUriToCacheFile(context, uri, "render_")
                parcelFileDescriptor = ParcelFileDescriptor.open(cachedTempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                
                synchronized(renderLock) {
                    pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
                }

                val count = pageCount
                Log.d(TAG, "Document opened successfully with $count pages.")
                Result.success(count)
            } catch (e: CancellationException) {
                // Cancellation must reach the caller, but any partially opened native resources
                // still need to be released.
                closeDocument()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open PDF document", e)
                closeDocument()
                Result.failure(e)
            }
        }
    }

    /**
     * Renders a specific page at the requested display scale factor.
     */
    suspend fun renderPage(pageIndex: Int, scale: Float = 1.0f): Bitmap? = withContext(Dispatchers.IO) {
        if (!scale.isFinite() || scale <= 0f) return@withContext null
        val renderScale = scale.coerceIn(0.1f, 4.0f)
        val bitmapCache = BitmapCache.getInstance()

        // Native renderer state and cache identity must be read under the same lock to avoid
        // mixing a previous document's cache key with a newly opened document.
        synchronized(renderLock) {
            val renderer = pdfRenderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            val cacheKey = BitmapCache.generateKey(currentDocumentId, pageIndex, renderScale)
            bitmapCache.get(cacheKey)?.let { return@withContext it }

            var page: PdfRenderer.Page? = null
            var allocatedBitmap: Bitmap? = null
            try {
                page = renderer.openPage(pageIndex)
                val targetWidth = (page.width * renderScale).toInt().coerceAtLeast(1)
                val targetHeight = (page.height * renderScale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                allocatedBitmap = bitmap
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { postScale(renderScale, renderScale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmapCache.put(cacheKey, bitmap)
                return@withContext bitmap
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM rendering page $pageIndex; clearing cache and retrying at lower resolution.", e)
                allocatedBitmap?.let { if (!it.isRecycled) it.recycle() }
                bitmapCache.clear()
                System.gc()

                // Retry at reduced resolution and RGB_565 (half the bitmap memory per pixel).
                val openPage = page ?: return@withContext null
                var fallbackBitmap: Bitmap? = null
                try {
                    val fallbackScale = minOf(renderScale, 0.5f)
                    val width = (openPage.width * fallbackScale).toInt().coerceAtLeast(1)
                    val height = (openPage.height * fallbackScale).toInt().coerceAtLeast(1)
                    val retryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
                    fallbackBitmap = retryBitmap
                    retryBitmap.eraseColor(Color.WHITE)
                    val retryMatrix = Matrix().apply { postScale(fallbackScale, fallbackScale) }
                    openPage.render(retryBitmap, null, retryMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmapCache.put(cacheKey, retryBitmap)
                    return@withContext retryBitmap
                } catch (retryError: OutOfMemoryError) {
                    fallbackBitmap?.let { if (!it.isRecycled) it.recycle() }
                    Log.e(TAG, "OOM during reduced-memory retry for page $pageIndex.", retryError)
                    return@withContext null
                } catch (retryError: Exception) {
                    fallbackBitmap?.let { if (!it.isRecycled) it.recycle() }
                    Log.e(TAG, "Reduced-memory retry failed for page $pageIndex.", retryError)
                    return@withContext null
                }
            } catch (e: Exception) {
                allocatedBitmap?.let { if (!it.isRecycled) it.recycle() }
                Log.e(TAG, "Error rendering page $pageIndex", e)
                return@withContext null
            } finally {
                try {
                    page?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing PdfRenderer.Page", e)
                }
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
        }
    }

    companion object {
        private const val TAG = "PdfRendererEngine"
    }
}
