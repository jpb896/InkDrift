package com.jpb.inkdrift.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Converts a touch coordinate on the physical screen to a point in the infinite canvas world.
 */
fun screenToWorld(screenOffset: Offset, canvasOffset: Offset, scale: Float): Offset {
    return (screenOffset - canvasOffset) / scale
}

suspend fun PointerInputScope.detectDrawingAndTransformGestures(
    isPenActive: Boolean,
    getCanvasOffset: () -> Offset,
    getScale: () -> Float,
    onTransform: (pan: Offset, zoom: Float, centroid: Offset) -> Unit,
    onStrokeStart: (StrokePoint) -> Unit,
    onStrokeMove: (StrokePoint) -> Unit,
    onStrokeEnd: () -> Unit
) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        var isDrawing = false

        // Start drawing immediately if Pen is selected and only 1 pointer is down
        if (isPenActive) {
            isDrawing = true
            val worldPos = screenToWorld(firstDown.position, getCanvasOffset(), getScale())
            val pressure = firstDown.pressure.coerceIn(0.1f, 1.0f)
            onStrokeStart(StrokePoint(worldPos, pressure))
            firstDown.consume()
        }

        do {
            val event = awaitPointerEvent()
            val activePointers = event.changes.filter { it.pressed }

            // If a second finger touches down mid-stroke, cancel/finish the stroke and switch to pan/zoom
            if (activePointers.size > 1) {
                if (isDrawing) {
                    isDrawing = false
                    onStrokeEnd()
                }
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                // Find centroid for natural pinch-to-zoom focal point
                val centroid = activePointers.map { it.position }
                    .reduce { acc, offset -> acc + offset } / activePointers.size.toFloat()

                onTransform(pan, zoom, centroid)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (activePointers.size == 1) {
                val change = activePointers.first()
                if (isDrawing) {
                    // Process historical points for ultra-smooth high-refresh-rate stylus input
                    change.historical.forEach { historical ->
                        val histWorldPos = screenToWorld(historical.position, getCanvasOffset(), getScale())
                        // Historical events share the current change's pressure in Compose
                        val pressure = change.pressure.coerceIn(0.1f, 1.0f)
                        onStrokeMove(StrokePoint(histWorldPos, pressure))
                    }

                    val worldPos = screenToWorld(change.position, getCanvasOffset(), getScale())
                    val pressure = change.pressure.coerceIn(0.1f, 1.0f)
                    onStrokeMove(StrokePoint(worldPos, pressure))
                    change.consume()
                } else if (!isPenActive) {
                    // Single-finger panning when Select/Hand tool is active
                    val pan = event.calculatePan()
                    onTransform(pan, 1f, change.position)
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })

        if (isDrawing) {
            onStrokeEnd()
        }
    }
}