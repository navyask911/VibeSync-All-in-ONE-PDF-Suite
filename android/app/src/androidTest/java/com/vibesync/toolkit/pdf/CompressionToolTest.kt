package com.vibesync.toolkit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.pdf.PdfCompressor
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import com.vibesync.toolkit.pdf.presentation.tools.PdfQuickActionScreen
import kotlinx.coroutines.runBlocking
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
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
    val composeRule = createComposeRule()

    @Test
    fun syntheticFivePagePdfRoundTripsAllProfilesAndPreservesPageCount() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
            val expectedRotations = PDDocument.load(input).use { source ->
                (0 until source.numberOfPages).map { index -> source.getPage(index).rotation }
            }
            ResolutionProfile.values().forEach { profile ->
                assertEquals("Page count must remain five for $profile", 5, reports.getValue(profile).pageCount)
                PDDocument.load(reports.getValue(profile).outputFile).use { outputDocument ->
                    assertEquals(5, outputDocument.numberOfPages)
                    assertEquals(
                        "Page rotations and order must be preserved for $profile",
                        expectedRotations,
                        (0 until outputDocument.numberOfPages).map { index -> outputDocument.getPage(index).rotation }
                    )
                }
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
        val context = ApplicationProvider.getApplicationContext<Context>()
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
    fun compressionUiShowsSizeAndDumpsSemanticsTree() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "compression-ui-" + System.nanoTime()).apply { mkdirs() }
        val input = syntheticPdf(work, protect = false)
        var compressedOutput: File? = null
        composeRule.setContent {
            VibeSyncPdfTheme {
                PdfQuickActionScreen(
                    mode = "compress",
                    initialUri = Uri.fromFile(input),
                    onNavigateBack = {},
                    onSuccess = { compressedOutput = it }
                )
            }
        }
        composeRule.onNodeWithTag("compression_run").performClick()
        composeRule.waitUntil(180_000) {
            composeRule.onAllNodesWithTag("compression_before_size").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("compression_after_size").fetchSemanticsNodes().isNotEmpty()
        }

        // Keep the formatter's exact-string check in the separate JVM test. This UI test only
        // verifies the visible unit and emits the unmerged semantic subtree for diagnostics.
        val beforeSizeNodes = composeRule.onAllNodesWithTag(
            "compression_before_size",
            useUnmergedTree = true
        )
        val beforeSizeTree = beforeSizeNodes.printToString(maxDepth = Int.MAX_VALUE)
        println("compression_size_text_actual:\n$beforeSizeTree")
        if (beforeSizeTree.contains("There were 0 nodes found!")) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("full_tree")
        }
        composeRule.onNodeWithTag("compression_before_size", useUnmergedTree = true)
            .assertExists()
            .assertIsDisplayed()
            .assertTextContains("MB", substring = true)

        composeRule.onNodeWithTag("compression_after_size").assertIsDisplayed()
        composeRule.onNodeWithTag("compression_size_change").assertIsDisplayed()
        composeRule.onNodeWithTag("compression_use_output").performClick()
        composeRule.waitUntil(10_000) { compressedOutput?.isFile == true }
        requireNotNull(compressedOutput).delete()
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
                page.rotation = if (it == 0) 90 else 0
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

    companion object {
        private const val USER_PASSWORD = "vibesync-user"
        private const val OWNER_PASSWORD = "vibesync-owner"
    }
}
