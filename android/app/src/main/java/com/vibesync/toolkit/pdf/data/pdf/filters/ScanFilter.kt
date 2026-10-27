package com.vibesync.toolkit.pdf.data.pdf.filters

/**
 * A deterministic, on-device transformation over an ARGB IntArray.
 * Implementations must not mutate [pixels] and report normalized progress from 0f to 1f.
 */
interface ScanFilter {
    val id: FilterId

    suspend fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit = {}
    ): IntArray
}
