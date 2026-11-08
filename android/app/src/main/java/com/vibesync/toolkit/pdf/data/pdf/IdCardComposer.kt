package com.vibesync.toolkit.pdf.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/** Composes the two sides of a standard 85.6 × 53.98 mm ID-1 card onto one A4 page. */
object IdCardComposer {
    private const val POINTS_PER_MM = 72f / 25.4f
    private const val CARD_WIDTH_MM = 85.6f
    private const val CARD_HEIGHT_MM = 53.98f
    private const val CARD_WIDTH_PX = 1011 // 300 DPI, rounded from 85.6 mm.
    private const val CARD_HEIGHT_PX = 638 // 300 DPI, rounded from 53.98 mm.
    private const val GAP_MM = 10f

    data class Placement(val x: Float, val y: Float, val width: Float, val height: Float)

    fun placements(
        frontWidth: Int,
        frontHeight: Int,
        backWidth: Int,
        backHeight: Int
    ): Pair<Placement, Placement> {
        require(frontWidth > 0 && frontHeight > 0 && backWidth > 0 && backHeight > 0)
        val pageWidth = PDRectangle.A4.width
        val pageHeight = PDRectangle.A4.height
        val cardWidth = CARD_WIDTH_MM * POINTS_PER_MM
        val cardHeight = CARD_HEIGHT_MM * POINTS_PER_MM
        val gap = GAP_MM * POINTS_PER_MM
        val left = (pageWidth - cardWidth) / 2f
        val lowerY = (pageHeight - (cardHeight * 2f + gap)) / 2f
        val bottom = Placement(left, lowerY, cardWidth, cardHeight)
        val top = Placement(left, lowerY + cardHeight + gap, cardWidth, cardHeight)
        return top to bottom
    }

    /** Centre-crops to ID-1 landscape aspect, then renders one bounded 300-DPI RGB bitmap. */
    private fun normalizeCard(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "The selected card image is no longer available." }
        val targetAspect = CARD_WIDTH_MM / CARD_HEIGHT_MM
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        val crop = if (sourceAspect > targetAspect) {
            val width = source.height * targetAspect
            RectF((source.width - width) / 2f, 0f, (source.width + width) / 2f, source.height.toFloat())
        } else {
            val height = source.width / targetAspect
            RectF(0f, (source.height - height) / 2f, source.width.toFloat(), (source.height + height) / 2f)
        }
        val sourceRect = Rect(
            floor(crop.left).toInt().coerceIn(0, source.width - 1),
            floor(crop.top).toInt().coerceIn(0, source.height - 1),
            ceil(crop.right).toInt().coerceIn(1, source.width),
            ceil(crop.bottom).toInt().coerceIn(1, source.height)
        )
        val normalized = Bitmap.createBitmap(CARD_WIDTH_PX, CARD_HEIGHT_PX, Bitmap.Config.RGB_565)
        try {
            Canvas(normalized).apply {
                drawColor(Color.WHITE)
                drawBitmap(
                    source,
                    sourceRect,
                    Rect(0, 0, CARD_WIDTH_PX, CARD_HEIGHT_PX),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                )
            }
            return normalized
        } catch (error: Throwable) {
            if (!normalized.isRecycled) normalized.recycle()
            throw error
        }
    }

    suspend fun compose(front: Bitmap, back: Bitmap, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var temp: File? = null
        var normalizedFront: Bitmap? = null
        var normalizedBack: Bitmap? = null
        try {
            require(front.width > 0 && front.height > 0 && back.width > 0 && back.height > 0)
            val frontCardBitmap = normalizeCard(front).also { normalizedFront = it }
            val backCardBitmap = normalizeCard(back).also { normalizedBack = it }
            currentCoroutineContext().ensureActive()

            document = PDDocument()
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val frontImage = LosslessFactory.createFromImage(document, frontCardBitmap)
            val backImage = LosslessFactory.createFromImage(document, backCardBitmap)
            val (frontPlacement, backPlacement) = placements(front.width, front.height, back.width, back.height)
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(frontImage, frontPlacement.x, frontPlacement.y, frontPlacement.width, frontPlacement.height)
                stream.drawImage(backImage, backPlacement.x, backPlacement.y, backPlacement.width, backPlacement.height)
            }
            frontCardBitmap.recycle()
            normalizedFront = null
            backCardBitmap.recycle()
            normalizedBack = null

            val parent = outputFile.parentFile ?: throw IOException("Output folder is missing.")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create output folder.")
            temp = File.createTempFile("id_card_", ".pdf", parent)
            document.save(temp)
            document.close()
            document = null
            currentCoroutineContext().ensureActive()
            if (!temp.renameTo(outputFile)) {
                temp.copyTo(outputFile, overwrite = true)
                temp.delete()
            }
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            temp?.delete()
            outputFile.delete()
            throw cancelled
        } catch (error: OutOfMemoryError) {
            temp?.delete()
            outputFile.delete()
            Result.failure(IOException("The images are too large to compose safely. Choose smaller images and try again.", error))
        } catch (error: Exception) {
            temp?.delete()
            outputFile.delete()
            Result.failure(error)
        } finally {
            normalizedFront?.let { if (!it.isRecycled) it.recycle() }
            normalizedBack?.let { if (!it.isRecycled) it.recycle() }
            runCatching { document?.close() }
        }
    }
}
