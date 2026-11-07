package com.vibesync.toolkit.pdf.data.vault

import android.content.Context
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import java.io.File
import java.io.IOException

/**
 * App-private encrypted storage for documents hidden from PDF discovery and scanner history.
 * The biometric-gated VaultScreen calls decrypt methods only after authentication succeeds.
 */
class VaultStorage(context: Context) {
    private val appContext = context.applicationContext
    private val scansDirectory = File(appContext.filesDir, "scans")
    private val vaultDirectory = File(appContext.filesDir, "vault")
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
        }.orEmpty().sortedBy { it.name.lowercase() }
    }

    /** Scans live here before vaulting; moving a file removes it from this discoverable list. */
    fun listMovableScans(): List<File> =
        scansDirectory.listFiles { file -> file.isFile && file.extension.equals("pdf", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }

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
        val destination = File.createTempFile("vault_open_", ".pdf", decryptedCacheDirectory)
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
        val base = initial.nameWithoutExtension
        val extension = initial.extension.let { if (it.isBlank()) "" else ".$it" }
        var index = 1
        while (true) {
            val candidate = File(initial.parentFile, "${base}_${index++}$extension")
            if (!candidate.exists()) return candidate
        }
    }

    private fun ensureDirectory(directory: File): File {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create app-private vault storage.")
        if (!directory.isDirectory) throw IOException("Vault storage path is not a directory.")
        return directory
    }

    companion object {
        private const val ENCRYPTED_FILE_SUFFIX = ".vlt"
        private const val DECRYPTED_FILE_PREFIX = "vault_open_"
        private const val SECURITY_VAULT_TEMP_PREFIX = "vault_crypto_"
    }
}
