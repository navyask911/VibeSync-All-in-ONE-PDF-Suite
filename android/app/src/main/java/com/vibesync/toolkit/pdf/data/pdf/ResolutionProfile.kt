package com.vibesync.toolkit.pdf.data.pdf

import android.graphics.Bitmap
import com.vibesync.toolkit.pdf.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Resolves output dimensions and quality without rebuilding or exporting PDFs. */
enum class ResolutionProfile {
    SMALLEST, STANDARD, LARGEST, HD_AUTO;

    fun spec(deviceMaxMemoryBytes: Long, sourceWidth: Int, sourceHeight: Int): ProfileSpec {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive." }
        val memoryBytes = deviceMaxMemoryBytes.coerceAtLeast(1L)
        return when (this) {
            SMALLEST -> boundedSpec(SMALLEST, 70, sourceWidth, sourceHeight, A4_SMALL_WIDTH, A4_SMALL_HEIGHT)
            STANDARD -> boundedSpec(STANDARD, 88, sourceWidth, sourceHeight, A4_STANDARD_WIDTH, A4_STANDARD_HEIGHT)
            LARGEST -> {
                val sourceBytes = sourceWidth.toLong() * sourceHeight.toLong() * BYTES_PER_PIXEL
                if (sourceBytes > memoryBytes / MEMORY_SAFETY_DIVISOR) {
                    standardFallback(sourceWidth, sourceHeight)
                } else {
                    ProfileSpec(null, null, 96, R.string.resolution_profile_largest,
                        R.string.resolution_profile_largest_description)
                }
            }
            HD_AUTO -> when {
                memoryBytes < MB_256 -> STANDARD.spec(memoryBytes, sourceWidth, sourceHeight)
                memoryBytes <= MB_768 -> largestTierSpec(memoryBytes, sourceWidth, sourceHeight, 95)
                else -> largestTierSpec(memoryBytes, sourceWidth, sourceHeight, 96)
            }
        }
    }

    private fun boundedSpec(
        profile: ResolutionProfile,
        quality: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        width: Int,
        height: Int
    ): ProfileSpec {
        val (targetWidth, targetHeight) = aspectPreservingBounds(sourceWidth, sourceHeight, width, height)
        return ProfileSpec(targetWidth, targetHeight, quality, profile.labelResource(), profile.descriptionResource())
    }

    private fun standardFallback(sourceWidth: Int, sourceHeight: Int): ProfileSpec =
        boundedSpec(STANDARD, 88, sourceWidth, sourceHeight, A4_STANDARD_WIDTH, A4_STANDARD_HEIGHT)
            .copy(fellBack = true)

    private fun largestTierSpec(memoryBytes: Long, sourceWidth: Int, sourceHeight: Int, quality: Int): ProfileSpec =
        LARGEST.spec(memoryBytes, sourceWidth, sourceHeight).copy(
            jpegQuality = quality,
            applyUnsharpMask = true
        )

    private fun labelResource(): Int = when (this) {
        SMALLEST -> R.string.resolution_profile_smallest
        STANDARD -> R.string.resolution_profile_standard
        LARGEST -> R.string.resolution_profile_largest
        HD_AUTO -> R.string.resolution_profile_hd_auto
    }

    private fun descriptionResource(): Int = when (this) {
        SMALLEST -> R.string.resolution_profile_smallest_description
        STANDARD -> R.string.resolution_profile_standard_description
        LARGEST -> R.string.resolution_profile_largest_description
        HD_AUTO -> R.string.resolution_profile_hd_auto_description
    }

    companion object {
        private const val BYTES_PER_PIXEL = 4L
        private const val MEMORY_SAFETY_DIVISOR = 4L
        private const val A4_SMALL_WIDTH = 1240
        private const val A4_SMALL_HEIGHT = 1754
        private const val A4_STANDARD_WIDTH = 2480
        private const val A4_STANDARD_HEIGHT = 3508
        private const val BYTES_PER_MEGABYTE = 1024L * 1024L
        private const val MB_256 = 256L * BYTES_PER_MEGABYTE
        private const val MB_768 = 768L * BYTES_PER_MEGABYTE

        /** Fits into A4 pixel bounds, rotates bounds for landscape sources, and preserves aspect. */
        private fun aspectPreservingBounds(
            sourceWidth: Int,
            sourceHeight: Int,
            portraitWidth: Int,
            portraitHeight: Int
        ): Pair<Int, Int> {
            val boundsWidth = if (sourceWidth > sourceHeight) portraitHeight else portraitWidth
            val boundsHeight = if (sourceWidth > sourceHeight) portraitWidth else portraitHeight
            val scale = min(
                boundsWidth.toDouble() / sourceWidth.toDouble(),
                boundsHeight.toDouble() / sourceHeight.toDouble()
            )
            return Pair(
                (sourceWidth * scale).roundToInt().coerceAtLeast(1),
                (sourceHeight * scale).roundToInt().coerceAtLeast(1)
            )
        }
    }
}

data class ProfileSpec(
    val targetWidthPx: Int?,
    val targetHeightPx: Int?,
    val jpegQuality: Int,
    val labelRes: Int,
    val descriptionRes: Int,
    val fellBack: Boolean = false,
    val applyUnsharpMask: Boolean = false
) {
    /** Filtered scaling for bounded profiles; null target dimensions preserve native pixels. */
    fun scaleBitmap(source: Bitmap): Bitmap {
        val targetWidth = targetWidthPx ?: return source
        val targetHeight = targetHeightPx ?: return source
        if (targetWidth == source.width && targetHeight == source.height) return source
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
}
