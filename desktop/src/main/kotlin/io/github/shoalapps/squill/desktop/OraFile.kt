package io.github.shoalapps.squill.desktop

import io.github.shoalapps.squill.paint.*
import io.github.shoalapps.squill.notes.readZip
import org.jetbrains.skia.*
import java.io.*
import java.util.zip.*
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** A snapshot owned by the editor, never mutated while a save uses it. */
class PaintingCopy(val painting: Painting, val extras: Map<String, ByteArray> = emptyMap(), val readOnlyReason: String? = null, val originalBytes: ByteArray? = null, val sources: Map<Long, String> = emptyMap(), val preview: ByteArray? = null) {
    fun frozen() = PaintingCopy(painting.snapshot(true), extras, readOnlyReason, originalBytes, sources, preview)
}

/** Desktop PNG codec uses unpremultiplied channels to retain translucent layer pixels. */
object OraFile {
    private fun info(w: Int, h: Int) = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    private fun rgba(pixels: IntArray) = ByteArray(pixels.size * 4).also { out -> pixels.forEachIndexed { i, p ->
        out[i * 4] = (p shr 16).toByte(); out[i * 4 + 1] = (p shr 8).toByte(); out[i * 4 + 2] = p.toByte(); out[i * 4 + 3] = (p ushr 24).toByte()
    } }
    fun image(pixels: IntArray, w: Int, h: Int): Image = Image.makeRaster(info(w, h), rgba(pixels), w * 4)
    private fun png(pixels: IntArray, w: Int, h: Int) = image(pixels, w, h).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { d -> d.bytes } }
    fun load(bytes: ByteArray): PaintingCopy {
        val files = readZip(ByteArrayInputStream(bytes))
        val xml = files["stack.xml"] ?: error("That isn't an OpenRaster file")
        val parsed = OraStack.parse(xml)
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        require(parsed.width in 1..Painting.MAX_SIDE && parsed.height in 1..Painting.MAX_SIDE) { "That picture is too big to open" }
        // A small file can ask for huge layers, so see if they'd fit before unpacking any.
        fun groupDepth(items: List<OraStack.Item>): Int = items.filterIsInstance<OraStack.GroupEntry>().maxOfOrNull { 1 + groupDepth(it.children) } ?: 0
        val needed = (parsed.entries.size.toLong() + groupDepth(parsed.items)) * parsed.width * parsed.height * 4
        require(needed <= Runtime.getRuntime().maxMemory() / 2) { "That painting has too many large layers to open here" }
        var reason: String? = null
        fun unsupported() { reason = "Read only: this painting has features this editor cannot keep. Open it in the app that made it." }
        fun inspect(e: Element) {
            val allowed = when(e.tagName) {
                "image" -> setOf("version", "w", "h", "xres", "yres")
                "stack" -> if (e.parentNode == root) emptySet() else setOf("name", "x", "y", "opacity", "visibility", "composite-op", "isolation", "squill-clipped")
                "layer" -> setOf("name", "src", "x", "y", "opacity", "visibility", "composite-op", "alpha-preserve", "squill-clipped")
                else -> { unsupported(); emptySet() }
            }
            for (i in 0 until e.attributes.length) if (e.attributes.item(i).nodeName !in allowed) unsupported()
            if (e.tagName == "image" && e !== root) unsupported()
            if (e.tagName == "stack" && e.parentNode != root && (e.parentNode as? Element)?.tagName != "stack") unsupported()
            if (e.tagName == "stack" && e.parentNode != root && e.getAttribute("isolation") !in listOf("", "isolate", "auto")) unsupported()
            if (e.tagName == "layer" || (e.tagName == "stack" && e.parentNode != root)) {
                if ((e.parentNode as? Element)?.tagName != "stack" || (e.tagName == "layer" && e.getAttribute("src").isEmpty())) unsupported()
                if (e.getAttribute("composite-op").let { op -> op.isNotEmpty() && Blend.entries.none { it.ora == op } }) unsupported()
                if (e.tagName == "layer" && listOf("x", "y").any { e.getAttribute(it).let { v -> v.isNotEmpty() && v != "0" } }) unsupported()
                if (e.getAttribute("visibility") !in listOf("", "visible", "hidden")) unsupported()
                for (name in listOf("alpha-preserve", "squill-clipped")) if (e.getAttribute(name) !in listOf("", "true", "false")) unsupported()
                if (e.getAttribute("opacity").let { it.isNotEmpty() && (it.toFloatOrNull()?.let { n -> n.isFinite() && n in 0f..1f } != true) }) unsupported()
            }
            for (i in 0 until e.childNodes.length) (e.childNodes.item(i) as? Element)?.let(::inspect)
        }
        inspect(root)
        val stacks = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }.count { it.tagName == "stack" }
        if (stacks != 1) unsupported()
        if (root.tagName != "image" || parsed.entries.isEmpty()) unsupported()
        // These image attributes have no engine representation; don't rewrite their values.
        for ((name, default) in listOf("version" to "0.0.5", "xres" to "72", "yres" to "72")) {
            if (root.getAttribute(name).let { it.isNotEmpty() && it != default }) unsupported()
        }
        val decodedEntries = java.util.IdentityHashMap<IntArray, OraStack.Entry>()
        val p = OraStack.toPainting(parsed) { e ->
            val data = files[e.src] ?: error("A layer's picture is missing")
            val pixels = IntArray(parsed.width * parsed.height)
            Data.makeFromBytes(data).use { encoded -> Codec.makeFromData(encoded).use { image ->
                require(image.width in 1..Painting.MAX_SIDE && image.height in 1..Painting.MAX_SIDE) { "A layer is too big to open" }
                if (image.width != parsed.width || image.height != parsed.height) unsupported()
                val raw = Bitmap().use { bitmap ->
                    check(bitmap.allocPixels(info(image.width, image.height)))
                    image.readPixels(bitmap)
                    bitmap.readPixels(info(image.width, image.height), image.width * 4, 0, 0) ?: error("A layer couldn't be read")
                }
                val ox = e.x; val oy = e.y
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val tx = x + ox; val ty = y + oy; val at = (y * image.width + x) * 4
                    if (tx in 0 until parsed.width && ty in 0 until parsed.height) pixels[ty * parsed.width + tx] =
                        ((raw[at + 3].toInt() and 255) shl 24) or ((raw[at].toInt() and 255) shl 16) or ((raw[at + 1].toInt() and 255) shl 8) or (raw[at + 2].toInt() and 255)
                }
            } }
            decodedEntries[pixels] = e
            pixels
        }
        p.assistants = files[AssistantFile.NAME]?.let { AssistantFile.parse(it.toString(Charsets.UTF_8)) }.orEmpty()
        val used = parsed.entries.map { it.src }.toSet() + setOf("mimetype", "stack.xml", "mergedimage.png", "Thumbnails/thumbnail.png", AssistantFile.NAME)
        return PaintingCopy(p, files.filterKeys { it !in used }, reason, bytes,
            p.layers.mapNotNull { layer -> decodedEntries[layer.pixels]?.let { layer.id to it.src } }.toMap(), if (reason != null) files["mergedimage.png"] else null)
    }
    fun save(copy: PaintingCopy, output: OutputStream) {
        require(copy.readOnlyReason == null) { copy.readOnlyReason!! }
        val p = copy.painting
        ZipOutputStream(output).use { zip ->
            fun put(name: String, bytes: ByteArray, stored: Boolean = false) {
                zip.putNextEntry(ZipEntry(name).apply { if (stored) { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().also { it.update(bytes) }.value } })
                zip.write(bytes); zip.closeEntry()
            }
            put("mimetype", "image/openraster".toByteArray(), true)
            // Retain existing source paths, including references an extension might use.
            val usedNames = (copy.extras.keys + setOf("mimetype", "stack.xml", "mergedimage.png", "Thumbnails/thumbnail.png", AssistantFile.NAME)).toMutableSet()
            val reserved = copy.sources.values.toSet()
            val indices = p.layers.withIndex().associate { it.value.id to it.index }
            val entries = p.toOra { l ->
                val i = indices.getValue(l.id)
                val original = copy.sources[l.id]?.takeIf { it !in usedNames }
                var src = original ?: "data/squill-layer$i.png"
                if (original == null) while (src in usedNames || src in reserved) src = "data/_" + src.substringAfter('/')
                usedNames += src
                put(src, png(l.pixels, p.width, p.height))
                src
            }
            put("stack.xml", OraStack.write(p.width, p.height, entries).toByteArray())
            val flattened = p.flattened()
            put("mergedimage.png", png(flattened, p.width, p.height))
            val scale = minOf(1f, 256f / maxOf(p.width, p.height))
            val w = (p.width * scale).toInt().coerceAtLeast(1)
            val h = (p.height * scale).toInt().coerceAtLeast(1)
            val thumbnail = Surface.makeRasterN32Premul(w, h).use { surface ->
                image(flattened, p.width, p.height).use { image ->
                    surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
                }
                surface.makeImageSnapshot().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
            }
            put("Thumbnails/thumbnail.png", thumbnail)
            if (p.assistants.isNotEmpty()) put(AssistantFile.NAME, AssistantFile.write(p.assistants).toByteArray())
            copy.extras.filterKeys { it != AssistantFile.NAME }.forEach { (name, bytes) -> put(name, bytes) }
        }
    }
}
