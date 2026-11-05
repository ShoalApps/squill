package io.github.shoalapps.squill.paint

/** One layer of pixels. */
class Layer(
    var id: Long,
    var name: String,
    var pixels: IntArray,
    var visible: Boolean = true,
    var opacity: Float = 1f,
    var blend: Blend = Blend.NORMAL,
    var alphaLock: Boolean = false,
    /** Stays inside the shape of the layer underneath: Krita's "inherit alpha". */
    var clipped: Boolean = false,
) {
    fun copy(newId: Long = id, newName: String = name) = Layer(newId, newName, pixels.copyOf(), visible, opacity, blend, alphaLock, clipped)
}

/** How layers looked, for undoing anything that changes more than one layer's pixels. */
private class LayerState(val layer: Layer, val pixels: IntArray, val visible: Boolean, val opacity: Float, val blend: Blend, val alphaLock: Boolean, val clipped: Boolean, val name: String)

private sealed interface Step {
    val bytes: Long

    /** Pixels in one rectangle of one layer. */
    class Pixels(val layer: Layer, val rect: Rect, var data: IntArray) : Step {
        override val bytes get() = data.size * 4L
    }

    /** The whole arrangement: layers, order, canvas size and selection. */
    class Whole(val width: Int, val height: Int, val states: List<LayerState>, val active: Long, val selection: ByteArray?) : Step {
        override val bytes get() = states.distinctBy { System.identityHashCode(it.pixels) }.sumOf { it.pixels.size * 4L } + (selection?.size?.toLong() ?: 0L)
    }
}

class Painting(var width: Int, var height: Int, var background: Int = 0xFFFFFFFF.toInt()) {
    val layers = mutableListOf<Layer>()
    var activeId = 0L
    var nextId = 1L
    var selection: ByteArray? = null

    val active: Layer get() = layers.firstOrNull { it.id == activeId } ?: layers.last()

    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun newLayer(name: String = "Layer ${layers.size + 1}"): Layer {
        val l = Layer(nextId++, name, IntArray(width * height))
        val at = layers.indexOfFirst { it.id == activeId }
        if (at < 0) layers.add(l) else layers.add(at + 1, l)
        activeId = l.id
        return l
    }

    private fun capture() = Step.Whole(width, height, layers.map { LayerState(it, it.pixels, it.visible, it.opacity, it.blend, it.alphaLock, it.clipped, it.name) }, activeId, selection)

    private fun push(step: Step) {
        undo.addLast(step)
        redo.clear()
        var total = undo.sumOf { it.bytes }
        while (total > LIMIT && undo.size > 1) { total -= undo.removeFirst().bytes }
    }

    /** Runs a change to the layers or the canvas so that it can be undone. Pixels must be replaced, not edited in place. */
    fun change(block: () -> Unit) {
        push(capture())
        block()
    }

