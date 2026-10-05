package com.omnidoc.toolkit.pdf.data.model

import java.io.Serializable

data class PageMetadata(
    val pageIndex: Int,
    val widthPt: Float,
    val heightPt: Float,
    val rotation: Int = 0
) : Serializable {
    val aspectRatio: Float
        get() = if (heightPt > 0) widthPt / heightPt else 1.0f
}

data class DocumentMetadata(
    val fileName: String,
    val uriString: String,
    val pageCount: Int,
    val fileSize: Long,
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val isEncrypted: Boolean = false,
    val allowsPrinting: Boolean = true,
    val allowsCopying: Boolean = true,
    val allowsModifying: Boolean = true
) : Serializable
