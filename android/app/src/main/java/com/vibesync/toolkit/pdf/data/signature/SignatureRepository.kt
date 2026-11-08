package com.vibesync.toolkit.pdf.data.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * File-backed signature repository. PNG payloads are encrypted with the app's Android Keystore
 * AES-GCM key; legacy EncryptedSharedPreferences signatures remain readable during migration.
 */
class SignatureRepository(
    context: Context,
    private val securityVault: SecurityVault = SecurityVault(context.applicationContext)
) {
    private val appContext = context.applicationContext
    private val signaturesDirectory = File(appContext.filesDir, "signatures")

    data class SavedSignature(val id: String)

    fun saveSignature(id: String, bitmap: Bitmap): File {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "The signature image is empty."
        }
        val safeId = safeId(id)
        ensureDirectory()
        val destination = File(signaturesDirectory, "$safeId$SIGNATURE_SUFFIX")
        if (destination.exists() || securityVault.listSignatures().any { it.id == safeId }) {
            throw IOException("A signature with that name already exists.")
        }

        val source = File.createTempFile("signature_source_", ".png", appContext.cacheDir)
        try {
            FileOutputStream(source).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Could not encode the signature image.")
                }
                output.fd.sync()
            }
            securityVault.encryptFile(source, destination)
            if (!destination.isFile || destination.length() <= 0L) {
                destination.delete()
                throw IOException("The encrypted signature was not written.")
            }
            return destination
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            source.delete()
        }
    }

    fun listSignatures(): List<SavedSignature> {
        ensureDirectory()
        val encryptedIds = signaturesDirectory.listFiles { file ->
            file.isFile && file.extension == SIGNATURE_EXTENSION && file.length() > 0L
        }.orEmpty().map { it.name.removeSuffix(SIGNATURE_SUFFIX) }
        val legacyIds = securityVault.listSignatures().map { it.id }
        return (encryptedIds + legacyIds).distinct().sortedBy { it.lowercase() }.map(::SavedSignature)
    }

    fun getSignature(id: String): Bitmap? {
        val safeId = safeId(id)
        val encrypted = File(signaturesDirectory, "$safeId$SIGNATURE_SUFFIX")
        if (!encrypted.isFile) return securityVault.getSignature(safeId)

        val plaintext = File.createTempFile("signature_read_", ".png", appContext.cacheDir)
        return try {
            securityVault.decryptFile(encrypted, plaintext)
            BitmapFactory.decodeFile(plaintext.absolutePath)
        } catch (_: Exception) {
            null
        } finally {
            plaintext.delete()
        }
    }

    /** Returns the encrypted on-disk file for verification/debugging; null for legacy entries. */
    fun encryptedFile(id: String): File? =
        File(signaturesDirectory, "${safeId(id)}$SIGNATURE_SUFFIX").takeIf { it.isFile }

    fun renameSignature(oldId: String, newId: String): Boolean {
        val oldSafe = safeId(oldId)
        val newSafe = safeId(newId)
        if (oldSafe == newSafe) return false
        ensureDirectory()
        val oldFile = File(signaturesDirectory, "$oldSafe$SIGNATURE_SUFFIX")
        val newFile = File(signaturesDirectory, "$newSafe$SIGNATURE_SUFFIX")
        if (newFile.exists() || securityVault.listSignatures().any { it.id == newSafe }) return false

        val fileRenamed = oldFile.isFile && oldFile.renameTo(newFile)
        val legacyRenamed = securityVault.renameSignature(oldSafe, newSafe)
        return fileRenamed || legacyRenamed
    }

    fun deleteSignature(id: String): Boolean {
        val safeId = safeId(id)
        val file = File(signaturesDirectory, "$safeId$SIGNATURE_SUFFIX")
        val fileDeleted = file.isFile && file.delete()
        val legacyDeleted = securityVault.deleteSignature(safeId)
        return fileDeleted || legacyDeleted
    }

    private fun ensureDirectory() {
        if (!signaturesDirectory.exists() && !signaturesDirectory.mkdirs()) {
            throw IOException("Could not prepare private signature storage.")
        }
        if (!signaturesDirectory.isDirectory) {
            throw IOException("Signature storage path is not a directory.")
        }
    }

    private fun safeId(value: String): String {
        val normalized = value.trim()
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .trim('_', '-')
            .take(MAX_ID_LENGTH)
        require(normalized.isNotBlank() && normalized != "." && normalized != "..") {
            "Enter a valid signature name."
        }
        return normalized
    }

    private companion object {
        const val SIGNATURE_SUFFIX = ".sig"
        const val SIGNATURE_EXTENSION = "sig"
        const val MAX_ID_LENGTH = 64
    }
}
