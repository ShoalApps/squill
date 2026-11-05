package io.github.shoalapps.squill.paint

sealed class Node(var id: Long, var name: String, var visible: Boolean, var opacity: Float, var blend: Blend, var clipped: Boolean)

class Layer(
    id: Long, name: String, var pixels: IntArray, visible: Boolean = true,
    opacity: Float = 1f, blend: Blend = Blend.NORMAL, var alphaLock: Boolean = false, clipped: Boolean = false,
) : Node(id, name, visible, opacity, blend, clipped) {
    fun copy(newId: Long = id, newName: String = name) = Layer(newId, newName, pixels.copyOf(), visible, opacity, blend, alphaLock, clipped)
}

class Group(id: Long, name: String, visible: Boolean = true, opacity: Float = 1f, blend: Blend = Blend.NORMAL,
            clipped: Boolean = false, var passThrough: Boolean = false, val children: MutableList<Node> = mutableListOf()) : Node(id, name, visible, opacity, blend, clipped)

private class NodeState(val node: Node) {
    val id = node.id; val name = node.name; val visible = node.visible; val opacity = node.opacity
    val blend = node.blend; val clipped = node.clipped
    val pixels = (node as? Layer)?.pixels; val alphaLock = (node as? Layer)?.alphaLock
    val passThrough = (node as? Group)?.passThrough
    val children = (node as? Group)?.children?.map { NodeState(it) }
    fun restore() {
        node.id = id; node.name = name; node.visible = visible; node.opacity = opacity; node.blend = blend; node.clipped = clipped
        if (node is Layer) { node.pixels = pixels!!; node.alphaLock = alphaLock!! }
        if (node is Group) { node.passThrough = passThrough!!; node.children.clear(); children!!.forEach { it.restore(); node.children.add(it.node) } }
    }
    fun arrays(): List<IntArray> = pixels?.let { listOf(it) } ?: children.orEmpty().flatMap { it.arrays() }
}
private sealed interface Step {
    val bytes: Long
    class Pixels(val layer: Layer, val rect: Rect, var data: IntArray) : Step { override val bytes get() = data.size * 4L }
    class Whole(val width: Int, val height: Int, val state: NodeState, val active: Long, val selection: ByteArray?, var assistants: List<Assistant>? = null) : Step {
        override val bytes get() = state.arrays().distinct().sumOf { it.size * 4L } + (selection?.size?.toLong() ?: 0L)
    }
}

