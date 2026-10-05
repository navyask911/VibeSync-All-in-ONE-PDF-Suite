package com.omnidoc.toolkit.pdf.presentation.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.omnidoc.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary

/**
 * Smooth Bezier Signature Pad.
 * Captures user signatures via quadratic bezier curves, eliminates jitter,
 * and extracts a transparent alpha-channel Bitmap for hardware-backed vaulting.
 */
@Composable
fun SignaturePadDialog(
    onDismissRequest: () -> Unit,
    onSignatureCaptured: (Bitmap) -> Unit
) {
    val points = remember { mutableStateListOf<Offset>() }
    var padWidth by remember { mutableIntStateOf(600) }
    var padHeight by remember { mutableIntStateOf(300) }

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Digital E-Signature",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    "Sign below. Stored securely in Encrypted Vault.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                // The signature drawing area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF8FAFC))
                        .border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(8.dp))
                ) {
                    ComposeCanvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        padWidth = size.width
                                        padHeight = size.height
                                        points.add(offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        points.add(change.position)
                                    }
                                )
                            }
                    ) {
                        if (points.size > 1) {
                            val path = Path().apply {
                                moveTo(points[0].x, points[0].y)
                                for (i in 1 until points.size) {
                                    val prev = points[i - 1]
                                    val curr = points[i]
                                    // Smooth bezier interpolation
                                    quadraticBezierTo(
                                        prev.x,
                                        prev.y,
                                        (prev.x + curr.x) / 2f,
                                        (prev.y + curr.y) / 2f
                                    )
                                }
                            }
                            drawPath(
                                path = path,
                                color = Color.Black,
                                style = Stroke(
                                    width = 4.dp.toPx(),
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    if (points.isEmpty()) {
                        Text(
                            "Sign here with finger or stylus",
                            color = Color.LightGray,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = { points.clear() }) {
                        Text("Clear")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismissRequest) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (points.isNotEmpty()) {
                                    val bitmap = renderToTransparentBitmap(points, padWidth, padHeight)
                                    onSignatureCaptured(bitmap)
                                }
                            },
                            enabled = points.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
                        ) {
                            Text("Apply & Vault")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the vector stroke list onto a transparent Android Bitmap for PDF embedding.
 */
private fun renderToTransparentBitmap(points: List<Offset>, width: Int, height: Int): Bitmap {
    val bitmapWidth = width.coerceAtLeast(1)
    val bitmapHeight = height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint().apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    if (points.size > 1) {
        val path = android.graphics.Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val curr = points[i]
                quadTo(prev.x, prev.y, (prev.x + curr.x) / 2f, (prev.y + curr.y) / 2f)
            }
        }
        canvas.drawPath(path, paint)
    }

    return bitmap
}
