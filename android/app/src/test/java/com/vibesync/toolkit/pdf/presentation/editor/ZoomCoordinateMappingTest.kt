package com.vibesync.toolkit.pdf.presentation.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomCoordinateMappingTest {
    @Test
    fun caseBOutsideGraphicsLayerUsesCenterOriginInverseTransform() {
        val local = ZoomCoordinateMapping.mapOutsideGraphicsLayer(
            touch = Offset(155f, 90f),
            containerSize = Size(200f, 100f),
            zoomScale = 2f,
            panOffset = Offset(10f, -5f)
        )

        assertEquals(122.5f, local.x, 0.0001f)
        assertEquals(72.5f, local.y, 0.0001f)
    }
    @Test
    fun zoomedScreenPointMapsToIntendedTextRegionNotItsOneXPosition() {
        val normalized = ZoomCoordinateMapping.screenToNormalized(
            touch = Offset(500f, 150f), zoomScale = 2.5f,
            panOffset = Offset(100f, -50f), containerSize = Size(400f, 800f),
            pageRect = Rect(0f, 0f, 400f, 800f)
        )
        assertEquals(0.7f, normalized.x, 0.0001f)
        assertEquals(0.4f, normalized.y, 0.0001f)
        val oneX = ZoomCoordinateMapping.toNormalizedPagePoint(
            Offset(500f, 150f), Size(400f, 800f),
            androidx.compose.ui.unit.IntSize(400, 800)
        )
        assertTrue(kotlin.math.abs(oneX.x - normalized.x) > 0.1f)
    }

    @Test
    fun screenToNormalizedAtOneXWithZeroPanIsIdentity() {
        val normalized = ZoomCoordinateMapping.screenToNormalized(
            touch = Offset(200f, 400f), zoomScale = 1f, panOffset = Offset.Zero,
            containerSize = Size(400f, 800f), pageRect = Rect(0f, 0f, 400f, 800f)
        )
        assertEquals(0.5f, normalized.x, 0.0001f)
        assertEquals(0.5f, normalized.y, 0.0001f)
    }
}
