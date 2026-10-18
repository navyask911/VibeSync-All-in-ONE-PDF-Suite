package com.vibesync.toolkit.pdf.data.pdf

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiscoveredPdf(
    val id: Long,
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val lastModified: Long,
    val formattedDate: String
) : Serializable

/**
 * High-performance, non-blocking MediaStore scanner that indexes local PDFs on device.
 * Runs on Dispatchers.IO to maintain 60/120 FPS UI smoothness.
 */
class DevicePdfScanner(private val context: Context) {

    suspend fun scanDevicePdfs(): List<DiscoveredPdf> = withContext(Dispatchers.IO) {
        val pdfList = mutableListOf<DiscoveredPdf>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        // Select PDFs either by official mime type or by .pdf file extension
        val selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?)"
        val selectionArgs = arrayOf("application/pdf", "%.pdf")
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Untitled.pdf"
                    val sizeBytes = cursor.getLong(sizeColumn)
                    val dateModifiedSeconds = cursor.getLong(dateColumn)

                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val sizeFormatted = formatFileSize(sizeBytes)
                    val formattedDate = dateFormat.format(Date(dateModifiedSeconds * 1000L))

                    pdfList.add(
                        DiscoveredPdf(
                            id = id,
                            name = name,
                            uri = contentUri,
                            sizeBytes = sizeBytes,
                            sizeFormatted = sizeFormatted,
                            lastModified = dateModifiedSeconds,
                            formattedDate = formattedDate
                        )
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore for PDF documents", e)
        }

        pdfList
    }

    /**
     * Checks whether the app currently has permission to scan external storage.
     */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val permission = android.Manifest.permission.READ_EXTERNAL_STORAGE
            context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    companion object {
        private const val TAG = "DevicePdfScanner"
    }
}
