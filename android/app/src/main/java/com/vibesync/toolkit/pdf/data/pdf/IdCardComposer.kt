package com.vibesync.toolkit.pdf.data.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Creates a one-page A4 ID card front/back layout with aspect-preserving image placement. */
object IdCardComposer {
    private const val POINTS_PER_MM = 72f / 25.4f
    private const val MARGIN = 15f * POINTS_PER_MM
    private const val MIN_GAP = 10f * POINTS_PER_MM

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
        val slotHeight = (pageHeight - MARGIN * 2f - MIN_GAP) / 2f
        val topSlotBottom = MARGIN + slotHeight + MIN_GAP
        fun fit(width: Int, height: Int, yBottom: Float): Placement {
            val scale = minOf((pageWidth - MARGIN * 2f) / width.toFloat(), slotHeight / height.toFloat())
            val drawWidth = width * scale
            val drawHeight = height * scale
            return Placement(
                x = (pageWidth - drawWidth) / 2f,
                y = yBottom + (slotHeight - drawHeight) / 2f,
                width = drawWidth,
                height = drawHeight
            )
        }
        return fit(frontWidth, frontHeight, topSlotBottom) to fit(backWidth, backHeight, MARGIN)
    }

    suspend fun compose(front: Bitmap, back: Bitmap, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var temp: File? = null
        try {
            val a4 = PDRectangle.A4
            val page = PDPage(a4)
            document = PDDocument()
            document.addPage(page)
            val (frontPlacement, backPlacement) = placements(front.width, front.height, back.width, back.height)
            val frontImage = LosslessFactory.createFromImage(document, front)
            PDPageContentStream(document, page).use {
                it.drawImage(frontImage, frontPlacement.x, frontPlacement.y, frontPlacement.width, frontPlacement.height)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val backImage = LosslessFactory.createFromImage(document, back)
            PDPageContentStream(document, page, true, true).use {
                it.drawImage(backImage, backPlacement.x, backPlacement.y, backPlacement.width, backPlacement.height)
            }
            val parent = outputFile.parentFile ?: throw IOException("Output folder is missing.")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create output folder.")
            temp = File.createTempFile("id_card_", ".pdf", parent)
            document.save(temp)
            document.close()
            document = null
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!temp.renameTo(outputFile)) {
                temp.copyTo(outputFile, overwrite = true)
                temp.delete()
            }
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            temp?.delete()
            outputFile.delete()
            throw cancelled
        } catch (error: Exception) {
            temp?.delete()
            outputFile.delete()
            Result.failure(error)
        } finally {
            runCatching { document?.close() }
        }
    }
}
