package com.vibesync.toolkit.pdf.data.pdf

import android.content.ContentValues
import com.vibesync.toolkit.pdf.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Images
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Scoped-storage JPEG exporter; embeds a JFIF density unit of inches and 300x300 DPI. */
object IdPhotoExporter {
    fun cropForPreset(
        source: Bitmap,
        preset: IdPhotoPresets,
        zoom: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f
    ): Bitmap {
        require(source.width > 0 && source.height > 0)
        val targetAspect = preset.widthPx.toFloat() / preset.heightPx.toFloat()
        val sourceAspect = source.width.toFloat() / source.height.toFloat()
        var cropWidth = source.width.toFloat()
        var cropHeight = source.height.toFloat()
        if (sourceAspect > targetAspect) cropWidth = source.height * targetAspect
        else cropHeight = source.width / targetAspect
        cropWidth = (cropWidth / zoom.coerceIn(1f, 4f)).coerceAtLeast(1f)
        cropHeight = (cropHeight / zoom.coerceIn(1f, 4f)).coerceAtLeast(1f)
        val maxLeft = (source.width - cropWidth).coerceAtLeast(0f)
        val maxTop = (source.height - cropHeight).coerceAtLeast(0f)
        val left = (maxLeft / 2f + panX * maxLeft / 2f).coerceIn(0f, maxLeft)
        val top = (maxTop / 2f + panY * maxTop / 2f).coerceIn(0f, maxTop)
        val rect = RectF(left, top, left + cropWidth, top + cropHeight)
        val cropped = Bitmap.createBitmap(
            source,
            rect.left.toInt().coerceIn(0, source.width - 1),
            rect.top.toInt().coerceIn(0, source.height - 1),
            rect.width().toInt().coerceAtLeast(1).coerceAtMost(source.width - rect.left.toInt()),
            rect.height().toInt().coerceAtLeast(1).coerceAtMost(source.height - rect.top.toInt())
        )
        return Bitmap.createScaledBitmap(cropped, preset.widthPx, preset.heightPx, true).also {
            if (cropped !== source && !cropped.isRecycled) cropped.recycle()
        }
    }

    suspend fun save(context: Context, bitmap: Bitmap, preset: IdPhotoPresets): Result<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(UnsupportedOperationException(context.getString(R.string.id_photo_requires_scoped_storage)))
        }
        val values = ContentValues().apply {
            put(Images.Media.DISPLAY_NAME, "VibeSync_ID_Photo_${preset.name}_${System.currentTimeMillis()}.jpg")
            put(Images.Media.MIME_TYPE, "image/jpeg")
            put(Images.Media.RELATIVE_PATH, "Pictures/VibeSync")
            put(Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var uri: Uri? = null
        return try {
            uri = resolver.insert(Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException(context.getString(R.string.id_photo_save_failed))
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(jpegBytesWithDpi(bitmap, quality = 88))
            } ?: throw IOException(context.getString(R.string.id_photo_save_failed))
            val published = ContentValues().apply { put(Images.Media.IS_PENDING, 0) }
            resolver.update(uri, published, null, null)
            Result.success(uri)
        } catch (error: Exception) {
            uri?.let { resolver.delete(it, null, null) }
            Result.failure(error)
        }
    }

    internal fun jpegBytesWithDpi(bitmap: Bitmap, quality: Int = 88): ByteArray {
        // JPEG cannot represent alpha; flatten transparent pixels onto white before encoding.
        val opaqueBitmap = flattenBitmapOnWhiteBackground(bitmap)
        try {
            val encoded = ByteArrayOutputStream()
            if (!opaqueBitmap.compress(Bitmap.CompressFormat.JPEG, quality, encoded)) {
                throw IOException("Could not encode the JPEG image.")
            }
            val jpeg = encoded.toByteArray()
            require(jpeg.size >= 2 && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte())
            val jfif = byteArrayOf(
                0xff.toByte(), 0xe0.toByte(), 0x00, 0x10,
                0x4a, 0x46, 0x49, 0x46, 0x00, 0x01, 0x02,
                0x01, 0x01, 0x2c, 0x01, 0x2c, 0x00, 0x00
            )
            return ByteArray(jpeg.size + jfif.size).also { out ->
                jpeg.copyInto(out, 0, 0, 2)
                jfif.copyInto(out, 2)
                jpeg.copyInto(out, 2 + jfif.size, 2)
            }
        } finally {
            if (!opaqueBitmap.isRecycled) opaqueBitmap.recycle()
        }
    }
}
