package com.vibesync.toolkit.pdf.presentation.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

data class ScanHistoryEntry(
    val file: File,
    val pageCount: Int,
    val scannedAtMillis: Long,
    val pageImageCount: Int
)

/**
 * File-backed scan history. The PDF is canonical; page JPEGs use the
 * <base>_page_<number>.jpg convention beside the PDF in filesDir/scans.
 */
class ScannerHistoryRepository(private val scansDirectory: File) {

    fun listScanFiles(): List<File> {
        if (!scansDirectory.isDirectory) return emptyList()
        return scansDirectory.listFiles { file ->
            file.isFile &&
                file.extension.equals("pdf", ignoreCase = true) &&
                !file.name.startsWith("scan_work_", ignoreCase = true) &&
                file.length() > 0L
        }?.sortedWith(
            compareByDescending<File> { it.lastModified() }.thenBy { it.name.lowercase(Locale.ROOT) }
        ) ?: emptyList()
    }

    fun listEntries(): List<ScanHistoryEntry> = listScanFiles().map { file ->
        ScanHistoryEntry(
            file = file,
            pageCount = readPageCount(file),
            scannedAtMillis = file.lastModified(),
            pageImageCount = pageImageFiles(file).size
        )
    }

    fun pageImageFiles(pdfFile: File): List<File> {
        val prefix = pdfFile.nameWithoutExtension + "_page_"
        return (scansDirectory.listFiles { file ->
            if (!file.isFile || !file.name.startsWith(prefix, ignoreCase = true)) {
                false
            } else {
                val suffix = file.name.removePrefix(prefix).substringBeforeLast('.', missingDelimiterValue = "")
                val extension = file.extension
                suffix.toIntOrNull() != null &&
                    (extension.equals("jpg", true) || extension.equals("jpeg", true)) &&
                    file.length() > 0L
            }
        } ?: emptyArray()).sortedBy { file ->
            file.name.removePrefix(prefix).substringBeforeLast('.').toIntOrNull() ?: Int.MAX_VALUE
        }
    }

    fun isManagedScanUri(uri: android.net.Uri): Boolean {
        if (!uri.scheme.equals("file", ignoreCase = true)) return false
        val path = uri.path ?: return false
        return runCatching { File(path).canonicalFile.parentFile == scansDirectory.canonicalFile }.getOrDefault(false)
    }

    fun fileFromUri(uri: android.net.Uri): File? {
        if (!isManagedScanUri(uri)) return null
        return runCatching { File(requireNotNull(uri.path)).canonicalFile }
            .getOrNull()
            ?.takeIf { it.isFile && it.extension.equals("pdf", true) }
    }

    fun renameScan(source: File, requestedName: String): Result<File> = runCatching {
        val sourceFile = requireScanFile(source)
        val rawName = requestedName.trim().removeSuffixIgnoreCase(".pdf").trim()
        val safeName = rawName
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it in "-_()." }
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_')
            .take(80)
        require(safeName.isNotBlank()) { "Enter a valid document name." }

        val destination = File(scansDirectory, safeName + ".pdf")
        if (destination.canonicalFile == sourceFile.canonicalFile) return@runCatching sourceFile
        if (destination.exists()) throw IOException("A scan with that name already exists.")

        val oldModified = sourceFile.lastModified()
        val prefix = sourceFile.nameWithoutExtension + "_page_"
        val imageMoves = pageImageFiles(sourceFile).map { image ->
            val pageNumber = image.name.removePrefix(prefix).substringBeforeLast('.')
            image to File(scansDirectory, safeName + "_page_" + pageNumber + "." + image.extension)
        }
        if (imageMoves.any { (_, target) -> target.exists() }) {
            throw IOException("A page image with that name already exists.")
        }

