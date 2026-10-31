package com.vibesync.toolkit.pdf.presentation.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomCoordinateMappingTest {
    @Test
    fun zoomedScreenPointMapsThroughPageLocalCoordinatesWithActiveHelper() {
        val zoomScale = 2.5f
        val panOffset = Offset(100f, -50f)
        val screenTouch = Offset(500f, 150f)

        // A descendant of graphicsLayer receives the inverse-transformed page-local point.
        val localTouch = Offset(
            (screenTouch.x - panOffset.x) / zoomScale,
            (screenTouch.y - panOffset.y) / zoomScale
        )
        val normalized = ZoomCoordinateMapping.toNormalizedPagePoint(
            touch = localTouch,
            containerSize = Size(400f, 800f),
            bitmapSize = IntSize(400, 800)
        )

        assertEquals(0.4f, normalized.x, 0.0001f)
        assertEquals(0.1f, normalized.y, 0.0001f)
    }

    @Test
    fun toNormalizedPagePointAtOneXWithZeroPanIsIdentity() {
        val zoomScale = 1f
        val panOffset = Offset.Zero
        val screenTouch = Offset(200f, 400f)

        val localTouch = Offset(
            (screenTouch.x - panOffset.x) / zoomScale,
            (screenTouch.y - panOffset.y) / zoomScale
        )
        val normalized = ZoomCoordinateMapping.toNormalizedPagePoint(
            touch = localTouch,
            containerSize = Size(400f, 800f),
            bitmapSize = IntSize(400, 800)
        )

        assertEquals(0.5f, normalized.x, 0.0001f)
        assertEquals(0.5f, normalized.y, 0.0001f)
    }
}
