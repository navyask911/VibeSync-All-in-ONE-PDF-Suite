package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.vibesync.toolkit.pdf.data.pdf.IdPhotoExporter
import com.vibesync.toolkit.pdf.data.pdf.IdPhotoPresets
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class IdPhotoMakerTest {
    @Test
    fun allPresetsScaleWithAspectAndJpegCarriesStandardProfileDensity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = ResolutionProfilePrefs(context)
        val previousProfile = preferences.read()
        preferences.write(ResolutionProfile.STANDARD)
        val expected = mapOf(
            IdPhotoPresets.STANDARD_35x45 to (413 to 531),
            IdPhotoPresets.STANDARD_2x2in to (600 to 600),
            IdPhotoPresets.STANDARD_35x35 to (413 to 413),
            IdPhotoPresets.PASSPORT_US to (602 to 602)
        )
        expected.forEach { (preset, dimensions) ->
            assertEquals(dimensions.first, preset.widthPx)
            assertEquals(dimensions.second, preset.heightPx)
            assertEquals(300, IdPhotoPresets.REFERENCE_DPI)
        }

        val checkerboard = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
        for (y in 0 until checkerboard.height) {
            for (x in 0 until checkerboard.width) {
                checkerboard.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
            }
        }
        try {
            val scaled = IdPhotoExporter.cropForPreset(checkerboard, IdPhotoPresets.STANDARD_2x2in)
            try {
                assertEquals(600, scaled.width)
                assertEquals(600, scaled.height)
                val midtone = Color.red(scaled.getPixel(300, 300))
                assertTrue("Bilinear downsampling should average checkerboard detail: " + midtone, midtone in 40..215)
                assumeTrue("Scoped-storage API is Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                val saved = IdPhotoExporter.save(context, scaled, IdPhotoPresets.STANDARD_2x2in).getOrThrow()
                try {
                    assertTrue(saved.toString().startsWith("content://"))
                    val jpeg = context.contentResolver.openInputStream(saved)?.use { it.readBytes() }
                        ?: error("Saved ID photo could not be read back.")
                    assertTrue(jpeg.size > 20)
                    assertEquals(0xff.toByte(), jpeg[2])
                    assertEquals(0xe0.toByte(), jpeg[3])
                    assertEquals(1, jpeg[13].toInt())
                    val xDpi = ((jpeg[14].toInt() and 0xff) shl 8) or (jpeg[15].toInt() and 0xff)
                    val yDpi = ((jpeg[16].toInt() and 0xff) shl 8) or (jpeg[17].toInt() and 0xff)
                    assertEquals(300, xDpi)
                    assertEquals(300, yDpi)
                } finally {
                    context.contentResolver.delete(saved, null, null)
                }
            } finally {
                scaled.recycle()
            }
        } finally {
            checkerboard.recycle()
            preferences.write(previousProfile)
        }
    }

    @Test
    fun selectedSmallestProfileControlsIdPhotoPixelsAndJfifDensity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Scoped-storage API is Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val preferences = ResolutionProfilePrefs(context)
        val previousProfile = preferences.read()
        val source = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        var savedUri: android.net.Uri? = null
        try {
            preferences.write(ResolutionProfile.SMALLEST)
            val dpi = preferences.read().outputDpi(
                Runtime.getRuntime().maxMemory(),
                source.width,
                source.height
            )
            assertEquals(150, dpi)
            val cropped = IdPhotoExporter.cropForPreset(
                source,
                IdPhotoPresets.STANDARD_2x2in,
                outputDpi = dpi
            )
            try {
                assertEquals(300, cropped.width)
                assertEquals(300, cropped.height)
                savedUri = IdPhotoExporter.save(
                    context,
                    cropped,
                    IdPhotoPresets.STANDARD_2x2in,
                    dpi
                ).getOrThrow()
                val jpeg = context.contentResolver.openInputStream(savedUri!!)?.use { it.readBytes() }
                    ?: error("Saved profile-sized JPEG could not be read.")
                val xDpi = ((jpeg[14].toInt() and 0xff) shl 8) or (jpeg[15].toInt() and 0xff)
                val yDpi = ((jpeg[16].toInt() and 0xff) shl 8) or (jpeg[17].toInt() and 0xff)
                assertEquals(150, xDpi)
                assertEquals(150, yDpi)
                val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: error("The profile-sized JPEG could not be decoded.")
                try {
                    assertEquals(300, decoded.width)
                    assertEquals(300, decoded.height)
                } finally {
                    decoded.recycle()
                }
            } finally {
                cropped.recycle()
            }
        } finally {
            savedUri?.let { context.contentResolver.delete(it, null, null) }
            preferences.write(previousProfile)
            source.recycle()
        }
    }
}
