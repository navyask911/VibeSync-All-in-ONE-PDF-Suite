package com.vibesync.toolkit.pdf.core.security

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Clean-room hardware-backed cryptographic vault utilizing Android Jetpack Security.
 * Stores digital signatures and preferences in EncryptedSharedPreferences and protects
 * vaulted document bytes with an AES-256-GCM key held by Android Keystore.
 */
class SecurityVault(context: Context) {

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Encrypts a file to an authenticated AES-256-GCM container. The key never leaves
     * Android Keystore; a versioned header and random IV are authenticated as AAD.
     * The destination is published only after the complete ciphertext and GCM tag are written.
     */
    fun encryptFile(
        sourceFile: File,
        encryptedDestination: File,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        require(sourceFile.isFile) { "Vault source file does not exist." }
        require(sourceFile.canonicalFile != encryptedDestination.canonicalFile) {
            "Vault source and destination must be different files."
        }
        val cipher = Cipher.getInstance(VAULT_FILE_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, vaultFileKey())
        val iv = cipher.iv ?: throw IOException("Android Keystore did not provide an AES-GCM IV.")
        require(iv.size == GCM_IV_BYTES) { "Unexpected AES-GCM IV length." }
        cipher.updateAAD(VAULT_FILE_MAGIC)

        writeAtomically(encryptedDestination) { temporary ->
            FileOutputStream(temporary).use { output ->
                output.write(VAULT_FILE_MAGIC)
                output.write(iv)
                FileInputStream(sourceFile).use { input ->
                    transformStream(input, output, cipher, sourceFile.length(), onProgress)
                }
                output.fd.sync()
            }
        }
    }

    /**
     * Authenticates and decrypts a vaulted file. Plaintext is staged in a sibling temporary
     * file and is not exposed at the destination unless the GCM authentication tag is valid.
     */
    fun decryptFile(
        encryptedSource: File,
        decryptedDestination: File,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        require(encryptedSource.isFile) { "Encrypted vault file does not exist." }
        require(encryptedSource.canonicalFile != decryptedDestination.canonicalFile) {
            "Vault source and destination must be different files."
        }
        writeAtomically(decryptedDestination) { temporary ->
            FileInputStream(encryptedSource).use { input ->
                val magic = ByteArray(VAULT_FILE_MAGIC.size)
                readFully(input, magic)
                if (!magic.contentEquals(VAULT_FILE_MAGIC)) {
                    throw IOException("The vault file format is unsupported or corrupted.")
                }
                val iv = ByteArray(GCM_IV_BYTES)
                readFully(input, iv)
                val cipher = Cipher.getInstance(VAULT_FILE_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, vaultFileKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(VAULT_FILE_MAGIC)

                FileOutputStream(temporary).use { output ->
                    val payloadBytes = (encryptedSource.length() - VAULT_FILE_MAGIC.size - GCM_IV_BYTES).coerceAtLeast(1L)
                    transformStream(input, output, cipher, payloadBytes, onProgress)
                    output.fd.sync()
                }
            }
        }
    }

    private fun vaultFileKey(): SecretKey = synchronized(VAULT_FILE_KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(VAULT_FILE_KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    VAULT_FILE_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun transformStream(
        input: InputStream,
        output: OutputStream,
        cipher: Cipher,
        totalBytes: Long,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit
    ) {
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var processed = 0L
        onProgress(0L, totalBytes)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            val transformed = cipher.update(buffer, 0, count)
            if (transformed != null && transformed.isNotEmpty()) output.write(transformed)
            processed += count.toLong()
            onProgress(processed.coerceAtMost(totalBytes), totalBytes)
        }
        val finalBytes = cipher.doFinal()
        if (finalBytes.isNotEmpty()) output.write(finalBytes)
        onProgress(totalBytes, totalBytes)
    }

    private fun readFully(input: InputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = input.read(destination, offset, destination.size - offset)
            if (count < 0) throw IOException("The vault file ended unexpectedly.")
            offset += count
        }
    }

    private fun writeAtomically(destination: File, writer: (File) -> Unit) {
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("Vault file destination has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not prepare the vault file directory.")
        }
        val temporary = File.createTempFile("vault_crypto_", ".partial", parent)
        try {
            writer(temporary)
            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace the previous vault output.")
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                if (!temporary.delete()) temporary.deleteOnExit()
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    /**
     * Stores a user's signature bitmap encoded as encrypted AES-256 Base64 string.
     */
    fun saveSignature(signatureId: String, bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val byteArray = stream.toByteArray()
        val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)

        encryptedPrefs.edit()
            .putString("${KEY_SIGNATURE_PREFIX}_$signatureId", base64String)
            .apply()
    }

    /**
     * Retrieves an encrypted signature bitmap.
     */
    fun getSignature(signatureId: String): Bitmap? {
        val base64String = encryptedPrefs.getString("${KEY_SIGNATURE_PREFIX}_$signatureId", null) ?: return null
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /** Lists saved signature identifiers without exposing their encrypted payloads. */
    fun listSignatures(): List<SavedSignature> =
        encryptedPrefs.all.keys
            .asSequence()
            .filter { it.startsWith("$KEY_SIGNATURE_PREFIX" + "_") }
            .map { SavedSignature(it.removePrefix("$KEY_SIGNATURE_PREFIX" + "_")) }
            .filter { it.id.isNotBlank() }
            .sortedBy { it.id }
            .toList()

    /** Renames a saved signature while keeping its bitmap encrypted at rest. */
    fun renameSignature(signatureId: String, newSignatureId: String): Boolean {
        val oldId = signatureId.trim()
        val newId = newSignatureId.trim()
        if (oldId.isBlank() || newId.isBlank() || oldId == newId) return false
        val oldKey = "${KEY_SIGNATURE_PREFIX}_$oldId"
        val newKey = "${KEY_SIGNATURE_PREFIX}_$newId"
        val encoded = encryptedPrefs.getString(oldKey, null) ?: return false
        if (encryptedPrefs.contains(newKey)) return false
        return encryptedPrefs.edit().putString(newKey, encoded).remove(oldKey).commit()
    }

    /** Deletes one saved signature; other encrypted entries are untouched. */
    fun deleteSignature(signatureId: String): Boolean {
        val key = "${KEY_SIGNATURE_PREFIX}_${signatureId.trim()}"
        if (!encryptedPrefs.contains(key)) return false
        return encryptedPrefs.edit().remove(key).commit()
    }

    /** Checks if a default signature exists in the encrypted vault. */
    fun hasDefaultSignature(): Boolean = encryptedPrefs.contains("${KEY_SIGNATURE_PREFIX}_default")

    /** Stores the default user signature. */
    fun saveDefaultSignature(bitmap: Bitmap) {
        saveSignature("default", bitmap)
    }

    /** Retrieves default user signature. */
    fun getDefaultSignature(): Bitmap? = getSignature("default")

    /** Clears all vaulted signatures. */
    fun clearSignatures() {
        val editor = encryptedPrefs.edit()
        encryptedPrefs.all.keys
            .filter { it.startsWith(KEY_SIGNATURE_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    data class SavedSignature(val id: String)

    companion object {
        private const val SECURE_PREFS_FILE = "vibesync_encrypted_vault"
        private const val KEY_SIGNATURE_PREFIX = "vault_sig"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val VAULT_FILE_KEY_ALIAS = "vibesync_vault_file_aes_v1"
        private const val VAULT_FILE_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private val VAULT_FILE_MAGIC = "VSVLT001".toByteArray(Charsets.US_ASCII)
        private val VAULT_FILE_KEY_LOCK = Any()
    }
}
