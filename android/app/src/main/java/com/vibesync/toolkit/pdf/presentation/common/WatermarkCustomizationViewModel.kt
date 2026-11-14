package com.vibesync.toolkit.pdf.presentation.common

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Owns custom-watermark preference writes. Entitlement is checked again at save time rather
 * than trusting a UI flag or a previously displayed customization surface.
 */
class WatermarkCustomizationViewModel(
    context: Context,
    private val hasActiveProEntitlement: () -> Boolean
) : ViewModel() {

    private val preferences = context.applicationContext.getSharedPreferences(
        WATERMARK_PREFERENCES,
        Context.MODE_PRIVATE
    )

    fun saveCustomWatermark(text: String, opacity: Float, angleDegrees: Float): Boolean {
        if (!hasActiveProEntitlement() || text.isBlank()) return false

        return preferences.edit()
            .putString(WATERMARK_TEXT_KEY, text.trim())
            .putFloat(WATERMARK_OPACITY_KEY, opacity.coerceIn(0f, 1f))
            .putFloat(WATERMARK_ANGLE_KEY, angleDegrees.coerceIn(0f, 90f))
            .putBoolean(WATERMARK_ENABLED_KEY, true)
            .commit()
    }

    class Factory(
        private val context: Context,
        private val hasActiveProEntitlement: () -> Boolean
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WatermarkCustomizationViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return WatermarkCustomizationViewModel(
                context.applicationContext,
                hasActiveProEntitlement
            ) as T
        }
    }

    private companion object {
        const val WATERMARK_PREFERENCES = "pdf_watermark_options"
        const val WATERMARK_TEXT_KEY = "watermark_text"
        const val WATERMARK_OPACITY_KEY = "watermark_opacity"
        const val WATERMARK_ANGLE_KEY = "watermark_angle"
        const val WATERMARK_ENABLED_KEY = "watermark_custom_enabled"
    }
}
