package com.vibesync.toolkit.pdf.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Creates an editable, brand-aligned default filename for a generated export.
 */
fun generateSmartFileName(prefix: String, extension: String): String {
    val safePrefix = prefix
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .trim('_')
        .ifBlank { "VibeSync_Export" }
    val safeExtension = extension.removePrefix(".")
        .filter { it.isLetterOrDigit() }
        .ifBlank { "pdf" }
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "${safePrefix}_${timeStamp}.${safeExtension}"
}
