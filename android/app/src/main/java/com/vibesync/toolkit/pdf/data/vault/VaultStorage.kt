package com.vibesync.toolkit.pdf.data.vault

import android.content.Context
import java.io.File
import java.io.IOException

/** App-private storage for files hidden from PDF discovery and scanner history. */
class VaultStorage(context: Context) {
    private val scansDirectory = File(context.filesDir, "scans")
    private val vaultDirectory = File(context.filesDir, "vault")

    init {
        ensureDirectory(vaultDirectory)
    }

    fun listVaultFiles(): List<File> =
        ensureDirectory(vaultDirectory)
            .listFiles { file -> file.isFile }
            .orEmpty()
            .sortedBy { it.name.lowercase() }

    /** Scans live here before vaulting; moving a file removes it from this discoverable list. */
    fun listMovableScans(): List<File> =
        scansDirectory.listFiles { file -> file.isFile && file.extension.equals("pdf", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }

    fun moveIntoVault(source: File): File {
        require(source.isFile) { "Source file does not exist." }
        val sourceCanonical = source.canonicalFile
        require(sourceCanonical.parentFile != vaultDirectory.canonicalFile) { "File is already in the vault." }
        ensureDirectory(vaultDirectory)
        val destination = uniqueDestination(File(vaultDirectory, source.name))
        if (sourceCanonical.renameTo(destination)) return destination
        try {
            sourceCanonical.copyTo(destination, overwrite = false)
            if (!sourceCanonical.delete()) {
                destination.delete()
                throw IOException("The source file could not be removed after copying.")
            }
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
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
}
