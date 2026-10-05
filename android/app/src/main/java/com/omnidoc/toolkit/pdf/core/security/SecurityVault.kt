package com.omnidoc.toolkit.pdf.core.security

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream

/**
 * Clean-room hardware-backed cryptographic vault utilizing Android Jetpack Security.
 * Stores digital signatures, encrypted preferences, and sensitive encryption keys locally
 * without exposing raw credentials to plaintext storage.
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

    /**
     * Checks if a default signature exists in the encrypted vault.
     */
    fun hasDefaultSignature(): Boolean {
        return encryptedPrefs.contains("${KEY_SIGNATURE_PREFIX}_default")
    }

    /**
     * Stores the default user signature.
     */
    fun saveDefaultSignature(bitmap: Bitmap) {
        saveSignature("default", bitmap)
    }

    /**
     * Retrieves default user signature.
     */
    fun getDefaultSignature(): Bitmap? {
        return getSignature("default")
    }

    /**
     * Clears all vaulted signatures.
     */
    fun clearSignatures() {
        val editor = encryptedPrefs.edit()
        encryptedPrefs.all.keys
            .filter { it.startsWith(KEY_SIGNATURE_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        private const val SECURE_PREFS_FILE = "omnidoc_encrypted_vault"
        private const val KEY_SIGNATURE_PREFIX = "vault_sig"
    }
}
