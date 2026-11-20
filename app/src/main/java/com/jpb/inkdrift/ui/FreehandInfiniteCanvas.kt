package com.jpb.inkdrift.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import com.jpb.inkdrift.util.FreehandStroke
import com.jpb.inkdrift.util.StrokePoint
import com.jpb.inkdrift.util.detectDrawingAndTransformGestures
import com.jpb.inkdrift.util.drawSmoothPressureStroke

@Composable
fun FreehandInfiniteCanvas(
    isPenActive: Boolean = true,
    activeColor: Color = Color(0xFF1E1E1E),
    baseStrokeWidth: Float = 12f
) {
    // Viewport transformation state
    var scale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }

    // Committed strokes & the stroke currently being drawn
    val strokes = remember { mutableStateListOf<FreehandStroke>() }
    val currentPoints = remember { mutableStateListOf<StrokePoint>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF9F9FB)) // Off-white Freeform-style board color
            .pointerInput(isPenActive, activeColor, baseStrokeWidth) {
                detectDrawingAndTransformGestures(
                    isPenActive = isPenActive,
                    getCanvasOffset = { canvasOffset },
                    getScale = { scale },
                    onTransform = { pan, zoom, centroid ->
                        val oldScale = scale
                        val newScale = (scale * zoom).coerceIn(0.15f, 6.0f)

                        // Adjust offset so pinch-to-zoom anchors around the user's fingers
                        val scaleFactor = newScale / oldScale
                        canvasOffset = (canvasOffset - centroid) * scaleFactor + centroid + pan
                        scale = newScale
                    },
                    onStrokeStart = { point ->
                        currentPoints.clear()
                        currentPoints.add(point)
                    },
                    onStrokeMove = { point ->
                        // Filter out micro-jitter (points closer than 1 world unit)
                        val last = currentPoints.lastOrNull()
                        if (last == null || (point.position - last.position).getDistance() > 1f) {
                            currentPoints.add(point)
                        }
                    },
                    onStrokeEnd = {
                        if (currentPoints.isNotEmpty()) {
                            strokes.add(
                                FreehandStroke(
                                    points = currentPoints.toList(),
                                    color = activeColor,
                                    baseWidth = baseStrokeWidth
                                )
                            )
                            currentPoints.clear()
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            withTransform({
                translate(left = canvasOffset.x, top = canvasOffset.y)
                scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
                // 1. Render all completed strokes
                strokes.forEach { stroke ->
                    drawSmoothPressureStroke(stroke)
                }

                // 2. Render the active in-progress stroke in real time
                if (currentPoints.isNotEmpty()) {
                    drawSmoothPressureStroke(
                        FreehandStroke(
                            points = currentPoints,
                            color = activeColor,
                            baseWidth = baseStrokeWidth
                        )
                    )
                }
            }
        }
    }
}