class Painting(var width: Int, var height: Int, var background: Int = 0xFFFFFFFF.toInt()) {
    val root = Group(0, "Root")
    val nodes: List<Node> get() = buildList {
        fun walk(g: Group) { g.children.forEach { add(it); if (it is Group) walk(it) } }
        walk(root)
    }
    val layers: List<Layer> get() = nodes.filterIsInstance<Layer>()
    fun parentOf(node: Node): Group? = (listOf(root) + nodes.filterIsInstance<Group>()).firstOrNull { node in it.children }
    fun find(id: Long): Node? = if (id == root.id) root else nodes.firstOrNull { it.id == id }
    fun depthOf(node: Node): Int = if (node === root) 0 else parentOf(node)?.let { depthOf(it) + 1 } ?: 1
    private fun heightOf(node: Node): Int = if (node is Group) (node.children.maxOfOrNull { 1 + heightOf(it) } ?: 0) else 0
    private fun pixelCount(node: Node): Int = if (node is Layer) 1 else (node as Group).children.sumOf { pixelCount(it) }
    private fun groupCount(node: Node): Int = if (node is Layer) 0 else 1 + (node as Group).children.sumOf { groupCount(it) }
    var activeId = 0L
    var nextId = 1L
    var assistants: List<Assistant> = emptyList()
    var assistantsOn: Boolean = true
    var selection: ByteArray? = null
    val activeNode: Node get() = find(activeId)?.takeUnless { it === root } ?: layers.lastOrNull() ?: root
    val active: Layer get() {
        fun top(n: Node): Layer? = if (n is Layer) n else (n as Group).children.asReversed().firstNotNullOfOrNull { top(it) }
        return top(activeNode) ?: layers.last()
    }
    val canPaint get() = activeNode is Layer
    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    private var changing = false
    private fun place(node: Node) {
        val a = activeNode
        if (a is Group && depthOf(a) + 1 + heightOf(node) <= MAX_GROUP_DEPTH) a.children.add(node)
        else { val parent = parentOf(a) ?: root; parent.children.add((parent.children.indexOf(a) + 1).coerceAtLeast(0), node) }
        activeId = node.id
    }
    /** Inserts above the active sibling, or at the top inside the active group. */
    fun insertLayer(layer: Layer) {
        if (layers.size >= MAX_LAYERS) return
        change { place(layer) }
    }
    fun newLayer(name: String = "Layer ${layers.size + 1}"): Layer {
        if (layers.size >= MAX_LAYERS) return active
        val layer = Layer(nextId++, name, IntArray(width * height))
        insertLayer(layer)
        return layer
    }
    fun newGroup(name: String = "Group ${nodes.count { it is Group } + 1}") {
        if (nodes.count { it is Group } >= MAX_LAYERS) return
        change { place(Group(nextId++, name)) }
    }
    fun groupActive() {
        val a = activeNode; val parent = parentOf(a) ?: return
        if (depthOf(a) + 1 + heightOf(a) > MAX_GROUP_DEPTH || nodes.count { it is Group } >= MAX_LAYERS) return
        change { val g = Group(nextId++, "Group ${nodes.count { it is Group } + 1}"); parent.children[parent.children.indexOf(a)] = g; g.children.add(a); activeId = g.id }
    }
    fun ungroup() {
        val g = activeNode as? Group ?: return; val parent = parentOf(g) ?: return
        change { val at = parent.children.indexOf(g); parent.children.removeAt(at); parent.children.addAll(at, g.children)
            activeId = g.children.lastOrNull()?.id ?: parent.children.getOrNull(at - 1)?.id ?: parent.takeUnless { it === root }?.id ?: layers.last().id }
    }
    fun mergeGroup() {
        val g = activeNode as? Group ?: return; val parent = parentOf(g) ?: return
        if (layers.size - pixelCount(g) + 1 > MAX_LAYERS) return
        change { val l = Layer(nextId++, g.name, renderGroup(g), g.visible, g.opacity, if (g.passThrough) Blend.NORMAL else g.blend, clipped = g.clipped)
            parent.children[parent.children.indexOf(g)] = l; activeId = l.id }
    }
    private fun capture() = Step.Whole(width, height, NodeState(root), activeId, selection)
    /** Guides aren't undo steps of their own, so undoing a layer change keeps them. Only a step that moved them restores them. */
    private fun guidesMove() { (undo.lastOrNull() as? Step.Whole)?.assistants = assistants }
    private fun push(step: Step) {
        undo.addLast(step); redo.clear()
        var total = undo.sumOf { it.bytes }
        while (total > LIMIT && undo.size > 1) total -= undo.removeFirst().bytes
    }
    /** Structural changes retain node identities and pixel references; replace arrays before editing them. */
    fun change(block: () -> Unit) {
        if (changing) { block(); return }
        push(capture()); changing = true
        try { block() } finally { changing = false }
    }
    fun recordPixels(layer: Layer, rect: Rect, before: IntArray) {
        if (rect.isEmpty) return
        val data = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) System.arraycopy(before, (rect.top + y) * width + rect.left, data, y * rect.width, rect.width)
        push(Step.Pixels(layer, rect, data))
    }
    private fun applyStep(step: Step): Step = when (step) {
        is Step.Pixels -> {
            val back = IntArray(step.data.size); val r = step.rect
            for (y in 0 until r.height) {
                val at = (r.top + y) * width + r.left
                System.arraycopy(step.layer.pixels, at, back, y * r.width, r.width)
                System.arraycopy(step.data, y * r.width, step.layer.pixels, at, r.width)
            }
            Step.Pixels(step.layer, r, back)
        }
        is Step.Whole -> { val back = capture(); width = step.width; height = step.height; step.state.restore(); activeId = step.active; selection = step.selection; step.assistants?.let { back.assistants = assistants; assistants = it }; back }
    }
    fun undo(): Boolean { val s = undo.removeLastOrNull() ?: return false; redo.addLast(applyStep(s)); return true }
    fun redo(): Boolean { val s = redo.removeLastOrNull() ?: return false; undo.addLast(applyStep(s)); return true }

    fun render(rect: Rect, out: IntArray) {
        val r = rect.clip(width, height); if (r.isEmpty) return
        for (y in r.top until r.bottom) java.util.Arrays.fill(out, y * width + r.left, y * width + r.right, background)
        compose(root.children, out, 0, width, r)
    }
    private fun isolated(g: Group, r: Rect) = IntArray(r.width * r.height).also { compose(g.children, it, -r.top * r.width - r.left, r.width, r) }
    fun renderGroup(g: Group): IntArray = isolated(g, Rect(0, 0, width, height))
    private fun interpolate(from: Int, to: Int, t: Float): Int {
        if (from == to) return from
        val fa = alphaOf(from).toFloat(); val ta = alphaOf(to).toFloat(); val a = fa * (1f - t) + ta * t
        if (a <= 0f) return 0
        fun channel(f: Int, v: Int) = clamp8((f * fa * (1f - t) + v * ta * t) / a)
        return argb(clamp8(a), channel(redOf(from), redOf(to)), channel(greenOf(from), greenOf(to)), channel(blueOf(from), blueOf(to)))
    }
    /** Coordinates remain canvas coordinates; offset and stride map them into the current buffer. */
    private fun compose(children: List<Node>, out: IntArray, offset: Int, stride: Int, rect: Rect) {
        val r = rect; val localOffset = -r.top * r.width - r.left
        var base: IntArray? = null; var baseCanvas = true
        children.forEachIndexed { index, n ->
            val needed = !n.clipped && children.getOrNull(index + 1)?.clipped == true
            val drawing = n.visible && n.opacity > 0f
            val buffer = if (n is Group && (needed || (drawing && (!n.passThrough || n.clipped)))) isolated(n, r) else null
            if (!n.clipped) { base = if (n is Layer) n.pixels else buffer; baseCanvas = n is Layer }
            if (!drawing) return@forEachIndexed
            if (n is Group && n.passThrough && !n.clipped) {
                if (n.opacity >= 1f) compose(n.children, out, offset, stride, r)
                else {
                    val copy = IntArray(r.width * r.height)
                    for (y in r.top until r.bottom) for (x in r.left until r.right) copy[localOffset + y * r.width + x] = out[offset + y * stride + x]
                    compose(n.children, copy, localOffset, r.width, r)
                    for (y in r.top until r.bottom) for (x in r.left until r.right) {
                        val i = offset + y * stride + x
                        out[i] = interpolate(out[i], copy[localOffset + y * r.width + x], n.opacity)
                    }
                }
            } else for (y in r.top until r.bottom) for (x in r.left until r.right) {
                val ci = y * width + x; val li = localOffset + y * r.width + x
                var src = if (n is Layer) n.pixels[ci] else buffer!![li]
                if (alphaOf(src) == 0) continue
                if (n.clipped && base != null) {
                    val a = alphaOf(src) * alphaOf(base!![if (baseCanvas) ci else li]) / 255
                    if (a == 0) continue
                    src = (src and 0xFFFFFF) or (a shl 24)
                }
                val i = offset + y * stride + x
                out[i] = if (n.blend == Blend.NORMAL && n.opacity >= 1f && alphaOf(src) == 255) src else composite(out[i], src, n.blend, n.opacity)
            }
        }
    }
    /** Stroke clipping uses the nearest unclipped sibling, regardless of its visibility or opacity. */
    fun clipAlphaFor(layer: Layer): IntArray? {
        if (!layer.clipped) return null
        val siblings = parentOf(layer)?.children ?: return null
        return when (val base = siblings.take(siblings.indexOf(layer)).lastOrNull { !it.clipped }) {
            is Layer -> base.pixels
            is Group -> renderGroup(base)
            else -> null
        }
    }
    fun flattened(): IntArray = IntArray(width * height).also { render(Rect(0, 0, width, height), it) }
    val canMergeDown: Boolean get() { val a = activeNode; val siblings = parentOf(a)?.children ?: return false; return a is Layer && siblings.getOrNull(siblings.indexOf(a) - 1) is Layer }
    fun mergeDown() {
        if (!canMergeDown) return
        val top = activeNode as Layer; val siblings = parentOf(top)!!.children; val at = siblings.indexOf(top); val under = siblings[at - 1] as Layer
        change {
            val merged = under.pixels.copyOf()
            for (i in merged.indices) {
                var src = top.pixels[i]; if (alphaOf(src) == 0) continue
                if (top.clipped) src = (src and 0xFFFFFF) or ((alphaOf(src) * alphaOf(under.pixels[i]) / 255) shl 24)
                merged[i] = composite(merged[i], src, top.blend, top.opacity)
            }
            under.pixels = merged; siblings.removeAt(at); activeId = under.id
        }
    }
    fun flatten() = change { val l = Layer(nextId++, "Background", flattened()); root.children.clear(); root.children.add(l); activeId = l.id }
    val canDeleteActive get() = parentOf(activeNode) != null && layers.size > pixelCount(activeNode)
    fun deleteActive() {
        if (!canDeleteActive) return
        val a = activeNode; val parent = parentOf(a)!!; val at = parent.children.indexOf(a)
        change { parent.children.removeAt(at); activeId = parent.children.getOrNull(at - 1)?.id ?: parent.children.getOrNull(at)?.id ?: parent.takeUnless { it === root }?.id ?: layers.last().id }
    }
    val canDuplicateActive get() = parentOf(activeNode) != null && layers.size + pixelCount(activeNode) <= MAX_LAYERS && nodes.count { it is Group } + groupCount(activeNode) <= MAX_LAYERS
    private fun clone(n: Node, copyPixels: Boolean, freshIds: Boolean): Node = when (n) {
        is Layer -> Layer(if (freshIds) nextId++ else n.id, n.name, if (copyPixels) n.pixels.copyOf() else n.pixels, n.visible, n.opacity, n.blend, n.alphaLock, n.clipped)
        is Group -> Group(if (freshIds) nextId++ else n.id, n.name, n.visible, n.opacity, n.blend, n.clipped, n.passThrough, n.children.map { clone(it, copyPixels, freshIds) }.toMutableList())
    }
    fun snapshot(copyPixels: Boolean): Painting = Painting(width, height, background).also { p ->
        p.root.id = root.id; p.root.name = root.name; p.root.visible = root.visible; p.root.opacity = root.opacity
        p.root.blend = root.blend; p.root.clipped = root.clipped; p.root.passThrough = root.passThrough
        p.root.children.addAll(root.children.map { clone(it, copyPixels, false) }); p.activeId = activeId; p.nextId = nextId
        p.assistants = assistants.map { if (it is Perspective) it.copy(points = it.points.toList()) else it }; p.assistantsOn = assistantsOn
    }
    fun duplicateActive() {
        if (!canDuplicateActive) return
        val a = activeNode; val siblings = parentOf(a)!!.children
        change { val copy = clone(a, true, true); copy.name += " copy"; siblings.add(siblings.indexOf(a) + 1, copy); activeId = copy.id }
    }
    fun moveActive(by: Int) {
        if (by != 1 && by != -1) return
        val a = activeNode; val parent = parentOf(a) ?: return; val at = parent.children.indexOf(a)
        val next = parent.children.getOrNull(at + by)
        if (next == null && parent === root) return
        change {
            parent.children.removeAt(at)
            if (next is Group && depthOf(next) + 1 + heightOf(a) <= MAX_GROUP_DEPTH) next.children.add(if (by > 0) 0 else next.children.size, a)
            else if (next != null) parent.children.add(at + by, a)
            else { val grand = parentOf(parent)!!; grand.children.add(grand.children.indexOf(parent) + if (by > 0) 1 else 0, a) }
        }
    }
    fun copySelectionToLayer(cut: Boolean = false) {
        if (!canPaint || layers.size >= MAX_LAYERS) return
        change {
            val a = active; val mask = selection; val fresh = IntArray(width * height); val kept = if (cut) a.pixels.copyOf() else a.pixels
            for (i in fresh.indices) if (mask == null || mask[i] != 0.toByte()) { fresh[i] = a.pixels[i]; if (cut) kept[i] = 0 }
            a.pixels = kept; insertLayer(Layer(nextId++, if (cut) "Cut" else "Copy", fresh))
        }
    }
    fun clearActive() { if (!canPaint) return; change {
        val a = active; val mask = selection
        a.pixels = if (mask == null) IntArray(width * height) else a.pixels.copyOf().also { p -> for (i in p.indices) if (mask[i] != 0.toByte() && !a.alphaLock) p[i] = 0 }
    } }
    fun filterActive(filter: Filter, amount: Float) { if (!canPaint) return; change {
        val a = active; val out = Filters.apply(filter, a.pixels, width, height, amount); val mask = selection
        if (mask != null) for (i in out.indices) if (mask[i] == 0.toByte()) out[i] = a.pixels[i]
        a.pixels = out
    } }
    fun transformActive(scaleX: Float, scaleY: Float, radians: Float, dx: Float, dy: Float, smooth: Boolean = true) { if (!canPaint) return; change {
        val a = active; val mask = selection
        if (mask == null) a.pixels = Raster.transform(a.pixels, width, height, scaleX, scaleY, radians, dx, dy, smooth)
        else {
            val lifted = IntArray(width * height); val rest = a.pixels.copyOf()
            for (i in lifted.indices) if (mask[i] != 0.toByte()) { lifted[i] = rest[i]; rest[i] = 0 }
            val moved = Raster.transform(lifted, width, height, scaleX, scaleY, radians, dx, dy, smooth)
            for (i in rest.indices) rest[i] = composite(rest[i], moved[i], Blend.NORMAL)
            a.pixels = rest
        }
    } }
    fun flip(horizontal: Boolean, allLayers: Boolean) { if (!allLayers && !canPaint) return; change {
        fun flipped(src: IntArray) = IntArray(src.size).also { out ->
            for (y in 0 until height) for (x in 0 until width) out[if (horizontal) y * width + width - 1 - x else (height - 1 - y) * width + x] = src[y * width + x]
        }
        if (allLayers) { guidesMove(); assistants = assistants.map { it.mapHandles { p -> if (horizontal) Point(width - p.x, p.y) else Point(p.x, height - p.y) } }; layers.forEach { it.pixels = flipped(it.pixels) }; selection = null } else active.pixels = flipped(active.pixels)
    } }

    /** Turns the whole canvas a quarter turn, swapping width and height. */
    fun rotate90(clockwise: Boolean) = change {
        guidesMove()
        val w = width; val h = height
        layers.forEach { l ->
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val (nx, ny) = if (clockwise) (h - 1 - y) to x else y to (w - 1 - x)
                out[ny * h + nx] = l.pixels[y * w + x]
            }
            l.pixels = out
        }
        assistants = assistants.map { it.mapHandles { p -> if (clockwise) Point(h - p.y, p.x) else Point(p.y, w - p.x) } }
        width = h; height = w
        selection = null
    }

    /** Crops or extends the canvas. [left] and [top] are where the old (0, 0) lands. */
    fun resizeCanvas(newW: Int, newH: Int, left: Int = 0, top: Int = 0) = change {
        guidesMove()
        val w = width; val h = height
        layers.forEach { l ->
            val out = IntArray(newW * newH)
            for (y in 0 until h) {
                val ny = y + top
                if (ny !in 0 until newH) continue
                val startX = maxOf(0, -left)
                val len = minOf(w - startX, newW - (startX + left))
                if (len > 0) System.arraycopy(l.pixels, y * w + startX, out, ny * newW + startX + left, len)
            }
            l.pixels = out
        }
        assistants = assistants.map { it.mapHandles { p -> Point(p.x + left, p.y + top) } }
        width = newW; height = newH
        selection = null
    }

    fun invertSelection() = change {
        val mask = selection
        selection = if (mask == null) null else ByteArray(mask.size) { if (mask[it] == 0.toByte()) -1 else 0 }
    }

    companion object {
        /** Undo keeps this much, and lets go of the oldest beyond it. */
        const val LIMIT = 192L * 1024 * 1024
        const val MAX_SIDE = 4096
        const val MAX_LAYERS = 40
        const val MAX_GROUP_DEPTH = 8

        fun blank(w: Int, h: Int) = Painting(w, h).also { p -> val l = Layer(p.nextId++, "Layer 1", IntArray(w * h)); p.root.children.add(l); p.activeId = l.id }
    }
}

