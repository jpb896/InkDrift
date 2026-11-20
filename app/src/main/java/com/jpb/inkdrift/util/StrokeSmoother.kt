package com.jpb.inkdrift.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.ceil
import kotlin.math.hypot

fun DrawScope.drawSmoothPressureStroke(stroke: FreehandStroke) {
    val points = stroke.points
    if (points.isEmpty()) return

    // Single dot tap
    if (points.size == 1) {
        val p = points[0]
        drawCircle(
            color = stroke.color,
            radius = (stroke.baseWidth * p.pressure) / 2f,
            center = p.position
        )
        return
    }

    // Iterate through points using midpoints as Quadratic Bezier anchors
    var prevMid = points[0].position
    var prevPressure = points[0].pressure

    for (i in 1 until points.size) {
        val current = points[i]
        val nextMid = if (i == points.size - 1) {
            current.position
        } else {
            lerp(points[i - 1].position, current.position, 0.5f)
        }
        val nextPressure = if (i == points.size - 1) {
            current.pressure
        } else {
            (points[i - 1].pressure + current.pressure) / 2f
        }

        // Control point is points[i - 1].position
        drawQuadraticPressureSegment(
            p0 = prevMid,
            p1 = points[i - 1].position,
            p2 = nextMid,
            pressure0 = prevPressure,
            pressure2 = nextPressure,
            baseWidth = stroke.baseWidth,
            color = stroke.color
        )

        prevMid = nextMid
        prevPressure = nextPressure
    }
}

private fun DrawScope.drawQuadraticPressureSegment(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    pressure0: Float,
    pressure2: Float,
    baseWidth: Float,
    color: Color
) {
    // Estimate curve length to determine step count (prevents gaps on fast strokes)
    val dist = hypot(p1.x - p0.x, p1.y - p0.y) + hypot(p2.x - p1.x, p2.y - p1.y)
    val steps = ceil(dist / 1.5f).toInt().coerceAtLeast(1)

    for (step in 0..steps) {
        val t = step.toFloat() / steps
        val invT = 1f - t

        // Quadratic Bezier formula: B(t) = (1-t)^2 * P0 + 2(1-t)t * P1 + t^2 * P2
        val x = (invT * invT * p0.x) + (2f * invT * t * p1.x) + (t * t * p2.x)
        val y = (invT * invT * p0.y) + (2f * invT * t * p1.y) + (t * t * p2.y)

        // Smoothly interpolate pressure along the segment
        val interpolatedPressure = pressure0 + (pressure2 - pressure0) * t
        val radius = (baseWidth * interpolatedPressure) / 2f

        drawCircle(
            color = color,
            radius = radius.coerceAtLeast(0.5f),
            center = Offset(x, y)
        )
    }
}