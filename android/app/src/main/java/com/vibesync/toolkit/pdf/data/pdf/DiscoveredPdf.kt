package com.vibesync.toolkit.pdf.data.pdf

import android.net.Uri
import java.io.Serializable

data class DiscoveredPdf(
    val id: Long,
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val lastModified: Long,
    val formattedDate: String
) : Serializable
