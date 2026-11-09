package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanImageProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchScanImageProcessorTest {
    @Test
    fun appliesExifRotationAndWritesAnUprightEnhancedImage() {
        val folder = File(System.getProperty("java.io.tmpdir"), "batch-image-${System.nanoTime()}")
            .apply { mkdirs() }
        val source = File(folder, "landscape-source.jpg")
        val output = File(folder, "upright-page.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    bitmap.setPixel(x, y, if (x < 40) Color.BLACK else Color.WHITE)
                }
            }
            FileOutputStream(source).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream))
            }
        } finally {
            bitmap.recycle()
        }

        try {
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val dimensions = BatchScanImageProcessor.process(source, output)
            assertEquals(40, dimensions.width)
            assertEquals(80, dimensions.height)
            assertTrue(output.isFile && output.length() > 0L)

            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(output.absolutePath, bounds)
            assertEquals(40, bounds.outWidth)
            assertEquals(80, bounds.outHeight)
            val orientation = ExifInterface(output.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            assertEquals(ExifInterface.ORIENTATION_NORMAL, orientation)
        } finally {
            folder.deleteRecursively()
        }
    }
}