    /** Records that [rect] of [layer] was changed from [before]. */
    fun recordPixels(layer: Layer, rect: Rect, before: IntArray) {
        if (rect.isEmpty) return
        val data = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) System.arraycopy(before, (rect.top + y) * width + rect.left, data, y * rect.width, rect.width)
        push(Step.Pixels(layer, rect, data))
    }

    private fun applyStep(step: Step): Step = when (step) {
        is Step.Pixels -> {
            val back = IntArray(step.data.size)
            val r = step.rect
            for (y in 0 until r.height) {
                val at = (r.top + y) * width + r.left
                System.arraycopy(step.layer.pixels, at, back, y * r.width, r.width)
                System.arraycopy(step.data, y * r.width, step.layer.pixels, at, r.width)
            }
            Step.Pixels(step.layer, r, back)
        }
        is Step.Whole -> {
            val back = capture()
            width = step.width; height = step.height
            layers.clear()
            step.states.forEach { s ->
                s.layer.pixels = s.pixels; s.layer.visible = s.visible; s.layer.opacity = s.opacity; s.layer.blend = s.blend
                s.layer.alphaLock = s.alphaLock; s.layer.clipped = s.clipped; s.layer.name = s.name
                layers += s.layer
            }
            activeId = step.active
            selection = step.selection
            back
        }
    }

    fun undo(): Boolean { val s = undo.removeLastOrNull() ?: return false; redo.addLast(applyStep(s)); return true }
    fun redo(): Boolean { val s = redo.removeLastOrNull() ?: return false; undo.addLast(applyStep(s)); return true }

    /** The picture as seen, in [rect], written into [out] (one int per canvas pixel). */
    fun render(rect: Rect, out: IntArray) {
        val r = rect.clip(width, height)
        if (r.isEmpty) return
        for (y in r.top until r.bottom) java.util.Arrays.fill(out, y * width + r.left, y * width + r.right, background)
        var clipBase: IntArray? = null
        layers.forEach { l ->
            if (!l.clipped) clipBase = l.pixels
            if (!l.visible || l.opacity <= 0f) return@forEach
            val base = if (l.clipped) clipBase else null
            for (y in r.top until r.bottom) {
                val row = y * width
                for (i in row + r.left until row + r.right) {
                    var src = l.pixels[i]
                    if (src ushr 24 == 0) continue
                    if (base != null) {
                        val a = alphaOf(src) * alphaOf(base[i]) / 255
                        if (a == 0) continue
                        src = (src and 0xFFFFFF) or (a shl 24)
                    }
                    out[i] = if (l.blend == Blend.NORMAL && l.opacity >= 1f && src ushr 24 == 255) src else composite(out[i], src, l.blend, l.opacity)
                }
            }
        }
    }

    fun flattened(): IntArray = IntArray(width * height).also { render(Rect(0, 0, width, height), it) }

    /** Lays the active layer onto the one below, keeping how it looked. */
    fun mergeDown() {
        val at = layers.indexOfFirst { it.id == activeId }
        if (at <= 0) return
        change {
            val top = layers[at]
            val under = layers[at - 1]
            val merged = under.pixels.copyOf()
            val clip = if (top.clipped) under.pixels else null
            for (i in merged.indices) {
                var src = top.pixels[i]
                if (src ushr 24 == 0) continue
                if (clip != null) src = (src and 0xFFFFFF) or ((alphaOf(src) * alphaOf(clip[i]) / 255) shl 24)
                merged[i] = composite(merged[i], src, top.blend, top.opacity)
            }
            under.pixels = merged
            layers.removeAt(at)
            activeId = under.id
        }
    }

    fun flatten() = change {
        val all = flattened()
        val l = Layer(nextId++, "Background", all)
        layers.clear()
        layers += l
        activeId = l.id
    }

    fun deleteActive() {
        if (layers.size <= 1) return
        change {
            val at = layers.indexOfFirst { it.id == activeId }
            layers.removeAt(at)
            activeId = layers[(at - 1).coerceAtLeast(0)].id
        }
    }

    fun duplicateActive() = change {
        val a = active
        val copy = a.copy(nextId++, a.name + " copy")
        layers.add(layers.indexOf(a) + 1, copy)
        activeId = copy.id
    }

    fun moveActive(by: Int) {
        val at = layers.indexOfFirst { it.id == activeId }
        val to = at + by
        if (to !in layers.indices) return
        change { layers.add(to, layers.removeAt(at)) }
    }

    /** Copies what's selected to a new layer, or the whole layer if nothing is. */
    fun copySelectionToLayer(cut: Boolean = false) = change {
        val a = active
        val mask = selection
        val fresh = IntArray(width * height)
        val kept = if (cut) a.pixels.copyOf() else a.pixels
        for (i in fresh.indices) if (mask == null || mask[i] != 0.toByte()) {
            fresh[i] = a.pixels[i]
            if (cut) kept[i] = 0
        }
        a.pixels = kept
        val l = Layer(nextId++, if (cut) "Cut" else "Copy", fresh)
        layers.add(layers.indexOf(a) + 1, l)
        activeId = l.id
    }

    fun clearActive() = change {
        val a = active
        val mask = selection
        a.pixels = if (mask == null) IntArray(width * height) else a.pixels.copyOf().also { p -> for (i in p.indices) if (mask[i] != 0.toByte()) p[i] = if (a.alphaLock) p[i] else 0 }
    }

    fun filterActive(filter: Filter, amount: Float) = change {
        val a = active
        val out = Filters.apply(filter, a.pixels, width, height, amount)
        val mask = selection
        if (mask != null) for (i in out.indices) if (mask[i] == 0.toByte()) out[i] = a.pixels[i]
        a.pixels = out
    }

    fun transformActive(scaleX: Float, scaleY: Float, radians: Float, dx: Float, dy: Float, smooth: Boolean = true) = change {
        val a = active
        val mask = selection
        if (mask == null) a.pixels = Raster.transform(a.pixels, width, height, scaleX, scaleY, radians, dx, dy, smooth)
        else {
            // Only the selected part moves, leaving a hole behind it.
            val lifted = IntArray(width * height)
            val rest = a.pixels.copyOf()
            for (i in lifted.indices) if (mask[i] != 0.toByte()) { lifted[i] = rest[i]; rest[i] = 0 }
            val moved = Raster.transform(lifted, width, height, scaleX, scaleY, radians, dx, dy, smooth)
            for (i in rest.indices) rest[i] = composite(rest[i], moved[i], Blend.NORMAL)
            a.pixels = rest
        }
    }

    /** Mirrors the whole picture or the active layer. */
    fun flip(horizontal: Boolean, allLayers: Boolean) = change {
        fun flipped(src: IntArray) = IntArray(src.size).also { out ->
            for (y in 0 until height) for (x in 0 until width) {
                val j = if (horizontal) y * width + (width - 1 - x) else (height - 1 - y) * width + x
                out[j] = src[y * width + x]
            }
        }
        if (allLayers) { layers.forEach { it.pixels = flipped(it.pixels) }; selection = null } else active.pixels = flipped(active.pixels)
    }

    /** Turns the whole canvas a quarter turn, swapping width and height. */
    fun rotate90(clockwise: Boolean) = change {
        val w = width; val h = height
        layers.forEach { l ->
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val (nx, ny) = if (clockwise) (h - 1 - y) to x else y to (w - 1 - x)
                out[ny * h + nx] = l.pixels[y * w + x]
            }
            l.pixels = out
        }
        width = h; height = w
        selection = null
    }

    /** Crops or extends the canvas. [left] and [top] are where the old (0, 0) lands. */
    fun resizeCanvas(newW: Int, newH: Int, left: Int = 0, top: Int = 0) = change {
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

        fun blank(w: Int, h: Int) = Painting(w, h).also { it.newLayer("Layer 1") }
    }
}

