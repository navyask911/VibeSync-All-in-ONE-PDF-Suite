package com.vibesync.toolkit.pdf.core.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Shared scoped-storage writes for PDF, JPEG, DOCX and TXT exports. */
object ScopedFileExport {
    fun saveToDownloads(context: Context, sourceFile: File?, sourceUri: Uri?, displayName: String, mimeType: String) {
        require(sourceFile != null || sourceUri != null) { "The generated file is no longer available." }
        val safeName = sanitizeDisplayName(displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, sourceFile, sourceUri, safeName, mimeType)
        } else {
            saveToLegacyDownloads(context, sourceFile, sourceUri, safeName, mimeType)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, sourceFile: File?, sourceUri: Uri?, displayName: String, mimeType: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VibeSync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a Downloads destination.")
        try {
            val output = resolver.openOutputStream(destination, "w")
                ?: throw IOException("Could not open the Downloads destination.")
            output.use { target ->
                openSource(context, sourceFile, sourceUri).use { input -> input.copyTo(target) }
            }
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(destination, published, null, null)
        } catch (error: Exception) {
            resolver.delete(destination, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyDownloads(context: Context, sourceFile: File?, sourceUri: Uri?, displayName: String, mimeType: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Storage permission is required to save files on this Android version.")
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appFolder = File(downloads, "VibeSync")
        if (!appFolder.exists() && !appFolder.mkdirs()) throw IOException("Could not create Downloads/VibeSync.")
        if (!appFolder.isDirectory) throw IOException("Downloads/VibeSync is not a directory.")
        val destination = uniqueFile(File(appFolder, displayName))
        try {
            FileOutputStream(destination).use { target ->
                openSource(context, sourceFile, sourceUri).use { input -> input.copyTo(target) }
            }
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf(mimeType), null)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun openSource(context: Context, sourceFile: File?, sourceUri: Uri?): java.io.InputStream {
        if (sourceFile != null) {
            if (!sourceFile.isFile || sourceFile.length() <= 0L) throw IOException("The generated file is empty.")
            return sourceFile.inputStream()
        }
        return context.contentResolver.openInputStream(requireNotNull(sourceUri))
            ?: throw IOException("Could not read the generated file.")
    }

    private fun sanitizeDisplayName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim().ifBlank { "VibeSync_Export" }.take(120)

    private fun uniqueFile(initial: File): File {
        if (!initial.exists()) return initial
        val base = initial.nameWithoutExtension
        val extension = initial.extension
        var index = 1
        while (true) {
            val candidate = File(initial.parentFile, if (extension.isBlank()) "${base}_${index++}" else "${base}_${index++}.${extension}")
            if (!candidate.exists()) return candidate
        }
    }
}
