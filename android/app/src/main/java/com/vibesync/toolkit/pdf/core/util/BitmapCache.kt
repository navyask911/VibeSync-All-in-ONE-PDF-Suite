package com.vibesync.toolkit.pdf.core.util

import android.graphics.Bitmap
import android.util.Log
import androidx.collection.LruCache
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Tile-based dynamic memory cache for rendered PDF page bitmaps.
 * Limits memory footprint to a safe fraction of app heap (1/8th)
 * and safely recycles evicted bitmaps to avoid OOM on 500+ page documents.
 */
class BitmapCache private constructor() {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8 // Use 1/8th of available memory for PDF rendering cache

    // Track active bitmaps in use by Composables so we don't recycle bitmaps currently being drawn
    private val activeKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            // The cache size will be measured in kilobytes rather than number of items.
            return bitmap.byteCount / 1024
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && oldValue != newValue) {
                // Only recycle if not actively in screen draw pass
                if (!activeKeys.contains(key) && !oldValue.isRecycled) {
                    try {
                        oldValue.recycle()
                        Log.v(TAG, "Bitmap evicted and recycled: $key")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error recycling bitmap: $key", e)
                    }
                }
            }
        }
    }

    fun get(key: String): Bitmap? {
        val bitmap = memoryCache.get(key)
        if (bitmap != null && bitmap.isRecycled) {
            memoryCache.remove(key)
            return null
        }
        return bitmap
    }

    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            memoryCache.put(key, bitmap)
        }
    }

    fun markActive(key: String) {
        activeKeys.add(key)
    }

    fun markInactive(key: String) {
        activeKeys.remove(key)
    }

    fun remove(key: String) {
        memoryCache.remove(key)?.let { bitmap ->
            // The cache may no longer own this bitmap exclusively; displayed images must remain valid.
            if (!activeKeys.contains(key) && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun clear() {
        // entryRemoved() preserves currently displayed bitmaps. Keep activeKeys until their
        // corresponding composables are disposed, otherwise a subsequent cache insertion for the
        // same page could be recycled while still visible.
        memoryCache.evictAll()
        System.gc()
    }

    companion object {
        private const val TAG = "BitmapCache"

        @Volatile
        private var instance: BitmapCache? = null

        fun getInstance(): BitmapCache {
            return instance ?: synchronized(this) {
                instance ?: BitmapCache().also { instance = it }
            }
        }

        fun generateKey(docId: String, pageIndex: Int, scale: Float): String {
            return "${docId}_p${pageIndex}_s${(scale * 100).toInt()}"
        }
    }
}
