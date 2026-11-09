package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PdfScannerGroup(
    val parentFolderName: String,
    val pdfs: List<DiscoveredPdf>
)

/**
 * Keeps a user-managed set of SAF tree grants and scans only those folders.
 * No broad or all-files storage permission is requested.
 */
class PdfScannerRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val folderScanner = SafPdfFolderScanner(appContext)

    /**
     * Persist read access before recording a folder. A false result means the provider
     * did not grant a persistable read permission and the tree is not saved.
     */
    fun addFolder(treeUri: Uri): Boolean {
        if (treeUri.scheme != "content") return false
        return try {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val isPersisted = appContext.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission
            }
            if (!isPersisted) return false

            val saved = storedFolderUris().toMutableSet()
            saved.add(treeUri.toString())
            preferences.edit().putStringSet(KEY_FOLDER_TREE_URIS, saved).apply()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /** Returns only saved trees for which Android still reports a persisted read grant. */
    fun getGrantedFolderUris(): List<Uri> {
        val persisted = runCatching {
            appContext.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
                .toSet()
        }.getOrDefault(emptySet())

        val saved = storedFolderUris()
        val valid = saved.filter { it in persisted }.distinct().sorted()
        if (valid.toSet() != saved) {
            preferences.edit().putStringSet(KEY_FOLDER_TREE_URIS, valid.toSet()).apply()
        }
        return valid.map(Uri::parse)
    }

    /** Recursively scans each granted tree and groups deduplicated PDFs by their parent folder. */
    suspend fun scanGrantedFolders(): List<PdfScannerGroup> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DiscoveredPdf>()
        for (treeUri in getGrantedFolderUris()) {
            currentCoroutineContext().ensureActive()
            try {
                discovered += folderScanner.scanFolder(treeUri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                // A provider can revoke access between reading persisted grants and traversal.
            } catch (_: IllegalArgumentException) {
                // Ignore a malformed or no-longer-resolvable tree without losing other results.
            }
        }
        groupPdfScanResults(discovered)
    }

    private fun storedFolderUris(): Set<String> {
        val result = preferences.getStringSet(KEY_FOLDER_TREE_URIS, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

        // Migrate the existing single-folder dashboard grant when it is still persisted.
        val legacy = appContext.getSharedPreferences(
            LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).getString(LEGACY_SELECTED_TREE_URI, null)
        if (!legacy.isNullOrBlank()) {
            val stillGranted = runCatching {
                appContext.contentResolver.persistedUriPermissions.any {
                    it.uri.toString() == legacy && it.isReadPermission
                }
            }.getOrDefault(false)
            if (stillGranted && result.add(legacy)) {
                preferences.edit().putStringSet(KEY_FOLDER_TREE_URIS, result).apply()
            }
        }
        return result
    }

    companion object {
        private const val PREFERENCES_NAME = "smart_pdf_scanner_folders"
        private const val KEY_FOLDER_TREE_URIS = "folder_tree_uris"
        private const val LEGACY_PREFERENCES_NAME = "document_picker_preferences"
        private const val LEGACY_SELECTED_TREE_URI = "selected_pdf_tree_uri"
    }
}

/** Pure grouping helper: shared by the repository and JVM regression tests. */
internal fun groupPdfScanResults(pdfs: List<DiscoveredPdf>): List<PdfScannerGroup> =
    pdfs.distinctBy { it.uri.toString() }
        .groupBy { pdf -> pdf.parentFolderName.ifBlank { "Other" } }
        .map { (folderName, files) ->
            PdfScannerGroup(
                parentFolderName = folderName,
                pdfs = files.sortedWith(
                    compareByDescending<DiscoveredPdf> { it.lastModified }
                        .thenBy { it.name.lowercase() }
                )
            )
        }
        .sortedBy { it.parentFolderName.lowercase() }
