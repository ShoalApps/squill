package io.github.shoalapps.squill.notes

/** What links to what, worked out from the text of the notes. */
object Links {
    /** The note names a text links to, and its tags. Code doesn't count. */
    fun scan(text: String): Pair<List<String>, List<String>> {
        val links = mutableListOf<String>()
        val tags = mutableListOf<String>()
        fun read(line: String) {
            Markdown.inlines(line).forEach { run ->
                run.note?.let { links += it }
                run.tag?.let { tags += it }
            }
        }
        Markdown.parse(text).forEach { b ->
            when (b) {
                is Block.Heading -> read(b.text)
                is Block.Paragraph -> read(b.text)
                is Block.Bullet -> read(b.text)
                is Block.Numbered -> read(b.text)
                is Block.Task -> read(b.text)
                is Block.Quote -> read(b.text)
                is Block.Table -> (b.header + b.rows.flatten()).forEach(::read)
                is Block.Code, is Block.Rule, is Block.Embed -> Unit
            }
        }
        return links.distinct() to tags.distinct()
    }

    /** Takes off a #heading and any .md, so that a link's target can be matched against note names. */
    private fun clean(target: String) = target.substringBefore('#').trim().removeSuffix(".md").trim('/')

    /** The note a link means. Build a [Resolver] instead when looking up many. */
    fun resolve(target: String, paths: Collection<String>): String? = Resolver(paths).resolve(target)

    /**
     * Works out what links mean, in constant time however many notes there are. A full path wins;
     * otherwise the name alone, and if two folders both have that name, the one nearest the top
     * (as in Obsidian).
     */
    class Resolver(paths: Collection<String>) {
        private val byPath = HashMap<String, String>(paths.size * 2)
        private val byName = HashMap<String, MutableList<String>>(paths.size * 2)

        init {
            paths.forEach { path ->
                byPath[path.lowercase()] = path
                byName.getOrPut(path.substringAfterLast('/').removeSuffix(".md").lowercase()) { mutableListOf() } += path
            }
            byName.values.forEach { list -> list.sortWith(compareBy({ it.count { c -> c == '/' } }, { it })) }
        }

        fun resolve(target: String): String? {
            val wanted = clean(target)
            if (wanted.isEmpty()) return null
            byPath["${wanted.lowercase()}.md"]?.let { return it }
            val same = byName[wanted.substringAfterLast('/').lowercase()] ?: return null
            if ("/" !in wanted) return same.firstOrNull()
            return same.firstOrNull { it.removeSuffix(".md").endsWith("/$wanted", ignoreCase = true) }
        }
    }

    /** Points links in [text] at a note's new name, leaving code, headings and aliases alone. */
    fun retarget(text: String, oldPath: String, newPath: String, paths: Collection<String>): String {
        val resolver = Resolver(paths)
        val oldName = oldPath.removeSuffix(".md")
        val newName = newPath.removeSuffix(".md")
        val code = codeRanges(text)
        return wikiLink.replace(text) { m ->
            val at = m.range.first
            if (code.any { at in it }) return@replace m.value
            val target = m.groupValues[1]
            if (resolver.resolve(target) != oldPath) return@replace m.value
            // Keep the style it was written in: a bare name stays a bare name if that still works.
            val written = clean(target)
            val replacement = if ("/" in written) newName else newName.substringAfterLast('/')
            val heading = target.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
            "[[$replacement$heading${m.groupValues[2]}]]"
        }
    }

    /** Points ![[embeds]] of a drawing at its new name. */
    fun retargetEmbed(text: String, oldName: String, newName: String): String =
        Regex("!\\[\\[" + Regex.escape(oldName) + "((?:\\|[^\\]]*)?)]]", RegexOption.IGNORE_CASE).replace(text) { "![[" + newName + it.groupValues[1] + "]]" }

    private val wikiLink = Regex("\\[\\[([^\\]|\\n]+)(\\|[^\\]\\n]*)?]]")

