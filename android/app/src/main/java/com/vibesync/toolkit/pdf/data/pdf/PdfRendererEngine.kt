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
import kotlinx.coroutines.Dispatchers
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open PDF document", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Renders a specific page at the requested display scale factor.
     */
    suspend fun renderPage(pageIndex: Int, scale: Float = 1.0f): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = BitmapCache.generateKey(currentDocumentId, pageIndex, scale)
        val bitmapCache = BitmapCache.getInstance()

        // 1. Check memory cache first
        bitmapCache.get(cacheKey)?.let {
            return@withContext it
        }

        // 2. Render from native engine with thread synchronization
        synchronized(renderLock) {
            val renderer = pdfRenderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(pageIndex)
                val targetWidth = (page.width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)

                // Allocate bitmap safely with ARGB_8888 for crystal clear vector rendering
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                // Fill pure white background
                bitmap.eraseColor(Color.WHITE)

                val matrix = Matrix().apply {
                    postScale(scale, scale)
                }

                // Render page to bitmap
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // Cache bitmap in memory pool
                bitmapCache.put(cacheKey, bitmap)
                return@withContext bitmap
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OOM rendering page $pageIndex, clearing cache and retrying in 565", e)
                bitmapCache.clear()
                System.gc()
                return@withContext null
            } catch (e: Exception) {
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
        synchronized(renderLock) {
            val renderer = pdfRenderer ?: return@withContext emptyList()
            val list = mutableListOf<PageMetadata>()
            for (i in 0 until renderer.pageCount) {
                try {
                    val page = renderer.openPage(i)
                    list.add(
                        PageMetadata(
                            pageIndex = i,
                            widthPt = page.width.toFloat(),
                            heightPt = page.height.toFloat()
                        )
                    )
                    page.close()
                } catch (e: Exception) {
                    list.add(PageMetadata(pageIndex = i, widthPt = 595f, heightPt = 842f))
                }
            }
            list
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
