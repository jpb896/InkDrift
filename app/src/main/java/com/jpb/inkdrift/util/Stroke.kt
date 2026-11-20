package com.jpb.inkdrift.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.util.UUID

data class StrokePoint(val position: Offset, val pressure: Float)

data class FreehandStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<StrokePoint>,
    val color: Color,
    val baseWidth: Float
)

typealias CanvasStrokePoint = StrokePoint
typealias CanvasStroke = FreehandStroke

// Raw storable point using primitives
data class StorableStrokePoint(val x: Float, val y: Float, val pressure: Float)

// Raw storable stroke using Long for color and Float for width
data class StorableStroke(
    val id: String,
    val points: List<StorableStrokePoint>,
    val colorValue: Long,
    val baseWidth: Float
)

// Convert UI Stroke to Storable DTO
fun FreehandStroke.toStorable(): StorableStroke = StorableStroke(
    id = id,
    points = points.map { StorableStrokePoint(it.position.x, it.position.y, it.pressure) },
    colorValue = color.value.toLong(),
    baseWidth = baseWidth
)

// Convert Storable DTO back to UI Stroke
fun StorableStroke.toCanvasStroke(): FreehandStroke = FreehandStroke(
    id = id,
    points = points.map { StrokePoint(Offset(it.x, it.y), it.pressure) },
    color = Color(colorValue.toULong()),
    baseWidth = baseWidth
)
