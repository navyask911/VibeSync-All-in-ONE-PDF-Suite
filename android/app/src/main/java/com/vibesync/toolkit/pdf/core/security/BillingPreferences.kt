package com.vibesync.toolkit.pdf.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface BillingPreferences {
    fun getExpiry(): Long
    fun setExpiry(v: Long)
    fun getFingerprint(): String?
    fun setFingerprint(v: String)
    fun getProcessedTokens(): Set<String>
    fun addProcessedToken(token: String)
    fun clear()

    /** Stores entitlement fields together so a successfully consumed token is recorded atomically. */
    fun persistGrant(expiry: Long, fingerprint: String, token: String) {
        setExpiry(expiry)
        setFingerprint(fingerprint)
        addProcessedToken(token)
    }
}

class EncryptedBillingPreferences(context: Context) : BillingPreferences {
    private val preferences: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFERENCE_FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun getExpiry(): Long = preferences.getLong(KEY_EXPIRY, 0L)

    override fun setExpiry(v: Long) {
        preferences.edit().putLong(KEY_EXPIRY, v).apply()
    }

    override fun getFingerprint(): String? = preferences.getString(KEY_FINGERPRINT, null)

    override fun setFingerprint(v: String) {
        preferences.edit().putString(KEY_FINGERPRINT, v).apply()
    }

    override fun getProcessedTokens(): Set<String> =
        preferences.getStringSet(KEY_PROCESSED_TOKENS, emptySet())?.toSet() ?: emptySet()

    override fun addProcessedToken(token: String) {
        val tokens = getProcessedTokens().toMutableSet().apply { add(token) }
        preferences.edit().putStringSet(KEY_PROCESSED_TOKENS, tokens).apply()
    }

    override fun persistGrant(expiry: Long, fingerprint: String, token: String) {
        val tokens = getProcessedTokens().toMutableSet().apply { add(token) }
        preferences.edit()
            .putLong(KEY_EXPIRY, expiry)
            .putString(KEY_FINGERPRINT, fingerprint)
            .putStringSet(KEY_PROCESSED_TOKENS, tokens)
            .commit()
    }

    override fun clear() {
        preferences.edit()
            .remove(KEY_EXPIRY)
            .remove(KEY_FINGERPRINT)
            .remove(KEY_PROCESSED_TOKENS)
            .apply()
    }

    private companion object {
        const val PREFERENCE_FILE = "vibesync_encrypted_vault"
        const val KEY_EXPIRY = "billing_pro_expiry_epoch_millis"
        const val KEY_FINGERPRINT = "billing_pro_device_fingerprint"
        const val KEY_PROCESSED_TOKENS = "billing_pro_processed_purchase_tokens"
    }
}

class InMemoryBillingPreferences : BillingPreferences {
    private var expiry = 0L
    private var fingerprint: String? = null
    private val processedTokens = mutableSetOf<String>()

    @Synchronized
    override fun getExpiry(): Long = expiry

    @Synchronized
    override fun setExpiry(v: Long) {
        expiry = v
    }

    @Synchronized
    override fun getFingerprint(): String? = fingerprint

    @Synchronized
    override fun setFingerprint(v: String) {
        fingerprint = v
    }

    @Synchronized
    override fun getProcessedTokens(): Set<String> = processedTokens.toSet()

    @Synchronized
    override fun addProcessedToken(token: String) {
        processedTokens.add(token)
    }

    @Synchronized
    override fun persistGrant(expiry: Long, fingerprint: String, token: String) {
        this.expiry = expiry
        this.fingerprint = fingerprint
        processedTokens.add(token)
    }

    @Synchronized
    override fun clear() {
        expiry = 0L
        fingerprint = null
        processedTokens.clear()
    }
}
