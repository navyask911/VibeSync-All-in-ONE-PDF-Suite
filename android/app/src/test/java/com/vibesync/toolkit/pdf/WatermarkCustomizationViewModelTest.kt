package com.vibesync.toolkit.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.presentation.common.WatermarkCustomizationViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatermarkCustomizationViewModelTest {
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences("pdf_watermark_options", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @Test
    fun freeUserCannotPersistCustomWatermarkOptions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = WatermarkCustomizationViewModel(context) { false }

        assertFalse(viewModel.saveCustomWatermark("My custom mark", 0.7f, 30f))
        assertFalse(preferences.getBoolean("watermark_custom_enabled", false))
        assertEquals(null, preferences.getString("watermark_text", null))
    }

    @Test
    fun entitlementIsRecheckedAtSaveTimeAndRevocationBlocksWrite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var proActive = true
        val viewModel = WatermarkCustomizationViewModel(context) { proActive }

        assertTrue(viewModel.saveCustomWatermark("  Pro watermark  ", 1.5f, 110f))
        assertTrue(preferences.getBoolean("watermark_custom_enabled", false))
        assertEquals("Pro watermark", preferences.getString("watermark_text", null))
        assertEquals(1f, preferences.getFloat("watermark_opacity", 0f))
        assertEquals(90f, preferences.getFloat("watermark_angle", 0f))

        proActive = false
        assertFalse(viewModel.saveCustomWatermark("Changed after expiry", 0.3f, 15f))
        assertEquals("Pro watermark", preferences.getString("watermark_text", null))
    }
}
