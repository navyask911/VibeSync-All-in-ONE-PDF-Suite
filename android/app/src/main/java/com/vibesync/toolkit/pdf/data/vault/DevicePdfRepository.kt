package com.vibesync.toolkit.pdf.data.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

data class DevicePdfDocument(
    val uri: Uri,
    val displayName: String,
    val mimeType: String = "application/pdf"
)

/** Indexes PDFs below a user-selected SAF tree without broad storage permissions. */
object DevicePdfRepository {
    private const val MAX_DEPTH = 12
    private const val MAX_RESULTS = 2_000

    fun listPdfDocuments(context: Context, treeUri: Uri): List<DevicePdfDocument> {
        val resolver = context.contentResolver
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val results = LinkedHashMap<String, DevicePdfDocument>()
        val visitedDirectories = HashSet<String>()

        fun visit(parentDocumentId: String, depth: Int) {
            if (depth > MAX_DEPTH || results.size >= MAX_RESULTS) return
            if (!visitedDirectories.add(parentDocumentId)) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            val projection = arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE
            )
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext() && results.size < MAX_RESULTS) {
                    val id = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn).orEmpty()
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    if (mime == Document.MIME_TYPE_DIR) {
                        visit(id, depth + 1)
                    } else if (mime.equals("application/pdf", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)) {
                        results[uri.toString()] = DevicePdfDocument(uri, name.ifBlank { "document.pdf" })
                    }
                }
            }
        }

        visit(rootDocumentId, 0)
        return results.values.sortedBy { it.displayName.lowercase() }
    }
}
