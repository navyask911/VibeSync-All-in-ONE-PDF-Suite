package com.vibesync.toolkit.pdf

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.pdf.PdfCompressor
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import com.vibesync.toolkit.pdf.presentation.tools.PdfQuickActionScreen
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

class CompressionToolTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun syntheticFivePagePdfRoundTripsAllProfilesAndPreservesPageCount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "compression-profiles-" + System.nanoTime()).apply { mkdirs() }
        val input = syntheticPdf(work, protect = false)
        val compressor = PdfCompressor(context)
        val outputs = mutableListOf<File>()
        try {
            val reports = ResolutionProfile.values().associateWith { profile ->
                val output = File(work, profile.name.lowercase() + ".pdf")
                outputs += output
                compressor.compress(Uri.fromFile(input), output, profile, maxMemoryBytes = 512L * 1024L * 1024L).getOrThrow()
            }
            ResolutionProfile.values().forEach { profile ->
                assertEquals("Page count must remain five for $profile", 5, reports.getValue(profile).pageCount)
                PDDocument.load(reports.getValue(profile).outputFile).use { assertEquals(5, it.numberOfPages) }
            }
            assertTrue(
                "SMALLEST output must be smaller than STANDARD",
                reports.getValue(ResolutionProfile.SMALLEST).afterBytes < reports.getValue(ResolutionProfile.STANDARD).afterBytes
            )
            assertTrue(
                "STANDARD output must be smaller than LARGEST",
                reports.getValue(ResolutionProfile.STANDARD).afterBytes < reports.getValue(ResolutionProfile.LARGEST).afterBytes
            )
            val constrained = File(work, "hd-auto-constrained.pdf")
            outputs += constrained
            val hdAutoReport = compressor.compress(
                Uri.fromFile(input), constrained, ResolutionProfile.HD_AUTO,
                maxMemoryBytes = 128L * 1024L * 1024L
            ).getOrThrow()
            assertTrue(
                "Constrained HD_AUTO output must be between SMALLEST and LARGEST",
                hdAutoReport.afterBytes >= reports.getValue(ResolutionProfile.SMALLEST).afterBytes &&
                    hdAutoReport.afterBytes <= reports.getValue(ResolutionProfile.LARGEST).afterBytes
            )
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun passwordProtectedPdfRequiresPasswordAndCanBeCompressedWithIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "compression-password-" + System.nanoTime()).apply { mkdirs() }
        val source = syntheticPdf(work, protect = true)
        val compressor = PdfCompressor(context)
        val noPasswordOutput = File(work, "no-password.pdf")
        val validPasswordOutput = File(work, "with-password.pdf")
        try {
            val noPassword = compressor.compress(Uri.fromFile(source), noPasswordOutput, ResolutionProfile.STANDARD)
            assertTrue("Encrypted PDF must fail without a password", noPassword.isFailure)
            assertFalse(noPasswordOutput.exists())
            val withPassword = compressor.compress(
                Uri.fromFile(source), validPasswordOutput, ResolutionProfile.STANDARD, password = USER_PASSWORD,
                maxMemoryBytes = 256L * 1024L * 1024L
            )
            assertTrue(withPassword.exceptionOrNull()?.message.orEmpty(), withPassword.isSuccess)
            assertEquals(5, withPassword.getOrThrow().pageCount)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun compressionUiShowsAccurateBeforeAndAfterSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "compression-ui-" + System.nanoTime()).apply { mkdirs() }
        val input = syntheticPdf(work, protect = false)
        setTestContent {
            PdfQuickActionScreen(
                mode = "compress",
                initialUri = Uri.fromFile(input),
                onNavigateBack = {},
                onSuccess = {}
            )
        }
        composeRule.onNodeWithTag("compression_run").performClick()
        composeRule.waitUntil(180_000) {
            composeRule.onAllNodesWithTag("compression_before_size").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("compression_after_size").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("compression_before_size").assertIsDisplayed()
        composeRule.onNodeWithTag("compression_after_size").assertIsDisplayed()
        composeRule.onNodeWithTag("compression_size_change").assertIsDisplayed()
        work.deleteRecursively()
    }

    private fun syntheticPdf(directory: File, protect: Boolean): File {
        val output = File(directory, if (protect) "protected-source.pdf" else "source.pdf")
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val random = Random(1234L)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val grain = random.nextInt(64)
                val red = (x * 255 / bitmap.width + grain).coerceAtMost(255)
                val green = (y * 255 / bitmap.height + grain).coerceAtMost(255)
                val blue = ((x + y) * 255 / (bitmap.width + bitmap.height) + grain).coerceAtMost(255)
                bitmap.setPixel(x, y, android.graphics.Color.rgb(red, green, blue))
            }
        }
        val jpegStream = ByteArrayOutputStream()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, jpegStream))
        } finally {
            bitmap.recycle()
        }
        PDDocument().use { document ->
            repeat(5) {
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                val image = PDImageXObject.createFromByteArray(document, jpegStream.toByteArray(), "fixture")
                PDPageContentStream(document, page).use { it.drawImage(image, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height) }
            }
            if (protect) {
                val policy = StandardProtectionPolicy(OWNER_PASSWORD, USER_PASSWORD, AccessPermission())
                policy.setEncryptionKeyLength(128)
                document.protect(policy)
            }
            document.save(output)
        }
        return output
    }

    private fun setTestContent(content: @Composable () -> Unit) {
        composeRule.activity.runOnUiThread {
            val composeView = findComposeView(composeRule.activity.window.decorView)
                ?: error("MainActivity ComposeView was not found")
            composeView.setContent { VibeSyncPdfTheme { content() } }
        }
        composeRule.waitForIdle()
    }

    private fun findComposeView(view: View): ComposeView? {
        if (view is ComposeView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    companion object {
        private const val USER_PASSWORD = "vibesync-user"
        private const val OWNER_PASSWORD = "vibesync-owner"
    }
}