/** OpenRaster lists siblings top first. Stack offsets are deliberately ignored. */
object OraStack {
    sealed interface Item
    data class Entry(val name: String, val src: String, val opacity: Float, val visible: Boolean, val blend: Blend, val alphaLock: Boolean, val clipped: Boolean, val x: Int = 0, val y: Int = 0) : Item
    data class GroupEntry(val name: String, val opacity: Float, val visible: Boolean, val blend: Blend, val passThrough: Boolean, val clipped: Boolean, val children: List<Item>) : Item

    fun write(width: Int, height: Int, items: List<Item>): String = buildString {
        append("<?xml version='1.0' encoding='UTF-8'?>\n<image version=\"0.0.5\" w=\"$width\" h=\"$height\" xres=\"72\" yres=\"72\">\n<stack>\n")
        fun emit(items: List<Item>) {
            items.forEach { item -> when (item) {
                is Entry -> {
                    append("<layer name=\"").append(io.github.shoalapps.squill.notes.esc(item.name)).append("\" src=\"").append(io.github.shoalapps.squill.notes.esc(item.src)).append("\" x=\"").append(item.x).append("\" y=\"").append(item.y).append("\" opacity=\"")
                    append(item.opacity).append("\" visibility=\"").append(if (item.visible) "visible" else "hidden").append("\" composite-op=\"").append(item.blend.ora).append('"')
                    if (item.alphaLock) append(" alpha-preserve=\"true\"")
                    if (item.clipped) append(" squill-clipped=\"true\"")
                    append("/>\n")
                }
                is GroupEntry -> {
                    append("<stack name=\"").append(io.github.shoalapps.squill.notes.esc(item.name)).append("\" opacity=\"").append(item.opacity)
                    append("\" visibility=\"").append(if (item.visible) "visible" else "hidden").append("\" composite-op=\"").append(item.blend.ora)
                    append("\" isolation=\"").append(if (item.passThrough) "auto" else "isolate").append('"')
                    if (item.clipped) append(" squill-clipped=\"true\"")
                    append('>'); emit(item.children); append("</stack>\n")
                }
            } }
        }
        emit(items)
        append("</stack>\n</image>\n")
    }
    data class Parsed(val width: Int, val height: Int, val items: List<Item>, val entries: List<Entry>, val offsets: List<Pair<Int, Int>>)
    fun parse(xml: ByteArray): Parsed {
        val p = io.github.shoalapps.squill.notes.parser(xml)
        var w = 0; var h = 0; var rootDepth = -1
        val items = mutableListOf<Item>(); val entries = mutableListOf<Entry>()
        data class Frame(val depth: Int, val group: GroupEntry?, val children: MutableList<Item>)
        val frames = ArrayDeque<Frame>()
        fun attr(name: String) = p.getAttributeValue(null, name)
        fun opacity() = attr("opacity")?.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (p.eventType == org.xmlpull.v1.XmlPullParser.END_TAG) {
                if (p.name == "stack" && frames.lastOrNull()?.depth == p.depth) {
                    val f = frames.removeLast()
                    f.group?.let { frames.last().children.add(it.copy(children = f.children)) }
                }
                continue
            }
            if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (p.name == "image") { w = attr("w")?.toIntOrNull() ?: 0; h = attr("h")?.toIntOrNull() ?: 0 }
            if (p.name == "stack") {
                if (rootDepth == -1) { rootDepth = p.depth; frames.addLast(Frame(p.depth, null, items)) }
                else if (frames.lastOrNull()?.depth == p.depth - 1) {
                    require(frames.size <= Painting.MAX_GROUP_DEPTH) { "That painting's groups are nested too deeply to open" }
                    val blend = Blend.fromOra(attr("composite-op"))
                    frames.addLast(Frame(p.depth, GroupEntry(attr("name") ?: "Group", opacity(), attr("visibility") != "hidden", blend, attr("isolation") == "auto" && blend == Blend.NORMAL, attr("squill-clipped") == "true", emptyList()), mutableListOf()))
                }
            }
            if (p.name == "layer" && frames.lastOrNull()?.depth == p.depth - 1) {
                val src = attr("src") ?: continue
                val e = Entry(attr("name") ?: "Layer", src, opacity(), attr("visibility") != "hidden", Blend.fromOra(attr("composite-op")), attr("alpha-preserve") == "true", attr("squill-clipped") == "true", attr("x")?.toIntOrNull() ?: 0, attr("y")?.toIntOrNull() ?: 0)
                frames.last().children.add(e); entries.add(e)
            }
        }
        return Parsed(w, h, items, entries, entries.map { it.x to it.y })
    }
}

