package com.vibesync.toolkit.pdf.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Perspective crop for a quadrilateral ordered top-left, top-right, bottom-right, bottom-left. */
object PerspectiveTransform {
    fun rectangleCorners(bitmap: Bitmap): List<PointF> = listOf(
        PointF(0f, 0f),
        PointF(bitmap.width.toFloat(), 0f),
        PointF(bitmap.width.toFloat(), bitmap.height.toFloat()),
        PointF(0f, bitmap.height.toFloat())
    )

    fun crop(source: Bitmap, corners: List<PointF>): Bitmap {
        require(!source.isRecycled) { "Source bitmap is recycled." }
        require(corners.size == 4) { "Exactly four crop corners are required." }
        corners.forEach { corner ->
            require(corner.x.isFinite() && corner.y.isFinite()) { "Crop coordinates must be finite." }
            require(corner.x in 0f..source.width.toFloat() && corner.y in 0f..source.height.toFloat()) {
                "Crop corners must lie within the source bitmap."
            }
        }

        val topWidth = distance(corners[0], corners[1])
        val bottomWidth = distance(corners[3], corners[2])
        val leftHeight = distance(corners[0], corners[3])
        val rightHeight = distance(corners[1], corners[2])
        val outputWidth = maxOf(topWidth, bottomWidth).roundToInt().coerceAtLeast(1)
        val outputHeight = maxOf(leftHeight, rightHeight).roundToInt().coerceAtLeast(1)

        val sourceQuad = FloatArray(8)
        corners.forEachIndexed { index, point ->
            sourceQuad[index * 2] = point.x
            sourceQuad[index * 2 + 1] = point.y
        }
        val destinationRect = floatArrayOf(
            0f, 0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f, outputHeight.toFloat()
        )
        val matrix = Matrix()
        require(matrix.setPolyToPoly(sourceQuad, 0, destinationRect, 0, 4)) {
            "The selected crop quadrilateral cannot be transformed."
        }

        val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(
            source,
            matrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return result
    }

    private fun distance(first: PointF, second: PointF): Float {
        val dx = second.x - first.x
        val dy = second.y - first.y
        return sqrt(dx * dx + dy * dy)
    }
}
