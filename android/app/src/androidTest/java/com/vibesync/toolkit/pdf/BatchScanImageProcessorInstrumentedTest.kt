package com.vibesync.toolkit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanImageProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class BatchScanImageProcessorInstrumentedTest {
    @Test
    fun rotatesExifOrientedCaptureToUprightAndEnhancesTheSavedRaster() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.cacheDir, "batch-image-device-${System.nanoTime()}").apply { mkdirs() }
        val source = File(folder, "landscape-source.jpg")
        val output = File(folder, "upright-page.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(128, 128, 128))
        }

        try {
            FileOutputStream(source).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
            }
            ExifInterface(source.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString()
                )
                saveAttributes()
            }

            val dimensions = BatchScanImageProcessor.process(source, output)
            assertEquals(40, dimensions.width)
            assertEquals(80, dimensions.height)
            assertTrue(output.isFile && output.length() > 0L)

            val outputBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, outputBounds)
            assertEquals(40, outputBounds.outWidth)
            assertEquals(80, outputBounds.outHeight)

            val outputOrientation = ExifInterface(output.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            // The encoder may omit the EXIF orientation tag entirely (UNDEFINED = 0).
            // Either an explicit NORMAL tag or no orientation tag means the raster is upright;
            // physical 40x80 dimensions above prove the 90-degree transform was applied.
            assertTrue(
                outputOrientation == ExifInterface.ORIENTATION_NORMAL ||
                    outputOrientation == ExifInterface.ORIENTATION_UNDEFINED
            )

            val processed = BitmapFactory.decodeFile(output.absolutePath)
                ?: throw AssertionError("The processed scan JPEG must decode.")
            try {
                val center = processed.getPixel(processed.width / 2, processed.height / 2)
                assertTrue(
                    "The neutral-gray test page should receive a mild brightness lift.",
                    Color.red(center) >= 129 && Color.green(center) >= 129 && Color.blue(center) >= 129
                )
            } finally {
                processed.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            folder.deleteRecursively()
        }
    }
}
