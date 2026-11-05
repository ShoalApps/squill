package io.github.shoalapps.squill.notes

/** Pick the topmost stroke, allowing a little room around thin lines. */
fun Sketch.strokeAt(x: Float, y: Float, reach: Float): Int? =
    strokes.indices.reversed().firstOrNull { strokes[it].touches(x, y, reach) }

fun Sketch.moveStroke(index: Int, dx: Float, dy: Float): Sketch =
    copy(strokes = strokes.mapIndexed { i, s -> if (i == index) s.moved(dx, dy) else s })

fun Sketch.deleteStroke(index: Int): Sketch = copy(strokes = strokes.filterIndexed { i, _ -> i != index })

/** Erase whole strokes along a drag, including the gaps between pointer events. */
fun Sketch.eraseStrokes(x0: Float, y0: Float, x1: Float, y1: Float, reach: Float): Sketch =
    copy(strokes = strokes.filterNot { s ->
        val limit = reach + s.size / 2f
        (0 until s.count).any { i ->
            val j = minOf(i + 1, s.count - 1)
            segmentDistance(s.x(i), s.y(i), s.x(j), s.y(j), x0, y0, x1, y1) <= limit
        }
    })

private fun segmentDistance(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
    fun cross(x: Float, y: Float, u: Float, v: Float) = x * v - y * u
    val denominator = cross(bx - ax, by - ay, dx - cx, dy - cy)
    if (denominator != 0f) {
        val t = cross(cx - ax, cy - ay, dx - cx, dy - cy) / denominator
        val u = cross(cx - ax, cy - ay, bx - ax, by - ay) / denominator
        if (t in 0f..1f && u in 0f..1f) return 0f
    }
    fun point(px: Float, py: Float, x: Float, y: Float, xx: Float, yy: Float): Float {
        val vx = xx - x; val vy = yy - y
        val length = vx * vx + vy * vy
        val t = if (length == 0f) 0f else (((px - x) * vx + (py - y) * vy) / length).coerceIn(0f, 1f)
        return kotlin.math.hypot(px - x - t * vx, py - y - t * vy)
    }
    return minOf(point(ax, ay, cx, cy, dx, dy), point(bx, by, cx, cy, dx, dy), point(cx, cy, ax, ay, bx, by), point(dx, dy, ax, ay, bx, by))
}
