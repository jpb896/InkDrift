package com.jpb.inkdrift.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.util.UUID

data class StrokePoint(
    val position: Offset, // Stored in Infinite World Coordinates
    val pressure: Float   // Normalized 0.0f to 1.0f
)

data class FreehandStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<StrokePoint>,
    val color: Color,
    val baseWidth: Float
)