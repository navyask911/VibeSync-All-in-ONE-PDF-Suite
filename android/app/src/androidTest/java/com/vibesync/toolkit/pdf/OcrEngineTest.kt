package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import com.vibesync.toolkit.pdf.data.ocr.MlKitOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class OcrEngineTest {
    @Test
    fun fixtureImageWithHelloWorldReturnsRecognizedTextAndBlockBounds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File.createTempFile("hello-world-ocr-", ".png", context.cacheDir)
        val sourceBitmap = Bitmap.createBitmap(1200, 360, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sourceBitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 116f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            canvas.drawText("Hello World", 72f, 215f, paint)
            FileOutputStream(fixture).use { output ->
                assertTrue("PNG fixture should be written", sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val bitmap = BitmapFactory.decodeFile(fixture.absolutePath)
                ?: error("PNG fixture could not be decoded")
            try {
                val result = MlKitOcrEngine(context.applicationContext).use { it.recognize(bitmap) }
                val normalized = result.text.lowercase()
                assertTrue("OCR should recognize Hello; got: ${result.text}", normalized.contains("hello"))
                assertTrue("OCR should recognize World; got: ${result.text}", normalized.contains("world"))
                assertTrue("OCR must return at least one block", result.blocks.isNotEmpty())
                assertTrue("Text block bounds should be reported", result.blocks.any { it.boundingBox != null })
            } finally {
                bitmap.recycle()
            }
        } finally {
            sourceBitmap.recycle()
            fixture.delete()
        }
    }

    @Test
    fun resultModelCanRepresentAnEmptyImageResultWithoutNetworkDependencies() {
        val result = com.vibesync.toolkit.pdf.data.ocr.OcrResult("", emptyList())
        assertTrue(result.text.isEmpty())
        assertTrue(result.blocks.isEmpty())
        assertTrue(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName.startsWith("com.vibesync")
        )
    }
}
