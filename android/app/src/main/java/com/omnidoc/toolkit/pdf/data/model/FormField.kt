package com.omnidoc.toolkit.pdf.data.model

import java.io.Serializable

enum class FormFieldType {
    TEXT,
    CHECKBOX,
    RADIO_BUTTON,
    CHOICE,
    SIGNATURE,
    UNKNOWN
}

data class FormFieldItem(
    val fullyQualifiedName: String,
    val partialName: String,
    val value: String,
    val type: FormFieldType,
    val isReadOnly: Boolean = false,
    val isRequired: Boolean = false,
    val pageIndex: Int = 0,
    val boundingBox: FieldBounds? = null
) : Serializable

data class FieldBounds(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float
) : Serializable