    /** Where the fenced code blocks and `inline code` are. */
    private fun codeRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var pos = 0
        var fence: String? = null
        var fenceStart = 0
        text.split('\n').forEach { line ->
            val t = line.trimStart()
            if (fence == null && (t.startsWith("```") || t.startsWith("~~~"))) {
                fence = t.take(3); fenceStart = pos
            } else if (fence != null && t.startsWith(fence!!)) {
                ranges += fenceStart..(pos + line.length); fence = null
            } else if (fence == null) {
                var i = line.indexOf('`')
                while (i >= 0) {
                    val end = line.indexOf('`', i + 1)
                    if (end < 0) break
                    ranges += (pos + i)..(pos + end)
                    i = line.indexOf('`', end + 1)
                }
            }
            pos += line.length + 1
        }
        if (fence != null) ranges += fenceStart..text.length
        return ranges
    }
}

/** The notes and how they join up. A node with no [path] is a note that's linked to but not written yet. */
data class GraphNode(val id: String, val title: String, val path: String?, val links: Int)
data class Graph(val nodes: List<GraphNode>, val edges: List<Pair<Int, Int>>) {
    companion object {
        /** [notes] maps each note's path to the targets it links to. */
        fun of(notes: Map<String, List<String>>, showUnwritten: Boolean = true): Graph {
            val paths = notes.keys
            val resolver = Links.Resolver(paths)
            val pairs = linkedSetOf<Pair<String, String>>()
            val unwritten = linkedMapOf<String, String>()
            notes.forEach { (from, targets) ->
                targets.forEach { target ->
                    val to = resolver.resolve(target)
                    when {
                        to == from -> Unit
                        to != null -> pairs += ordered(from, to)
                        showUnwritten -> {
                            val name = target.substringBefore('#').trim().removeSuffix(".md")
                            if (name.isNotEmpty()) {
                                val id = "unwritten:${name.lowercase()}"
                                unwritten.putIfAbsent(id, name)
                                pairs += ordered(from, id)
                            }
                        }
                    }
                }
            }
            val count = HashMap<String, Int>()
            pairs.forEach { (a, b) ->
                count[a] = (count[a] ?: 0) + 1
                count[b] = (count[b] ?: 0) + 1
            }
            val nodes = paths.sorted().map { GraphNode(it, it.substringAfterLast('/').removeSuffix(".md"), it, count[it] ?: 0) } +
                unwritten.map { (id, name) -> GraphNode(id, name.substringAfterLast('/'), null, count[id] ?: 0) }
            val index = nodes.withIndex().associate { (i, n) -> n.id to i }
            return Graph(nodes, pairs.map { (a, b) -> index.getValue(a) to index.getValue(b) })
        }

        private fun ordered(a: String, b: String) = if (a < b) a to b else b to a
    }

    /** Just [path] and whatever's within [depth] links of it. */
    fun around(path: String, depth: Int = 1): Graph {
        val start = nodes.indexOfFirst { it.path == path }
        if (start < 0) return Graph(emptyList(), emptyList())
        val keep = linkedSetOf(start)
        repeat(depth) {
            val found = edges.filter { (a, b) -> a in keep || b in keep }.flatMap { (a, b) -> listOf(a, b) }
            keep += found
        }
        val order = keep.toList()
        val map = order.withIndex().associate { (new, old) -> old to new }
        return Graph(order.map { nodes[it] }, edges.filter { (a, b) -> a in keep && b in keep }.map { (a, b) -> map.getValue(a) to map.getValue(b) })
    }
}

/** A simple force-directed layout: nodes push apart, links pull together, and the middle draws everything in. */
class GraphLayout(private val graph: Graph, seed: Int = 7) {
    val x = FloatArray(graph.nodes.size)
    val y = FloatArray(graph.nodes.size)
    private val vx = FloatArray(graph.nodes.size)
    private val vy = FloatArray(graph.nodes.size)
    /** Cools with every step, so the layout always comes to rest. */
    private var heat = 1f

