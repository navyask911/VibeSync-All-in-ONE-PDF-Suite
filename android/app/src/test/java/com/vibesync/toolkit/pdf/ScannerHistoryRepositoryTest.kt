package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.presentation.scanner.ScannerHistoryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ScannerHistoryRepositoryTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "scan-history-test-" + System.nanoTime()).apply { mkdirs() }
    private val scans = File(root, "scans").apply { mkdirs() }
    private val repository = ScannerHistoryRepository(scans)

    @AfterEach
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun listsOnlySavedPdfScansNewestFirst() {
        val older = File(scans, "Scan_old.pdf").apply { writeBytes(byteArrayOf(1)); setLastModified(1000L) }
        val newer = File(scans, "Scan_new.pdf").apply { writeBytes(byteArrayOf(1)); setLastModified(2000L) }
        File(scans, "Scan_new_page_1.jpg").writeBytes(byteArrayOf(2))
        File(scans, "not_a_pdf.txt").writeText("ignore")
        File(scans, "scan_work_interrupted.pdf").writeBytes(byteArrayOf(3))

        assertEquals(listOf(newer, older), repository.listScanFiles())
    }

    @Test
    fun renameMovesPdfAndAllPageImagesTogether() {
        val original = File(scans, "Scan_123.pdf").apply { writeBytes(byteArrayOf(1, 2)) }
        val pageOne = File(scans, "Scan_123_page_1.jpg").apply { writeBytes(byteArrayOf(3)) }
        val pageTwo = File(scans, "Scan_123_page_2.jpg").apply { writeBytes(byteArrayOf(4)) }

        val renamed = repository.renameScan(original, "  Invoice April  ").getOrThrow()

        assertEquals("Invoice April.pdf", renamed.name)
        assertTrue(renamed.isFile)
        assertFalse(original.exists())
        assertTrue(File(scans, "Invoice April_page_1.jpg").exists())
        assertTrue(File(scans, "Invoice April_page_2.jpg").exists())
        assertFalse(pageOne.exists())
        assertFalse(pageTwo.exists())
    }

    @Test
    fun renameRejectsCollisionsWithoutChangingOriginal() {
        val original = File(scans, "Scan_123.pdf").apply { writeBytes(byteArrayOf(1)) }
        File(scans, "Existing.pdf").writeBytes(byteArrayOf(2))
        val result = repository.renameScan(original, "Existing")
        assertTrue(result.isFailure)
        assertTrue(original.exists())
    }

    @Test
    fun deleteRemovesScanAndPageImages() {
        val scan = File(scans, "Scan_delete.pdf").apply { writeBytes(byteArrayOf(1)) }
        val page = File(scans, "Scan_delete_page_1.jpg").apply { writeBytes(byteArrayOf(2)) }

        repository.deleteScan(scan).getOrThrow()

        assertFalse(scan.exists())
        assertFalse(page.exists())
    }
}
