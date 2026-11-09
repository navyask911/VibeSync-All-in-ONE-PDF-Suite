package com.vibesync.toolkit.pdf

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import com.vibesync.toolkit.pdf.data.pdf.DiscoveredPdf
import com.vibesync.toolkit.pdf.data.pdf.groupPdfScanResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PdfScannerRepositoryTest {
    @Test
    fun groupsByImmediateParentAndDeduplicatesByDocumentUri() {
        val downloads = pdf("content://provider/downloads/invoice.pdf", "invoice.pdf", "Download", 10L)
        val whatsapp = pdf("content://provider/whatsapp/chat.pdf", "chat.pdf", "WhatsApp", 20L)
        val duplicate = downloads.copy(name = "duplicate-name.pdf")

        val groups = groupPdfScanResults(listOf(downloads, whatsapp, duplicate))

        assertEquals(listOf("Download", "WhatsApp"), groups.map { it.parentFolderName })
        assertEquals(listOf(downloads.uri), groups[0].pdfs.map { it.uri })
        assertEquals(listOf(whatsapp.uri), groups[1].pdfs.map { it.uri })
    }

    @Test
    fun unnamedParentUsesStableOtherGroupAndNewestFilesComeFirst() {
        val older = pdf("content://provider/1", "older.pdf", "", 1L)
        val newer = pdf("content://provider/2", "newer.pdf", "", 9L)

        val groups = groupPdfScanResults(listOf(older, newer))

        assertEquals("Other", groups.single().parentFolderName)
        assertEquals(listOf(newer.uri, older.uri), groups.single().pdfs.map { it.uri })
    }

    private fun pdf(uri: String, name: String, parent: String, modified: Long): DiscoveredPdf {
        // android.net.Uri methods are unimplemented in local JVM tests; mock only toString(),
        // which is the repository's de-duplication key.
        val documentUri = mockk<Uri>()
        every { documentUri.toString() } returns uri
        return DiscoveredPdf(
            id = uri.hashCode().toLong(),
            name = name,
            uri = documentUri,
            sizeBytes = 100L,
            sizeFormatted = "1 KB",
            lastModified = modified,
            formattedDate = "Nov 9, 2026",
            parentFolderName = parent
        )
    }
}