fun OraStack.toPainting(parsed: OraStack.Parsed, pixels: (OraStack.Entry) -> IntArray): Painting {
    val p = Painting(parsed.width, parsed.height)
    fun nodes(items: List<OraStack.Item>): MutableList<Node> = items.asReversed().map { item -> when (item) {
        is OraStack.Entry -> Layer(p.nextId++, item.name, pixels(item), item.visible, item.opacity, item.blend, item.alphaLock, item.clipped)
        is OraStack.GroupEntry -> Group(p.nextId++, item.name, item.visible, item.opacity, item.blend, item.clipped, item.passThrough, nodes(item.children))
    } }.toMutableList()
    p.root.children.addAll(nodes(parsed.items))
    if (p.layers.isEmpty()) p.root.children.add(Layer(p.nextId++, "Layer 1", IntArray(p.width * p.height)))
    p.activeId = p.layers.last().id
    return p
}

fun Painting.toOra(src: (Layer) -> String): List<OraStack.Item> {
    fun items(children: List<Node>): List<OraStack.Item> = children.asReversed().map { n -> when (n) {
        is Layer -> OraStack.Entry(n.name, src(n), n.opacity, n.visible, n.blend, n.alphaLock, n.clipped)
        is Group -> OraStack.GroupEntry(n.name, n.opacity, n.visible, n.blend, n.passThrough, n.clipped, items(n.children))
    } }
    return items(root.children)
}
