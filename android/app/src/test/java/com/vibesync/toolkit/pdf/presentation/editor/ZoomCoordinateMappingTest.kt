package com.vibesync.toolkit.pdf.presentation.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
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
}
