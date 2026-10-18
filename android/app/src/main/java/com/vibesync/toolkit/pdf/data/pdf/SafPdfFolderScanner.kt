package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.vibesync.toolkit.pdf.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * SAF folder traversal kept entirely off the main thread. Limits protect against
 * unusually large/deep provider trees and cancellation is checked while walking.
 */
class SafPdfFolderScanner(context: Context) {
    private val appContext = context.applicationContext

    suspend fun scanFolder(treeUri: Uri): List<DiscoveredPdf> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalArgumentException("The selected folder is not accessible.")
        if (!root.isDirectory) {
            throw IllegalArgumentException("Please select a folder, not a file.")
        }

        val stack = ArrayDeque<Pair<DocumentFile, Int>>()
        val results = mutableListOf<DiscoveredPdf>()
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        stack.addLast(root to 0)
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_VISITED_NODES) {
            currentCoroutineContext().ensureActive()
            val (node, depth) = stack.removeLast()
            visited++

            if (node.isDirectory) {
                if (depth >= MAX_DEPTH) continue
                try {
                    node.listFiles().reversed().forEach { child ->
                        stack.addLast(child to (depth + 1))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: SecurityException) {
                    if (node.uri == root.uri) throw e
                    Log.w(TAG, "Skipping an inaccessible subfolder: ${node.uri}", e)
                } catch (e: RuntimeException) {
                    if (node.uri == root.uri) throw e
                    Log.w(TAG, "Skipping a provider subfolder that could not be listed: ${node.uri}", e)
                }
                continue
            }

            val name = node.name.orEmpty()
            val isPdf = node.type.equals("application/pdf", ignoreCase = true) ||
                name.endsWith(".pdf", ignoreCase = true)
            if (!isPdf) continue

            try {
                val size = node.length().coerceAtLeast(0L)
                val modifiedMillis = node.lastModified().coerceAtLeast(0L)
                results += DiscoveredPdf(
                    id = node.uri.toString().hashCode().toLong(),
                    name = name.ifBlank { appContext.getString(R.string.default_pdf_filename) },
                    uri = node.uri,
                    sizeBytes = size,
                    sizeFormatted = formatFileSize(size),
                    lastModified = modifiedMillis / 1000L,
                    formattedDate = if (modifiedMillis > 0L) {
                        dateFormat.format(Date(modifiedMillis))
                    } else {
                        ""
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: SecurityException) {
                Log.w(TAG, "Skipping an inaccessible PDF: ${node.uri}", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Skipping a PDF whose metadata could not be read: ${node.uri}", e)
            }
        }

        results.sortedByDescending { it.lastModified }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
        else String.format(Locale.US, "%.0f KB", kb)
    }

    private companion object {
        const val TAG = "SafPdfFolderScanner"
        const val MAX_DEPTH = 32
        const val MAX_VISITED_NODES = 10_000
    }
}