    init {
        // A spiral start spreads nodes evenly and comes out the same every time.
        val golden = 2.39996f + seed * 0f
        for (i in x.indices) {
            val r = 30f * kotlin.math.sqrt(i + 1f)
            x[i] = r * kotlin.math.cos(golden * i)
            y[i] = r * kotlin.math.sin(golden * i)
        }
    }

    /** One step. Returns how much is still moving, so the caller knows when to stop. */
    fun step(): Float {
        val n = x.size
        if (n == 0) return 0f
        val fx = FloatArray(n)
        val fy = FloatArray(n)
        fun push(i: Int, j: Int) {
            var dx = x[i] - x[j]
            var dy = y[i] - y[j]
            var d2 = dx * dx + dy * dy
            if (d2 < 1f) { dx = 1f; dy = 0.5f; d2 = 1.25f }
            val f = 9000f / d2
            fx[i] += dx * f; fy[i] += dy * f
            fx[j] -= dx * f; fy[j] -= dy * f
        }
        if (n <= EVERY_PAIR_LIMIT) {
            for (i in 0 until n) for (j in i + 1 until n) push(i, j)
        } else {
            // A big vault only pushes against its neighbours, so a frame stays a frame.
            val cells = HashMap<Long, MutableList<Int>>(n)
            for (i in 0 until n) cells.getOrPut(cell(x[i], y[i])) { mutableListOf() } += i
            cells.forEach { (key, list) ->
                val cx = (key shr 32).toInt()
                val cy = key.toInt()
                for (a in cx - 1..cx + 1) for (b in cy - 1..cy + 1) {
                    val other = cells[(a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)] ?: continue
                    for (i in list) for (j in other) if (i < j) push(i, j)
                }
            }
        }
        for ((a, b) in graph.edges) {
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            fx[a] += dx * .035f; fy[a] += dy * .035f
            fx[b] -= dx * .035f; fy[b] -= dy * .035f
        }
        var moving = 0f
        for (i in 0 until n) {
            fx[i] -= x[i] * .012f
            fy[i] -= y[i] * .012f
            vx[i] = (vx[i] + fx[i] * .35f) * .72f
            vy[i] = (vy[i] + fy[i] * .35f) * .72f
            x[i] += vx[i] * heat
            y[i] += vy[i] * heat
            moving += (kotlin.math.abs(vx[i]) + kotlin.math.abs(vy[i])) * heat
        }
        heat *= .99f
        return moving / n
    }

    private fun cell(px: Float, py: Float): Long {
        val cx = kotlin.math.floor(px / CELL).toInt()
        val cy = kotlin.math.floor(py / CELL).toInt()
        return (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
    }

    /** The zoom and offset (from the middle) that keep every node in a view this size, leaving [margin] around it. */
    fun fit(width: Float, height: Float, margin: Float): Triple<Float, Float, Float> {
        if (x.isEmpty()) return Triple(1f, 0f, 0f)
        val minX = x.min(); val maxX = x.max(); val minY = y.min(); val maxY = y.max()
        val zoom = minOf((width - margin * 2f) / (maxX - minX).coerceAtLeast(1f), (height - margin * 2f) / (maxY - minY).coerceAtLeast(1f)).coerceIn(.01f, 1.5f)
        return Triple(zoom, -(minX + maxX) / 2f * zoom, -(minY + maxY) / 2f * zoom)
    }

    fun nearest(px: Float, py: Float, within: Float): Int? {
        var best = -1
        var bestD = within * within
        for (i in x.indices) {
            val d = (x[i] - px) * (x[i] - px) + (y[i] - py) * (y[i] - py)
            if (d <= bestD) { best = i; bestD = d }
        }
        return best.takeIf { it >= 0 }
    }

    private companion object {
        const val EVERY_PAIR_LIMIT = 800
        const val CELL = 320f
    }
}
