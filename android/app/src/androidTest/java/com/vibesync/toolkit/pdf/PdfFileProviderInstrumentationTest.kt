package com.vibesync.toolkit.pdf

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfFileProviderInstrumentationTest {

    @Test
    fun fileProviderOpensEveryAppOwnedPdfOutputRoot() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuredDirectories = listOf(
            File(context.cacheDir, "pdf_cache"),
            File(context.filesDir, "documents"),
            File(context.filesDir, "scans"),
            File(context.filesDir, "VibeSyncScans"),
            File(context.filesDir, "shared_pdfs")
        )

        configuredDirectories.forEachIndexed { index, directory ->
            check(directory.exists() || directory.mkdirs()) {
                "Could not create provider test directory: ${directory.absolutePath}"
            }
            val pdf = File(directory, "provider_root_${index}.pdf")
            try {
                pdf.writeBytes("%PDF-1.4\n% FileProvider test\n".toByteArray(Charsets.US_ASCII))
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    pdf
                )

                assertEquals("content", uri.scheme)
                context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                    assertNotNull("Provider could not open ${pdf.absolutePath}", descriptor)
                }
            } finally {
                pdf.delete()
            }
        }
    }
}
