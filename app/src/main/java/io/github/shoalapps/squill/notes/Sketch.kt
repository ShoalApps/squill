package io.github.shoalapps.squill.notes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One pen stroke. [points] runs x, y, milliseconds, pressure (or -1 if the input had none), over and over. */
@Serializable
data class SketchStroke(val tool: String, val colour: Int, val size: Float, val points: List<Float>) {
    val count: Int get() = points.size / 4
    fun x(i: Int) = points[i * 4]
    fun y(i: Int) = points[i * 4 + 1]
    fun t(i: Int) = points[i * 4 + 2]
    fun p(i: Int) = points[i * 4 + 3]

    fun moved(dx: Float, dy: Float) = copy(points = points.mapIndexed { i, v -> when (i % 4) { 0 -> v + dx; 1 -> v + dy; else -> v } })
}

@Serializable
data class SketchText(val x: Float, val y: Float, val text: String, val colour: Int, val size: Float = 40f) {
    fun moved(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
}

/** A drawing. Saved as an ordinary SVG picture that also carries this, so it can be edited again. */
@Serializable
data class Sketch(
    val paper: String = PLAIN,
    val strokes: List<SketchStroke> = emptyList(),
    val texts: List<SketchText> = emptyList(),
) {
    /** The area the drawing covers: left, top, right, bottom. */
    fun bounds(margin: Float = 24f): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        strokes.forEach { s ->
            for (i in 0 until s.count) {
                l = minOf(l, s.x(i) - s.size); r = maxOf(r, s.x(i) + s.size)
                t = minOf(t, s.y(i) - s.size); b = maxOf(b, s.y(i) + s.size)
            }
        }
        texts.forEach { x ->
            l = minOf(l, x.x); r = maxOf(r, x.x + x.text.length * x.size * .6f)
            t = minOf(t, x.y - x.size); b = maxOf(b, x.y + x.size * .3f)
        }
        if (l > r) return floatArrayOf(0f, 0f, 400f, 300f)
        return floatArrayOf(l - margin, t - margin, r + margin, b + margin)
    }