/** OpenRaster's layer list: the part of the format that isn't pictures. */
object OraStack {
    data class Entry(val name: String, val src: String, val opacity: Float, val visible: Boolean, val blend: Blend, val alphaLock: Boolean, val clipped: Boolean)

    fun write(width: Int, height: Int, entries: List<Entry>): String = buildString {
        append("<?xml version='1.0' encoding='UTF-8'?>\n<image version=\"0.0.5\" w=\"$width\" h=\"$height\" xres=\"72\" yres=\"72\">\n<stack>\n")
        // OpenRaster lists the top layer first.
        entries.forEach { e ->
            append("<layer name=\"").append(io.github.shoalapps.squill.notes.esc(e.name)).append("\" src=\"").append(io.github.shoalapps.squill.notes.esc(e.src)).append("\" x=\"0\" y=\"0\" opacity=\"")
            append(e.opacity).append("\" visibility=\"").append(if (e.visible) "visible" else "hidden").append("\" composite-op=\"").append(e.blend.ora).append('"')
            if (e.alphaLock) append(" alpha-preserve=\"true\"")
            if (e.clipped) append(" squill-clipped=\"true\"")
            append("/>\n")
        }
        append("</stack>\n</image>\n")
    }

    data class Parsed(val width: Int, val height: Int, val entries: List<Entry>, val offsets: List<Pair<Int, Int>>)

    fun parse(xml: ByteArray): Parsed {
        val p = io.github.shoalapps.squill.notes.parser(xml)
        var w = 0; var h = 0
        val entries = mutableListOf<Entry>()
        val offsets = mutableListOf<Pair<Int, Int>>()
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            if (p.name == "image") { w = p.getAttributeValue(null, "w")?.toIntOrNull() ?: 0; h = p.getAttributeValue(null, "h")?.toIntOrNull() ?: 0 }
            if (p.name == "layer") {
                val src = p.getAttributeValue(null, "src") ?: continue
                entries += Entry(
                    p.getAttributeValue(null, "name") ?: "Layer", src,
                    p.getAttributeValue(null, "opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                    p.getAttributeValue(null, "visibility") != "hidden",
                    Blend.fromOra(p.getAttributeValue(null, "composite-op")),
                    p.getAttributeValue(null, "alpha-preserve") == "true",
                    p.getAttributeValue(null, "squill-clipped") == "true",
                )
                offsets += (p.getAttributeValue(null, "x")?.toIntOrNull() ?: 0) to (p.getAttributeValue(null, "y")?.toIntOrNull() ?: 0)
            }
        }
        return Parsed(w, h, entries, offsets)
    }
}
