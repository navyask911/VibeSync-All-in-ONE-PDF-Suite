package com.omnidoc.toolkit.pdf.core.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
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
        var name = "document.pdf"
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
        val cacheDir = File(context.cacheDir, "pdf_cache").apply { if (!exists()) mkdirs() }
        val tempFile = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.pdf")
        
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot open input stream for Uri: $uri")

        return tempFile
    }

    /**
     * Creates a new cached destination file for export operations.
     */
    fun createExportCacheFile(context: Context, baseName: String, extension: String = ".pdf"): File {
        val cacheDir = File(context.cacheDir, "pdf_cache").apply { if (!exists()) mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(cacheDir, "${baseName}_$timeStamp$extension")
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
