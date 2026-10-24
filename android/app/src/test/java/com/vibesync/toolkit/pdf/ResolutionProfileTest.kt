package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ResolutionProfileTest {
    private val mb = 1024L * 1024L

    @Test
    fun smallestA4TargetsAreWithinTwoPixelsAndUsesQuality70() {
        val spec = ResolutionProfile.SMALLEST.spec(512 * mb, 1240, 1754)
        assertEquals(1240, spec.targetWidthPx)
        assertEquals(1754, spec.targetHeightPx)
        assertEquals(70, spec.jpegQuality)
        assertFalse(spec.applyUnsharpMask)
    }

    @Test
    fun standardA4IsExactAndUsesQuality88() {
        val spec = ResolutionProfile.STANDARD.spec(512 * mb, 2480, 3508)
        assertEquals(2480, spec.targetWidthPx)
        assertEquals(3508, spec.targetHeightPx)
        assertEquals(88, spec.jpegQuality)
        assertFalse(spec.applyUnsharpMask)
    }

    @Test
    fun largestKeepsNativeDimensionsAndUsesQuality96() {
        val spec = ResolutionProfile.LARGEST.spec(1024 * mb, 2400, 3400)
        assertNull(spec.targetWidthPx)
        assertNull(spec.targetHeightPx)
        assertEquals(96, spec.jpegQuality)
        assertFalse(spec.fellBack)
        assertFalse(spec.applyUnsharpMask)
    }

    @Test
    fun largestFallsBackToStandardWhenSourceExceedsQuarterHeapBudget() {
        val spec = ResolutionProfile.LARGEST.spec(256 * mb, 12000, 12000)
        val standard = ResolutionProfile.STANDARD.spec(256 * mb, 12000, 12000)
        assertTrue(spec.fellBack)
        assertEquals(88, spec.jpegQuality)
        assertEquals(standard.targetWidthPx, spec.targetWidthPx)
        assertEquals(standard.targetHeightPx, spec.targetHeightPx)
    }

    @Test
    fun hdAutoChoosesStandardBelow256MiB() {
        val spec = ResolutionProfile.HD_AUTO.spec(255 * mb, 1000, 1400)
        assertEquals(88, spec.jpegQuality)
        assertFalse(spec.applyUnsharpMask)
        assertFalse(spec.fellBack)
    }

    @Test
    fun hdAutoUsesLargestTierAt256And768MiB() {
        listOf(256L, 768L).forEach { memoryMb ->
            val spec = ResolutionProfile.HD_AUTO.spec(memoryMb * mb, 1000, 1400)
            assertNull(spec.targetWidthPx)
            assertNull(spec.targetHeightPx)
            assertEquals(95, spec.jpegQuality)
            assertTrue(spec.applyUnsharpMask)
        }
    }

    @Test
    fun hdAutoUsesQuality96Above768MiBAndEnablesSharpening() {
        val spec = ResolutionProfile.HD_AUTO.spec(769 * mb, 1000, 1400)
        assertEquals(96, spec.jpegQuality)
        assertTrue(spec.applyUnsharpMask)
        assertNull(spec.targetWidthPx)
        assertNull(spec.targetHeightPx)
    }

    @Test
    fun nonA4AspectRatioIsPreservedForSmallestAndStandard() {
        val sourceWidth = 1600
        val sourceHeight = 900
        val small = ResolutionProfile.SMALLEST.spec(512 * mb, sourceWidth, sourceHeight)
        val standard = ResolutionProfile.STANDARD.spec(512 * mb, sourceWidth, sourceHeight)
        assertAspectRatio(sourceWidth, sourceHeight, small.targetWidthPx!!, small.targetHeightPx!!)
        assertAspectRatio(sourceWidth, sourceHeight, standard.targetWidthPx!!, standard.targetHeightPx!!)
    }

    @Test
    fun largestMemoryFallbackDoesNotChangeHdAutoUnsharpTierSignalAt256MiB() {
        val spec = ResolutionProfile.HD_AUTO.spec(256 * mb, 12000, 12000)
        assertTrue(spec.fellBack)
        assertEquals(95, spec.jpegQuality)
        assertTrue(spec.applyUnsharpMask)
    }

    private fun assertAspectRatio(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int) {
        val sourceRatio = sourceWidth.toDouble() / sourceHeight.toDouble()
        val outputRatio = outputWidth.toDouble() / outputHeight.toDouble()
        assertTrue(abs(sourceRatio - outputRatio) < 0.002, "Expected aspect ratio $sourceRatio but got $outputRatio")
    }
}
