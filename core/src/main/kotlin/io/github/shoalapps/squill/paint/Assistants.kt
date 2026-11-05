package io.github.shoalapps.squill.paint

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

sealed interface Assistant

data class Point(val x: Float, val y: Float)
data class Ruler(val ax: Float, val ay: Float, val bx: Float, val by: Float) : Assistant
data class Parallel(val ax: Float, val ay: Float, val bx: Float, val by: Float) : Assistant
data class Perspective(val points: List<Point>) : Assistant {
    init { require(points.size in 1..3) }
}

const val MAX_ASSISTANTS = 16

fun assistantHandles(a: Assistant): List<Point> = when (a) {
    is Ruler -> listOf(Point(a.ax, a.ay), Point(a.bx, a.by))
    is Parallel -> listOf(Point(a.ax, a.ay), Point(a.bx, a.by))
    is Perspective -> a.points
}

fun Assistant.movedHandle(i: Int, x: Float, y: Float): Assistant = when (this) {
    is Ruler -> when (i) { 0 -> copy(ax = x, ay = y); 1 -> copy(bx = x, by = y); else -> this }
    is Parallel -> when (i) { 0 -> copy(ax = x, ay = y); 1 -> copy(bx = x, by = y); else -> this }
    is Perspective -> copy(points = points.mapIndexed { n, p -> if (n == i) Point(x, y) else p })
}

fun Assistant.mapHandles(transform: (Point) -> Point): Assistant {
    var result = this
    assistantHandles(this).forEachIndexed { i, p -> val q = transform(p); result = result.movedHandle(i, q.x, q.y) }
    return result
}

fun assistantName(a: Assistant): String = when (a) {
    is Ruler -> "Ruler"
    is Parallel -> "Parallel"
    is Perspective -> "${a.points.size}-point perspective"
}

/** Shared initial layout for both editors. Zero is a ruler, -1 a parallel, 1..3 perspective. */
fun defaultAssistant(kind: Int, width: Int, height: Int): Assistant {
    val w = width.toFloat(); val h = height.toFloat()
    return when (kind) {
        0 -> Ruler(w * .2f, h / 2, w * .8f, h / 2)
        -1 -> Parallel(w * .2f, h / 2, w * .8f, h / 2)
        1 -> Perspective(listOf(Point(w / 2, h / 2)))
        else -> Perspective(listOf(Point(w * .1f, h / 2), Point(w * .9f, h / 2)) + if (kind == 3) listOf(Point(w / 2, h * .1f)) else emptyList())
    }
}

/** Visible segments, extended beyond the handles by [length] in canvas units. */
fun assistantLines(a: Assistant, length: Float): List<Pair<Point, Point>> = when (a) {
    is Perspective -> a.points.flatMap { p ->
        val reach = length + hypot(p.x, p.y)
        (0 until 12).map { i ->
            val angle = i * Math.PI / 6
            p to Point(p.x + cos(angle).toFloat() * reach, p.y + sin(angle).toFloat() * reach)
        }
    }
    else -> {
        val handles = assistantHandles(a); val p = handles[0]; val q = handles[1]
        val dx = q.x - p.x; val dy = q.y - p.y; val d = hypot(dx, dy)
        if (d < 1f) emptyList() else listOf(Point(p.x - dx / d * length, p.y - dy / d * length) to Point(q.x + dx / d * length, q.y + dy / d * length))
    }
}

/** Double precision keeps subtraction, direction scoring and projection finite for huge floats. */
class AssistantSnap(private val guides: List<Assistant>, private val lockDistance: Float) {
    private data class Line(val x: Double, val y: Double, val dx: Double, val dy: Double) {
        fun project(px: Float, py: Float): Pair<Float, Float> {
            val t = (px.toDouble() - x) * dx + (py.toDouble() - y) * dy
            return finite(x + t * dx) to finite(y + t * dy)
        }
    }
    private var start: Point? = null
    private var line: Line? = null
    var locked: Boolean = false
        private set