        val completedMoves = mutableListOf<Pair<File, File>>()
        try {
            imageMoves.forEach { (oldFile, newFile) ->
                if (!oldFile.renameTo(newFile)) throw IOException("Could not rename a scanned page image.")
                completedMoves.add(oldFile to newFile)
            }
            if (!sourceFile.renameTo(destination)) throw IOException("Could not rename the scanned PDF.")
        } catch (error: Exception) {
            completedMoves.asReversed().forEach { (oldFile, newFile) ->
                if (newFile.exists()) newFile.renameTo(oldFile)
            }
            throw error
        }
        destination.setLastModified(oldModified)
        destination
    }

    fun deleteScan(source: File): Result<Unit> = runCatching {
        val sourceFile = requireScanFile(source)
        val pageFiles = pageImageFiles(sourceFile)
        if (!sourceFile.delete()) throw IOException("Could not delete the scanned PDF.")
        val undeleted = pageFiles.filter { it.exists() && !it.delete() }
        if (undeleted.isNotEmpty()) {
            Log.w(TAG, "Some page images could not be deleted for " + sourceFile.name)
            throw IOException("The PDF was deleted, but one or more page images could not be removed.")
        }
        Unit
    }

    fun readPageCount(pdfFile: File): Int = runCatching {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
        }
    }.getOrDefault(0)

    /** Loads a small preview from the first retained JPEG or renders page one from the PDF. */
    fun loadThumbnail(pdfFile: File): Bitmap? {
        val firstImage = pageImageFiles(pdfFile).firstOrNull()
        if (firstImage != null) {
            decodeThumbnail(firstImage)?.let { return it }
        }

        return runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use rendererUse@ { renderer ->
                    if (renderer.pageCount == 0) return@rendererUse null
                    renderer.openPage(0).use { page ->
                        val targetWidth = 360
                        val targetHeight =
                            (targetWidth * page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f))
                                .toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            val pageMatrix = Matrix().apply {
                                postScale(
                                    targetWidth.toFloat() / page.width.toFloat().coerceAtLeast(1f),
                                    targetHeight.toFloat() / page.height.toFloat().coerceAtLeast(1f)
                                )
                            }
                            page.render(
                                bitmap,
                                Rect(0, 0, targetWidth, targetHeight),
                                pageMatrix,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Shares original page JPEGs when they are complete. If a legacy/incomplete scan has no
     * complete page-image set, renders all pages into app-private cache exports.
     */
    fun exportPageImagesForSharing(pdfFile: File, cacheDirectory: File): Result<List<File>> = runCatching {
        val file = requireScanFile(pdfFile)
        val pageCount = readPageCount(file)
        if (pageCount <= 0) throw IOException("Could not read pages from this scan.")
        val retainedImages = pageImageFiles(file)
        if (retainedImages.size == pageCount) return@runCatching retainedImages

        val outputDirectory = File(cacheDirectory, "scan_page_exports")
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw IOException("Could not prepare the temporary page-image export directory.")
        }
        val safePrefix = file.nameWithoutExtension
            .filter { it.isLetterOrDigit() || it in "-_" }
            .take(24)
            .ifBlank { "scan" }
        val generatedFiles = mutableListOf<File>()
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val scale = minOf(2.0f, 1600f / page.width.toFloat().coerceAtLeast(1f))
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                val pageMatrix = Matrix().apply {
                                    postScale(
                                        width.toFloat() / page.width.toFloat().coerceAtLeast(1f),
                                        height.toFloat() / page.height.toFloat().coerceAtLeast(1f)
                                    )
                                }
                                page.render(bitmap, Rect(0, 0, width, height), pageMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val output = File.createTempFile(
                                    safePrefix + "_p" + (pageIndex + 1) + "_",
                                    ".jpg",
                                    outputDirectory
                                )
                                FileOutputStream(output).use { stream ->
                                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                                        throw IOException("Could not export page " + (pageIndex + 1) + " as JPEG.")
                                    }
                                }
                                generatedFiles.add(output)
                            } finally {
                                if (!bitmap.isRecycled) bitmap.recycle()
                            }
                        }
                    }
                }
            }
            generatedFiles
        } catch (error: Exception) {
            generatedFiles.forEach { it.delete() }
            throw error
        }
    }

    private fun decodeThumbnail(imageFile: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 480 || bounds.outHeight / sampleSize > 640) {
            sampleSize *= 2
        }
        BitmapFactory.decodeFile(
            imageFile.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        )
    }.getOrNull()

    private fun requireScanFile(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.parentFile == scansDirectory.canonicalFile) { "Only saved scans can be managed here." }
        require(canonical.extension.equals("pdf", true) && canonical.isFile) { "The saved scan no longer exists." }
        return canonical
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private companion object {
        const val TAG = "ScannerHistoryRepository"
    }
}
