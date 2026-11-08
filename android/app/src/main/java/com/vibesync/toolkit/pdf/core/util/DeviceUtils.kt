package com.vibesync.toolkit.pdf.core.util

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

object DeviceUtils {
    fun fingerprint(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: ""
        return MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
