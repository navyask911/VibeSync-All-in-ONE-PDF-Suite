package com.vibesync.toolkit.pdf.data.vault

import android.content.Context
import android.os.Environment
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque
import java.util.Locale

/**
 * App-private encrypted storage for documents hidden from PDF discovery and scanner history.
 * The biometric-gated VaultScreen calls decrypt methods only after authentication succeeds.
 */
class VaultStorage(context: Context) {
    private val appContext = context.applicationContext
    private val scansDirectory = File(appContext.filesDir, "scans")
    private val documentsDirectory = File(appContext.filesDir, "documents")
    private val vaultDirectory = File(appContext.filesDir, "vault")
    private val pdfCacheDirectory = File(appContext.cacheDir, "pdf_cache")
    private val decryptedCacheDirectory = File(appContext.cacheDir, "pdf_cache")
    private val securityVault by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecurityVault(appContext)
    }

    init {
        ensureDirectory(vaultDirectory)
        clearDecryptedCache()
    }

    /**
     * Migrates legacy plaintext PDFs when the authenticated vault list is requested, then
     * returns only AES-GCM encrypted containers.
     */
    fun listVaultFiles(): List<File> {
        val directory = ensureDirectory(vaultDirectory)
        migrateLegacyPdfFiles(directory)
        return directory.listFiles { file ->
            file.isFile && file.name.endsWith(ENCRYPTED_FILE_SUFFIX)
        }.orEmpty().sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /**
     * Lists app-generated PDFs that may be moved into the vault. It intentionally scans only
     * the app's persistent scan folder, its private PDF export cache, and VibeSync-named folders
     * under the public Downloads/Documents/Pictures locations. It does not index user PDFs.
     */
    fun listMovableDocuments(): List<File> {
        val candidates = mutableListOf<File>()
        candidates += collectPdfFiles(scansDirectory, maxDepth = 0, appExportOnly = false)
        candidates += collectPdfFiles(documentsDirectory, maxDepth = 2, appExportOnly = false)
        candidates += collectPdfFiles(pdfCacheDirectory, maxDepth = 2, appExportOnly = true)

        publicPdfDirectories().forEach { directory ->
            candidates += collectPdfFiles(directory, maxDepth = 1, appExportOnly = true)
        }
        return candidates
            .filter { it.isFile && it.length() > 0L }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /** Compatibility name retained for existing scan-history callers. */
    fun listMovableScans(): List<File> = listMovableDocuments()

    /**
     * Imports a SAF-provided PDF into filesDir/vault. The source is copied in bounded chunks to a
     * temporary cache file, then streamed through AES-256-GCM/Android Keystore into a .vlt file.
     * Progress spans both stages and the unencrypted staging file is deleted on every exit path.
     */
    fun importPdf(
        inputStream: InputStream,
        displayName: String,
        expectedBytes: Long = -1L,
        onProgress: (Float) -> Unit = {}
    ): File {
        val safeName = sanitizePdfName(displayName)
        ensureDirectory(vaultDirectory)
        val staged = File.createTempFile("vault_import_", ".pdf", appContext.cacheDir)
        var destination: File? = null
        try {
            var copied = 0L
            inputStream.use { input ->
                FileOutputStream(staged).use { output ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copied += count.toLong()
                        if (expectedBytes > 0L) {
                            onProgress((copied.toFloat() / expectedBytes.toFloat() * COPY_PROGRESS_WEIGHT)
                                .coerceIn(0f, COPY_PROGRESS_WEIGHT))
                        }
                    }
                    output.fd.sync()
                }
            }
            if (!staged.isFile || staged.length() <= 0L) {
                throw IOException("The selected PDF is empty or could not be read.")
            }
            FileInputStream(staged).use { stagedInput ->
                val header = ByteArray(PDF_HEADER.size)
                var offset = 0
                while (offset < header.size) {
                    val count = stagedInput.read(header, offset, header.size - offset)
                    if (count < 0) break
                    offset += count
                }
                if (!header.contentEquals(PDF_HEADER)) {
                    throw IOException("The selected document is not a valid PDF.")
                }
            }

            onProgress(COPY_PROGRESS_WEIGHT)
            destination = uniqueDestination(File(vaultDirectory, safeName + ENCRYPTED_FILE_SUFFIX))
            securityVault.encryptFile(staged, destination) { processed, total ->
                val encryptionFraction = if (total > 0L) processed.toFloat() / total.toFloat() else 1f
                onProgress((COPY_PROGRESS_WEIGHT + encryptionFraction * ENCRYPT_PROGRESS_WEIGHT)
                    .coerceIn(COPY_PROGRESS_WEIGHT, 1f))
            }
            onProgress(1f)
            return destination
        } catch (error: Exception) {
            destination?.delete()
            throw error
        } finally {
            staged.delete()
        }
    }

    /** Encrypts the source PDF before deleting the original from scanner history. */
    fun moveIntoVault(source: File): File {
        require(source.isFile) { "Source file does not exist." }
        require(source.extension.equals("pdf", ignoreCase = true)) { "Only PDF files can be moved into the vault." }
        val sourceCanonical = source.canonicalFile
        require(sourceCanonical.parentFile != vaultDirectory.canonicalFile) { "File is already in the vault." }
        val destination = uniqueDestination(File(vaultDirectory, source.name + ENCRYPTED_FILE_SUFFIX))
        securityVault.encryptFile(sourceCanonical, destination)
        if (!sourceCanonical.delete()) {
            destination.delete()
            throw IOException("The encrypted copy was created, but the original file could not be removed.")
        }
        return destination
    }

    /**
     * Decrypts a vault entry to the FileProvider-approved private cache directory for viewing
     * or for a subsequent CreateDocument export. Call only after the biometric gate succeeds.
     */
    fun decryptToCache(encryptedFile: File): File {
        val source = requireVaultFile(encryptedFile)
        ensureDirectory(decryptedCacheDirectory)
        val destination = File.createTempFile(DECRYPTED_FILE_PREFIX, ".pdf", decryptedCacheDirectory)
        try {
            securityVault.decryptFile(source, destination)
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    /** Decrypts an authenticated vault entry directly to a user-selected export destination. */
    fun decryptToFile(encryptedFile: File, outputFile: File): File {
        val source = requireVaultFile(encryptedFile)
        require(source.canonicalFile != outputFile.canonicalFile) {
            "Vault source and export destination must be different files."
        }
        securityVault.decryptFile(source, outputFile)
        return outputFile
    }

    /** Friendly PDF name without the private encrypted-container suffix. */
    fun displayName(encryptedFile: File): String =
        requireVaultFile(encryptedFile).name.removeSuffix(ENCRYPTED_FILE_SUFFIX)

    /** Removes plaintext viewing/export staging files when the vault is locked or closed. */
    fun clearDecryptedCache() {
        decryptedCacheDirectory.listFiles { file ->
            file.isFile && (
                file.name.startsWith(DECRYPTED_FILE_PREFIX) ||
                    file.name.startsWith(SECURITY_VAULT_TEMP_PREFIX)
                )
        }.orEmpty().forEach { it.delete() }
    }

    private fun collectPdfFiles(directory: File, maxDepth: Int, appExportOnly: Boolean): List<File> {
        if (!directory.isDirectory) return emptyList()
        val stack = ArrayDeque<Pair<File, Int>>()
        val output = mutableListOf<File>()
        stack.addLast(directory to 0)
        while (stack.isNotEmpty()) {
            val (current, depth) = stack.removeLast()
            current.listFiles().orEmpty().forEach { child ->
                if (child.isDirectory && depth < maxDepth) {
                    stack.addLast(child to depth + 1)
                } else if (child.isFile &&
                    child.extension.equals("pdf", ignoreCase = true) &&
                    child.length() > 0L &&
                    (!appExportOnly || child.name.startsWith(APP_EXPORT_PREFIX, ignoreCase = true)) &&
                    !isTemporaryPdf(child.name)
                ) {
                    output += child
                }
            }
        }
        return output
    }

    private fun publicPdfDirectories(): List<File> {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        @Suppress("DEPRECATION")
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return listOf(
            File(downloads, PUBLIC_APP_FOLDER),
            File(documents, PUBLIC_APP_FOLDER),
            File(pictures, PUBLIC_APP_FOLDER),
            downloads,
            documents
        )
    }

    private fun isTemporaryPdf(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return TEMP_PDF_PREFIXES.any(lower::startsWith)
    }

    private fun sanitizePdfName(name: String): String {
        val base = name.trim().removeSuffixIgnoreCase(".pdf")
            .filter { it.isLetterOrDigit() || it in " _-()." }
            .replace(Regex("\\s+"), "_")
            .trim(' ', '.', '_', '-')
            .take(MAX_FILE_NAME_LENGTH)
            .ifBlank { "Imported_Document" }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else base + ".pdf"
    }

    private fun migrateLegacyPdfFiles(directory: File) {
        val legacyFiles = directory.listFiles { file ->
            file.isFile &&
                file.extension.equals("pdf", ignoreCase = true) &&
                !file.name.endsWith(ENCRYPTED_FILE_SUFFIX)
        }.orEmpty()
        legacyFiles.forEach { legacy ->
            val encrypted = uniqueDestination(File(directory, legacy.name + ENCRYPTED_FILE_SUFFIX))
            securityVault.encryptFile(legacy, encrypted)
            if (!legacy.delete()) {
                encrypted.delete()
                throw IOException("A legacy plaintext vault file could not be removed after encryption.")
            }
        }
    }

    private fun requireVaultFile(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.parentFile == vaultDirectory.canonicalFile) {
            "Only files stored in the private vault can be decrypted."
        }
        require(canonical.isFile && canonical.name.endsWith(ENCRYPTED_FILE_SUFFIX)) {
            "The selected encrypted vault document no longer exists."
        }
        return canonical
    }

    private fun uniqueDestination(initial: File): File {
        if (!initial.exists()) return initial
        val initialPdfName = initial.name.removeSuffix(ENCRYPTED_FILE_SUFFIX)
        val base = initialPdfName.removeSuffixIgnoreCase(".pdf")
        val extension = if (initialPdfName.endsWith(".pdf", ignoreCase = true)) ".pdf" else ""
        var index = 1
        while (true) {
            val candidate = File(initial.parentFile, "${base}_${index++}${extension}${ENCRYPTED_FILE_SUFFIX}")
            if (!candidate.exists()) return candidate
        }
    }

    private fun ensureDirectory(directory: File): File {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create app-private vault storage.")
        if (!directory.isDirectory) throw IOException("Vault storage path is not a directory.")
        return directory
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    companion object {
        private const val ENCRYPTED_FILE_SUFFIX = ".vlt"
        private const val DECRYPTED_FILE_PREFIX = "vault_open_"
        private const val SECURITY_VAULT_TEMP_PREFIX = "vault_crypto_"
        private const val APP_EXPORT_PREFIX = "VibeSync_"
        private const val PUBLIC_APP_FOLDER = "VibeSync"
        private const val MAX_FILE_NAME_LENGTH = 80
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)
        private const val COPY_PROGRESS_WEIGHT = 0.45f
        private const val ENCRYPT_PROGRESS_WEIGHT = 0.55f
        private val TEMP_PDF_PREFIXES = listOf(
            "vault_", "merge_src_", "split_src_", "export_source_", "scan_work_", "print_ready_"
        )
    }
}