    private fun candidates(s: Point, tx: Double, ty: Double): List<Line> = buildList {
        fun addLine(x: Float, y: Float, dx: Double, dy: Double) {
            val d = hypot(dx, dy)
            if (x.isFinite() && y.isFinite() && d.isFinite() && d >= 1.0) add(Line(x.toDouble(), y.toDouble(), dx / d, dy / d))
        }
        guides.forEach { a -> when (a) {
            is Ruler -> addLine(a.ax, a.ay, a.bx.toDouble() - a.ax, a.by.toDouble() - a.ay)
            is Parallel -> addLine(s.x, s.y, a.bx.toDouble() - a.ax, a.by.toDouble() - a.ay)
            is Perspective -> a.points.forEach { p ->
                val dx = s.x.toDouble() - p.x; val dy = s.y.toDouble() - p.y
                if (hypot(dx, dy) < 1.0) {
                    val d = hypot(tx, ty)
                    // A placeholder direction also detects that a candidate exists on first feed.
                    if (p.x.isFinite() && p.y.isFinite()) add(Line(p.x.toDouble(), p.y.toDouble(), if (d > 0) tx / d else 1.0, if (d > 0) ty / d else 0.0))
                } else addLine(p.x, p.y, dx, dy)
            }
        } }
    }

    fun feed(x: Float, y: Float): FloatArray {
        val px = finite(x.toDouble()); val py = finite(y.toDouble())
        val s = start ?: Point(px, py).also { start = it }
        if (locked) { val p = line?.project(px, py) ?: (px to py); return floatArrayOf(p.first, p.second) }
        val dx = px.toDouble() - s.x; val dy = py.toDouble() - s.y
        val candidates = candidates(s, dx, dy)
        if (candidates.isEmpty()) { locked = true; return floatArrayOf(px, py) }
        val d = hypot(dx, dy)
        if (d == 0.0 || d < lockDistance.toDouble()) return floatArrayOf()
        line = candidates.maxByOrNull { abs(it.dx * (dx / d) + it.dy * (dy / d)) }
        locked = true
        val first = line!!.project(s.x, s.y); val current = line!!.project(px, py)
        return floatArrayOf(first.first, first.second, current.first, current.second)
    }

    fun end(x: Float, y: Float): Pair<Float, Float> = if (locked) line?.project(finite(x.toDouble()), finite(y.toDouble())) ?: (finite(x.toDouble()) to finite(y.toDouble()))
        else start?.let { it.x to it.y } ?: (finite(x.toDouble()) to finite(y.toDouble()))

    companion object {
        private fun finite(value: Double): Float = if (value.isNaN()) 0f else value.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
    }
}

object AssistantFile {
    const val NAME = "squill-assistants.txt"
    private const val HEADER = "squill-assistants 1"
    fun write(list: List<Assistant>): String = buildString {
        append(HEADER); append('\n')
        list.forEach { a ->
            append(when (a) { is Ruler -> "ruler"; is Parallel -> "parallel"; is Perspective -> "perspective" })
            assistantHandles(a).forEach { append(' '); append(it.x.toString()); append(' '); append(it.y.toString()) }
            append('\n')
        }
    }
    fun parse(text: String): List<Assistant> {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != HEADER) return emptyList()
        return buildList {
            while (lines.hasNext() && size < MAX_ASSISTANTS) {
                val parts = lines.next().trim().split(Regex("\\s+"))
                val kind = parts.firstOrNull()
                val count = parts.size - 1
                if (when (kind) { "ruler", "parallel" -> count != 4; "perspective" -> count !in listOf(2, 4, 6); else -> true }) continue
                val values = parts.drop(1).map { it.toFloatOrNull() }
                if (values.any { it == null || !it.isFinite() || abs(it) > 1_000_000f }) continue
                val v = values.map { it!! }
                add(when (kind) {
                    "ruler" -> Ruler(v[0], v[1], v[2], v[3])
                    "parallel" -> Parallel(v[0], v[1], v[2], v[3])
                    else -> Perspective(v.chunked(2).map { Point(it[0], it[1]) })
                })
            }
        }
    }
}
