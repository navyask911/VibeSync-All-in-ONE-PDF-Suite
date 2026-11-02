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
    fun getHardwareBindingId(): String?
    fun setHardwareBindingId(v: String)
    fun getProcessedTokens(): Set<String>
    fun addProcessedToken(token: String)
    fun clear()

    /** Legacy consumed-purchase storage retained for v1.0.13 migration. */
    fun persistGrant(expiry: Long, fingerprint: String, token: String) {
        setExpiry(expiry)
        setFingerprint(fingerprint)
        addProcessedToken(token)
    }

    /** Atomically stores a non-consumable entitlement and its device binding. */
    fun persistNonConsumableGrant(expiry: Long, fingerprint: String, hardwareBindingId: String, token: String) {
        setExpiry(expiry)
        setFingerprint(fingerprint)
        setHardwareBindingId(hardwareBindingId)
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
    override fun setExpiry(v: Long) { preferences.edit().putLong(KEY_EXPIRY, v).apply() }
    override fun getFingerprint(): String? = preferences.getString(KEY_FINGERPRINT, null)
    override fun setFingerprint(v: String) { preferences.edit().putString(KEY_FINGERPRINT, v).apply() }
    override fun getHardwareBindingId(): String? = preferences.getString(KEY_HARDWARE_BINDING_ID, null)
    override fun setHardwareBindingId(v: String) {
        check(preferences.edit().putString(KEY_HARDWARE_BINDING_ID, v).commit()) {
            "Could not persist the Pro Pass device binding."
        }
    }

    override fun getProcessedTokens(): Set<String> =
        preferences.getStringSet(KEY_PROCESSED_TOKENS, emptySet())?.toSet() ?: emptySet()

    override fun addProcessedToken(token: String) {
        val tokens = getProcessedTokens().toMutableSet().apply { add(token) }
        preferences.edit().putStringSet(KEY_PROCESSED_TOKENS, tokens).apply()
    }

    override fun persistGrant(expiry: Long, fingerprint: String, token: String) {
        val tokens = getProcessedTokens().toMutableSet().apply { add(token) }
        check(preferences.edit()
            .putLong(KEY_EXPIRY, expiry)
            .putString(KEY_FINGERPRINT, fingerprint)
            .putStringSet(KEY_PROCESSED_TOKENS, tokens)
            .commit()) { "Could not persist the legacy Pro Pass entitlement." }
    }

    override fun persistNonConsumableGrant(expiry: Long, fingerprint: String, hardwareBindingId: String, token: String) {
        val tokens = getProcessedTokens().toMutableSet().apply { add(token) }
        check(preferences.edit()
            .putLong(KEY_EXPIRY, expiry)
            .putString(KEY_FINGERPRINT, fingerprint)
            .putString(KEY_HARDWARE_BINDING_ID, hardwareBindingId)
            .putStringSet(KEY_PROCESSED_TOKENS, tokens)
            .commit()) { "Could not persist the Pro Pass entitlement." }
    }

    override fun clear() {
        preferences.edit().remove(KEY_EXPIRY).remove(KEY_FINGERPRINT)
            .remove(KEY_HARDWARE_BINDING_ID).remove(KEY_PROCESSED_TOKENS).apply()
    }

    private companion object {
        const val PREFERENCE_FILE = "vibesync_encrypted_vault"
        const val KEY_EXPIRY = "billing_pro_expiry_epoch_millis"
        const val KEY_FINGERPRINT = "billing_pro_device_fingerprint"
        const val KEY_HARDWARE_BINDING_ID = "billing_pro_hardware_binding_uuid"
        const val KEY_PROCESSED_TOKENS = "billing_pro_processed_purchase_tokens"
    }
}

class InMemoryBillingPreferences : BillingPreferences {
    private var expiry = 0L
    private var fingerprint: String? = null
    private var hardwareBindingId: String? = null
    private val processedTokens = mutableSetOf<String>()

    @Synchronized override fun getExpiry(): Long = expiry
    @Synchronized override fun setExpiry(v: Long) { expiry = v }
    @Synchronized override fun getFingerprint(): String? = fingerprint
    @Synchronized override fun setFingerprint(v: String) { fingerprint = v }
    @Synchronized override fun getHardwareBindingId(): String? = hardwareBindingId
    @Synchronized override fun setHardwareBindingId(v: String) { hardwareBindingId = v }
    @Synchronized override fun getProcessedTokens(): Set<String> = processedTokens.toSet()
    @Synchronized override fun addProcessedToken(token: String) { processedTokens.add(token) }

    @Synchronized override fun persistGrant(expiry: Long, fingerprint: String, token: String) {
        this.expiry = expiry
        this.fingerprint = fingerprint
        processedTokens.add(token)
    }

    @Synchronized override fun persistNonConsumableGrant(expiry: Long, fingerprint: String, hardwareBindingId: String, token: String) {
        this.expiry = expiry
        this.fingerprint = fingerprint
        this.hardwareBindingId = hardwareBindingId
        processedTokens.add(token)
    }

    @Synchronized override fun clear() {
        expiry = 0L
        fingerprint = null
        hardwareBindingId = null
        processedTokens.clear()
    }
}
