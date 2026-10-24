package com.vibesync.toolkit.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResolutionProfilePrefsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsToStandardOnFirstLaunch() {
        assertEquals(ResolutionProfile.STANDARD, ResolutionProfilePrefs(context).read())
    }

    @Test
    fun writesAndReadsSelectedProfile() {
        val prefs = ResolutionProfilePrefs(context)
        prefs.write(ResolutionProfile.LARGEST)
        assertEquals(ResolutionProfile.LARGEST, prefs.read())
    }

    @Test
    fun selectedProfileSurvivesRecreatingPreferenceAccessor() {
        ResolutionProfilePrefs(context).write(ResolutionProfile.HD_AUTO)
        assertEquals(ResolutionProfile.HD_AUTO, ResolutionProfilePrefs(context).read())
    }

    @Test
    fun unknownStoredValueFallsBackToStandard() {
        context.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit().putString(ResolutionProfilePrefs.KEY_RESOLUTION_PROFILE, "NOT_A_PROFILE").commit()
        assertEquals(ResolutionProfile.STANDARD, ResolutionProfilePrefs(context).read())
    }
}
