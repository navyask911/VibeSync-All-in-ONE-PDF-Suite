package com.vibesync.toolkit.pdf.core.util

import android.content.Context
import android.content.Intent
import com.vibesync.toolkit.pdf.R
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UriUtils {

    /**
     * Resolves the user-facing display name of a Content Uri.
     */
    fun getFileName(context: Context, uri: Uri): String {
        var name = context.getString(R.string.default_pdf_filename)
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex) ?: name
                    }
                }
            }
        } else if (uri.scheme == "file") {
            uri.path?.let {
                name = File(it).name
            }
        }
        return name
    }

    /**
     * Resolves file byte size from Content Uri.
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long = 0L
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = it.getLong(sizeIndex)
                    }
                }
            }
        } else if (uri.scheme == "file") {
            uri.path?.let {
                size = File(it).length()
            }
        }
        return size
    }

    /**
     * Safely copies an external Content Uri stream into app-internal cache directory
     * so that native tools (PdfBox / PdfRenderer) have immediate, seekable random access.
     */
    fun copyUriToCacheFile(context: Context, uri: Uri, prefix: String = "doc_"): File {
        val cacheDir = File(context.cacheDir, "pdf_cache").apply {
            if (!exists() && !mkdirs()) {
                throw java.io.IOException("Unable to prepare the PDF cache directory.")
            }
        }
        val safePrefix = prefix
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(40)
            .let { if (it.length >= 3) it else it.padEnd(3, '_') }
        val tempFile = File.createTempFile("${safePrefix}_", ".pdf", cacheDir)
        var copiedSuccessfully = false

        try {
            val inputStream: InputStream = when (uri.scheme?.lowercase(Locale.US)) {
                "file" -> FileInputStream(requireNotNull(uri.path) { "File Uri has no path." })
                else -> context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open input stream for the selected document.")
            }

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            copiedSuccessfully = true
            return tempFile
        } finally {
            // A failed or cancelled stream copy must not leave partial PDFs in the cache.
            if (!copiedSuccessfully) tempFile.delete()
        }
    }

    /**
     * Keeps read access to a SAF-selected document/tree across process restarts when the
     * provider grants persistable permissions. PDF processing only needs read access.
     */
    fun persistReadPermission(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return true
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Creates a new cached destination file for export operations.
     */
    fun createExportCacheFile(context: Context, baseName: String, extension: String = ".pdf"): File {
        val cacheDir = File(context.cacheDir, "pdf_cache").apply {
            if (!exists() && !mkdirs()) {
                throw java.io.IOException("Unable to prepare the PDF export directory.")
            }
            if (!isDirectory) throw java.io.IOException("The PDF export cache path is not a directory.")
        }
        // Keep user-entered names inside the private export directory and prevent timestamp collisions.
        val safeBaseName = baseName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(48)
            .ifBlank { "document" }
        val safeExtension = extension.takeIf { Regex("\\.[A-Za-z0-9]{1,8}").matches(it) } ?: ".pdf"
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File.createTempFile("${safeBaseName}_${timeStamp}_", safeExtension, cacheDir)
    }

    /**
     * Converts a local File into a shareable FileProvider Uri.
     */
    fun getShareableUri(context: Context, file: File): Uri {
        val authority = "${context.packageName}.provider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Safely opens a read-only ParcelFileDescriptor for the given Uri.
     */
    fun openFileDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
    }
}