    fun toSvg(): String {
        val box = bounds()
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        val out = StringBuilder()
        out.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="${n(box[0])} ${n(box[1])} ${n(w)} ${n(h)}" width="${n(w)}" height="${n(h)}">""").append('\n')
        out.append("<metadata id=\"$MARK\">").append(escape(json.encodeToString(serializer(), this))).append("</metadata>\n")
        out.append("""<rect x="${n(box[0])}" y="${n(box[1])}" width="${n(w)}" height="${n(h)}" fill="#FFFBF5"/>""").append('\n')
        strokes.forEach { s -> out.append(strokeSvg(s)).append('\n') }
        texts.forEach { t ->
            out.append("""<text x="${n(t.x)}" y="${n(t.y)}" font-family="sans-serif" font-size="${n(t.size)}" fill="${hex(t.colour)}">${escape(t.text)}</text>""").append('\n')
        }
        return out.append("</svg>\n").toString()
    }

    private fun strokeSvg(s: SketchStroke): String {
        val opacity = if (s.tool == HIGHLIGHTER) .4f else 1f
        val out = StringBuilder("""<g fill="none" stroke="${hex(s.colour)}" stroke-opacity="${n(opacity)}" stroke-linecap="round" stroke-linejoin="round">""")
        // Pressure can't be a single line's width, so neighbouring points of about the same width share one.
        var i = 0
        while (i < s.count - 1 || (i == 0 && s.count == 1)) {
            val width = widthAt(s, i)
            val path = StringBuilder("M${n(s.x(i))} ${n(s.y(i))}")
            var j = i + 1
            if (s.count == 1) path.append("L${n(s.x(0))} ${n(s.y(0))}")
            while (j < s.count && kotlin.math.abs(widthAt(s, j) - width) < .75f) { path.append("L${n(s.x(j))} ${n(s.y(j))}"); j++ }
            if (j < s.count) path.append("L${n(s.x(j))} ${n(s.y(j))}")
            out.append("""<path stroke-width="${n(width)}" d="$path"/>""")
            i = maxOf(j, i + 1)
        }
        return out.append("</g>").toString()
    }

    private fun widthAt(s: SketchStroke, i: Int): Float {
        val p = s.p(i)
        return if (p < 0f || s.tool == HIGHLIGHTER) s.size else s.size * (.45f + .75f * p.coerceIn(0f, 1f))
    }

    companion object {
        const val PLAIN = "plain"
        const val LINES = "lines"
        const val GRID = "grid"
        const val DOTS = "dots"
        const val PEN = "pen"
        const val PENCIL = "pencil"
        const val MARKER = "marker"
        const val HIGHLIGHTER = "highlighter"
        private const val MARK = "squill-sketch"
        private val json = Json { ignoreUnknownKeys = true }

        /** The drawing in an SVG Squill saved, or null if it's some other picture. */
        fun parse(svg: String): Sketch? {
            val open = "<metadata id=\"$MARK\">"
            val start = svg.indexOf(open)
            if (start < 0) return null
            val end = svg.indexOf("</metadata>", start)
            if (end < 0) return null
            return runCatching { json.decodeFromString(serializer(), unescape(svg.substring(start + open.length, end))) }.getOrNull()
        }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
        private fun n(v: Float): String {
            val r = kotlin.math.round(v * 100f) / 100f
            return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
        }

        private fun synth(tool: String, colour: Int, size: Float, xy: List<Pair<Float, Float>>): SketchStroke {
            val points = ArrayList<Float>(xy.size * 4)
            xy.forEachIndexed { i, (x, y) -> points += x; points += y; points += i * 8f; points += -1f }
            return SketchStroke(tool, colour, size, points)
        }

        /** Straight-edged shapes come out as strokes like any other, so they erase and move the same way. */
        private fun edge(a: Pair<Float, Float>, b: Pair<Float, Float>, steps: Int = 12) =
            (0..steps).map { i -> val f = i / steps.toFloat(); (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f) }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float, colour: Int, size: Float) = synth(PEN, colour, size, edge(x0 to y0, x1 to y1))

        fun rectangle(x0: Float, y0: Float, x1: Float, y1: Float, colour: Int, size: Float): SketchStroke {
            val corners = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1, x0 to y0)
            return synth(PEN, colour, size, corners.zipWithNext().flatMap { (a, b) -> edge(a, b).dropLast(1) } + corners.last())
        }

        fun ellipse(x0: Float, y0: Float, x1: Float, y1: Float, colour: Int, size: Float): SketchStroke {
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            return synth(PEN, colour, size, (0..72).map { i ->
                val a = i / 72f * 2f * Math.PI.toFloat()
                (cx + (x1 - x0) / 2f * kotlin.math.cos(a)) to (cy + (y1 - y0) / 2f * kotlin.math.sin(a))
            })
        }

        fun arrow(x0: Float, y0: Float, x1: Float, y1: Float, colour: Int, size: Float): List<SketchStroke> {
            val angle = kotlin.math.atan2(y1 - y0, x1 - x0)
            val head = (kotlin.math.hypot(x1 - x0, y1 - y0) * .25f).coerceIn(size * 3f, 80f)
            fun tip(turn: Float) = (x1 - head * kotlin.math.cos(angle + turn)) to (y1 - head * kotlin.math.sin(angle + turn))
            val (ax, ay) = tip(.5f)
            val (bx, by) = tip(-.5f)
            return listOf(line(x0, y0, x1, y1, colour, size), line(x1, y1, ax, ay, colour, size), line(x1, y1, bx, by, colour, size))
        }
    }
}

/** Whether a stroke passes within [reach] of a point, for the eraser and for selecting. */
fun SketchStroke.touches(px: Float, py: Float, reach: Float): Boolean {
    val limit = reach + size / 2f
    for (i in 0 until count) {
        val x0 = x(i); val y0 = y(i)
        val x1 = if (i + 1 < count) x(i + 1) else x0
        val y1 = if (i + 1 < count) y(i + 1) else y0
        val dx = x1 - x0; val dy = y1 - y0
        val len = dx * dx + dy * dy
        val f = if (len == 0f) 0f else (((px - x0) * dx + (py - y0) * dy) / len).coerceIn(0f, 1f)
        val qx = x0 + dx * f - px; val qy = y0 + dy * f - py
        if (qx * qx + qy * qy <= limit * limit) return true
    }
    return false
}

fun SketchStroke.inside(l: Float, t: Float, r: Float, b: Float): Boolean =
    (0 until count).all { x(it) in l..r && y(it) in t..b }
