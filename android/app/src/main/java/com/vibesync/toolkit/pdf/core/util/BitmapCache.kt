package com.vibesync.toolkit.pdf.core.util

import android.graphics.Bitmap
import android.util.Log
import androidx.collection.LruCache
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide bounded cache for rendered PDF pages.
 *
 * A caller must hold a [BitmapLease] for every bitmap currently displayed or being handed to UI.
 * Evicted bitmaps are retired until the final lease is closed; this prevents LruCache from
 * recycling a bitmap in the interval between rendering and Compose committing a frame.
 */
class BitmapCache private constructor() {

    private val cacheLock = Any()
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    private val cacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(MIN_CACHE_KB)
    private val activeLeaseCounts = HashMap<String, Int>()
    private val retiredBitmaps = HashMap<String, MutableList<Bitmap>>()

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int =
            (bitmap.byteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (oldValue === newValue) return
            // Every access that can mutate LruCache is performed under cacheLock. JVM monitors
            // are re-entrant, so this callback can safely examine lease counts under the same lock.
            if ((activeLeaseCounts[key] ?: 0) > 0) {
                retiredBitmaps.getOrPut(key) { mutableListOf() }.add(oldValue)
            } else {
                recycleSafely(oldValue)
            }
        }
    }

    /** Obtain a retained reference to a cached bitmap. Cache lookup and pinning are atomic. */
    fun acquire(key: String): BitmapLease? = synchronized(cacheLock) {
        val bitmap = memoryCache.get(key) ?: return@synchronized null
        if (bitmap.isRecycled) {
            memoryCache.remove(key)
            return@synchronized null
        }
        retain(key)
        BitmapLease(this, key, bitmap)
    }

    /**
     * Reserve the key before inserting a newly rendered bitmap. This is essential because a
     * single bitmap larger than the LRU budget can be evicted synchronously by put().
     */
    fun reserve(key: String): BitmapLease = synchronized(cacheLock) {
        retain(key)
        BitmapLease(this, key, null)
    }

    /** Insert the bitmap while its lease is already held. */
    fun put(key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(cacheLock) {
            memoryCache.put(key, bitmap)
        }
    }

    /** Compatibility access for non-UI callers; UI code should use acquire() and keep its lease. */
    fun get(key: String): Bitmap? = synchronized(cacheLock) {
        memoryCache.get(key)?.takeUnless { it.isRecycled }
    }

    /** Retained for existing callers; prefer [acquire] for new code. */
    fun markActive(key: String) {
        synchronized(cacheLock) { retain(key) }
    }

    /** Retained for existing callers; each markActive call must have one matching markInactive. */
    fun markInactive(key: String) {
        synchronized(cacheLock) { release(key) }
    }

    fun remove(key: String) {
        synchronized(cacheLock) { memoryCache.remove(key) }
    }

    /** Evict cached entries without recycling bitmaps that are still leased by a visible page. */
    fun clear() {
        synchronized(cacheLock) {
            memoryCache.evictAll()
        }
    }

    private fun retain(key: String) {
        activeLeaseCounts[key] = (activeLeaseCounts[key] ?: 0) + 1
    }

    private fun release(key: String) {
        val remaining = (activeLeaseCounts[key] ?: 1) - 1
        if (remaining > 0) {
            activeLeaseCounts[key] = remaining
            return
        }
        activeLeaseCounts.remove(key)
        retiredBitmaps.remove(key)?.forEach(::recycleSafely)
    }

    private fun releaseLease(key: String) {
        synchronized(cacheLock) { release(key) }
    }

    private fun recycleSafely(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        try {
            bitmap.recycle()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to recycle an evicted PDF page bitmap.", error)
        }
    }

    /**
     * Reference-counted ownership of a cache bitmap. A reservation has no bitmap until attach()
     * is called by the renderer; closing a reservation on every failure path releases its pin.
     */
    class BitmapLease internal constructor(
        private val owner: BitmapCache,
        val key: String,
        initialBitmap: Bitmap?
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        @Volatile
        private var heldBitmap: Bitmap? = initialBitmap

        val bitmap: Bitmap
            get() = checkNotNull(heldBitmap) { "Bitmap lease has not been populated yet." }

        internal fun attach(bitmap: Bitmap) {
            check(!closed.get()) { "Cannot attach a bitmap to a released lease." }
            check(!bitmap.isRecycled) { "Cannot lease a recycled bitmap." }
            heldBitmap = bitmap
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                heldBitmap = null
                owner.releaseLease(key)
            }
        }
    }

    companion object {
        private const val TAG = "BitmapCache"
        private const val MIN_CACHE_KB = 1024

        @Volatile
        private var instance: BitmapCache? = null

        fun getInstance(): BitmapCache =
            instance ?: synchronized(this) {
                instance ?: BitmapCache().also { instance = it }
            }

        fun generateKey(docId: String, pageIndex: Int, scale: Float): String =
            "${docId}_p${pageIndex}_s${(scale * 100).toInt()}"
    }
}
