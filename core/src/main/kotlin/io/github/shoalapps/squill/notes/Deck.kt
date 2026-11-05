package io.github.shoalapps.squill.notes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** Slides are laid out on this canvas, 16:9, whatever size they end up shown at. */
const val SLIDE_W = 1280f
const val SLIDE_H = 720f

@Serializable
data class Element(
    val id: Long,
    /** "text", "rect", "ellipse", "line" or "image". */
    val kind: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val text: String = "",
    val size: Float = 32f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** "left", "center" or "right". */
    val align: String = "left",
    val colour: Int = 0x1B1B1F,
    /** The inside colour, or -1 for none. */
    val fill: Int = -1,
    /** Which click brings this on when presenting: 0 is there from the start. */
    val step: Int = 0,
    /** A picture's bytes, in Base64, so a deck is one file you can move around. */
    val image: String? = null,
)

@Serializable
data class Slide(val id: Long, val background: Int = 0xFFFFFF, val elements: List<Element> = emptyList(), val notes: String = "") {
    val steps: Int get() = elements.maxOfOrNull { it.step } ?: 0
}

@Serializable
data class Deck(val slides: List<Slide> = listOf(titleSlide(1))) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun parse(text: String): Deck? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.slides.isNotEmpty() }

        fun titleSlide(id: Long) = Slide(
            id,
            elements = listOf(
                Element(id * 10 + 1, "text", 120f, 220f, 1040f, 140f, "Your title", size = 72f, bold = true, align = "center"),
                Element(id * 10 + 2, "text", 200f, 380f, 880f, 80f, "A few words to go with it", size = 34f, colour = 0x5F5F66, align = "center"),
            ),
        )

        fun contentSlide(id: Long) = Slide(
            id,
            elements = listOf(
                Element(id * 10 + 1, "text", 80f, 50f, 1120f, 110f, "A heading", size = 52f, bold = true),
                Element(id * 10 + 2, "text", 80f, 180f, 1120f, 460f, "Your point here", size = 32f),
            ),
        )
    }
}

/** Changes to a deck, as new decks. A deck always keeps at least one slide. */
private fun Deck.nextId(): Long = (slides.maxOfOrNull { it.id } ?: 0L) + 1

fun Deck.addSlide(after: Int): Deck = copy(slides = slides.toMutableList().apply { add((after + 1).coerceIn(0, size), Deck.contentSlide(nextId())) })

fun Deck.duplicateSlide(index: Int): Deck {
    val source = slides.getOrNull(index) ?: return this
    val id = nextId()
    val copied = source.copy(id = id, elements = source.elements.mapIndexed { i, e -> e.copy(id = id * 10 + i + 1) })
    return copy(slides = slides.toMutableList().apply { add(index + 1, copied) })
}

fun Deck.deleteSlide(index: Int): Deck =
    if (slides.size <= 1 || index !in slides.indices) this else copy(slides = slides.filterIndexed { i, _ -> i != index })

fun Deck.moveSlide(from: Int, to: Int): Deck {
    if (from !in slides.indices || to !in slides.indices || from == to) return this
    return copy(slides = slides.toMutableList().apply { add(to, removeAt(from)) })
}

fun Deck.setSlideNotes(index: Int, notes: String): Deck =
    copy(slides = slides.mapIndexed { i, s -> if (i == index) s.copy(notes = notes) else s })

fun Deck.setElementText(index: Int, elementId: Long, text: String): Deck =
    copy(slides = slides.mapIndexed { i, s ->
        if (i != index) s else s.copy(elements = s.elements.map { if (it.id == elementId) it.copy(text = text) else it })
    })

/** PowerPoint files, both ways. Reveal steps stay in Squill; everything visible on a slide travels. */
object Pptx {
    private const val EMU = 9525.0 // Per canvas unit: 1280 units is PowerPoint's 12192000 wide.
    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private const val RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun emu(v: Float) = (v * EMU).toLong()
    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)
    private fun fillXml(fill: Int) = if (fill < 0) "<a:noFill/>" else "<a:solidFill><a:srgbClr val=\"${hex(fill)}\"/></a:solidFill>"

    private fun bytes(s: String) = s.toByteArray()
    private fun xfrm(e: Element) = "<a:xfrm><a:off x=\"${emu(e.x)}\" y=\"${emu(e.y)}\"/><a:ext cx=\"${emu(e.w)}\" cy=\"${emu(e.h)}\"/></a:xfrm>"

    private fun textBody(e: Element): String {
        val algn = when (e.align) { "center" -> "ctr"; "right" -> "r"; else -> "l" }
        val props = "lang=\"en-GB\" sz=\"${(e.size * 75).toInt()}\" b=\"${if (e.bold) 1 else 0}\" i=\"${if (e.italic) 1 else 0}\""
        val paragraphs = e.text.split('\n').joinToString("") { line ->
            "<a:p><a:pPr algn=\"$algn\"/><a:r><a:rPr $props>${fillXml(e.colour)}</a:rPr><a:t>${esc(line)}</a:t></a:r></a:p>"
        }
        return "<p:txBody><a:bodyPr wrap=\"square\"><a:noAutofit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody>"
    }

    fun export(deck: Deck, out: OutputStream) {
        val files = LinkedHashMap<String, ByteArray>()
        val media = LinkedHashMap<String, ByteArray>()
        deck.slides.forEachIndexed { index, slide ->
            val rels = StringBuilder("<Relationship Id=\"rId1\" Type=\"$OFFICE/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>")
            var relId = 2
            val shapes = slide.elements.withIndex().joinToString("") { (i, e) ->
                val id = i + 2
                val name = "<p:cNvPr id=\"$id\" name=\"${e.kind} $id\"/>"
                when (e.kind) {
                    "image" -> {
                        val data = runCatching { Base64.getDecoder().decode(e.image.orEmpty()) }.getOrNull()
                        if (data == null) "" else {
                            val ext = if (data.size > 1 && data[0] == 0x89.toByte()) "png" else "jpeg"
                            val file = "image${index + 1}_${i + 1}.$ext"
                            media["ppt/media/$file"] = data
                            val rid = "rId${relId++}"
                            rels.append("<Relationship Id=\"$rid\" Type=\"$OFFICE/image\" Target=\"../media/$file\"/>")
                            "<p:pic><p:nvPicPr>$name<p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>${xfrm(e)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
                        }
                    }
                    "line" -> "<p:cxnSp><p:nvCxnSpPr>$name<p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr>${xfrm(e)}<a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom><a:ln w=\"${emu(e.size.coerceAtMost(40f) / 4f)}\">${fillXml(e.colour)}</a:ln></p:spPr></p:cxnSp>"
                    "rect", "ellipse" -> "<p:sp><p:nvSpPr>$name<p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(e)}<a:prstGeom prst=\"${e.kind}\"><a:avLst/></a:prstGeom>${fillXml(e.fill)}<a:ln w=\"19050\">${fillXml(e.colour)}</a:ln></p:spPr>${textBody(e)}</p:sp>"
                    else -> "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(e)}<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>${fillXml(e.fill)}</p:spPr>${textBody(e)}</p:sp>"
                }
            }
            files["ppt/slides/slide${index + 1}.xml"] = bytes("$XML<p:sld $NS><p:cSld><p:bg><p:bgPr>${fillXml(slide.background)}<a:effectLst/></p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$shapes</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
            files["ppt/slides/_rels/slide${index + 1}.xml.rels"] = bytes("$XML<Relationships xmlns=\"$RELS\">$rels</Relationships>")
        }
        val count = deck.slides.size
        val slideIds = (1..count).joinToString("") { "<p:sldId id=\"${255 + it}\" r:id=\"rIdSlide$it\"/>" }
        files["ppt/presentation.xml"] = bytes("$XML<p:presentation $NS><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rIdMaster\"/></p:sldMasterIdLst><p:sldIdLst>$slideIds</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        files["ppt/_rels/presentation.xml.rels"] = bytes("$XML<Relationships xmlns=\"$RELS\"><Relationship Id=\"rIdMaster\" Type=\"$OFFICE/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/><Relationship Id=\"rIdTheme\" Type=\"$OFFICE/theme\" Target=\"theme/theme1.xml\"/>" + (1..count).joinToString("") { "<Relationship Id=\"rIdSlide$it\" Type=\"$OFFICE/slide\" Target=\"slides/slide$it.xml\"/>" } + "</Relationships>")
        val tree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>"
        files["ppt/slideMasters/slideMaster1.xml"] = bytes("$XML<p:sldMaster $NS>$tree<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/><p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>")
        files["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = bytes("$XML<Relationships xmlns=\"$RELS\"><Relationship Id=\"rId1\" Type=\"$OFFICE/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/><Relationship Id=\"rId2\" Type=\"$OFFICE/theme\" Target=\"../theme/theme1.xml\"/></Relationships>")
        files["ppt/slideLayouts/slideLayout1.xml"] = bytes("$XML<p:sldLayout $NS type=\"blank\">$tree<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>")
        files["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = bytes("$XML<Relationships xmlns=\"$RELS\"><Relationship Id=\"rId1\" Type=\"$OFFICE/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>")
        files["ppt/theme/theme1.xml"] = bytes(THEME)
        files["_rels/.rels"] = bytes("$XML<Relationships xmlns=\"$RELS\"><Relationship Id=\"rId1\" Type=\"$OFFICE/officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>")
        files["[Content_Types].xml"] = bytes(
            "$XML<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/><Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/><Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/><Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
                (1..count).joinToString("") { "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } + "</Types>",
        )
        files += media
        writeZip(out, files)
    }

    /** A PowerPoint file as a deck: text, shapes, lines, pictures and colours. Animations and masters don't come across. */
    fun import(input: InputStream): Deck {
        val files = readZip(input)
        val presentation = files["ppt/presentation.xml"] ?: throw OfficeException("That isn't a PowerPoint file")
        var scale = 1f / EMU.toFloat()
        val order = mutableListOf<String>()
        parser(presentation).let { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                if (p.name == "p:sldSz") p.getAttributeValue(null, "cx")?.toFloatOrNull()?.takeIf { it > 0 }?.let { scale = SLIDE_W / it }
                if (p.name == "p:sldId") p.getAttributeValue(null, "r:id")?.let { order += it }
            }
        }
        val rels = relationships(files["ppt/_rels/presentation.xml.rels"])
        val slidePaths = order.mapNotNull { rels[it] }.map { "ppt/" + it.removePrefix("/ppt/") }.filter { it in files }
        val slides = slidePaths.ifEmpty { files.keys.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 } }
            .mapIndexed { i, path -> readSlide(files, path, i + 1L, scale) }
        if (slides.isEmpty()) throw OfficeException("There are no slides in that file")
        return Deck(slides)
    }

    private fun relationships(bytes: ByteArray?): Map<String, String> {
        val out = HashMap<String, String>()
        val p = parser(bytes ?: return out)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") out[p.getAttributeValue(null, "Id")] = p.getAttributeValue(null, "Target")
        }
        return out
    }

    private fun readSlide(files: Map<String, ByteArray>, path: String, slideId: Long, scale: Float): Slide {
        val rels = relationships(files[path.replaceAfterLast('/', "_rels/" + path.substringAfterLast('/') + ".rels")])
        val p = parser(files.getValue(path))
        val elements = mutableListOf<Element>()
        var background = 0xFFFFFF
        var kind: String? = null
        var x = 0f; var y = 0f; var w = 0f; var h = 0f; var hasBox = false
        var placeholder: String? = null
        var geometry = "rect"
        var fill = -1
        var line = -1
        var image: String? = null
        val text = StringBuilder()
        var size = 0f; var bold = false; var italic = false; var align = "left"; var colour = -1
        var inBackground = false; var inShapeProps = false; var inLine = false; var inRunProps = false; var inT = false; var inGroup = 0
        var groupX = 0f; var groupY = 0f; var groupSx = 1f; var groupSy = 1f
        var offX = 0f; var offY = 0f; var chOffX = 0f; var chOffY = 0f; var extX = 0f; var extY = 0f; var inGroupProps = false
        var firstPara = true
        var count = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "p:bg" -> inBackground = true
                    "p:grpSp" -> inGroup++
                    "p:grpSpPr" -> inGroupProps = true
                    "p:sp", "p:pic", "p:cxnSp" -> {
                        kind = p.name
                        hasBox = false; placeholder = null; geometry = "rect"; fill = -1; line = -1; image = null
                        text.clear(); size = 0f; bold = false; italic = false; align = "left"; colour = -1; firstPara = true
                    }
                    "p:ph" -> placeholder = p.getAttributeValue(null, "type") ?: "body"
                    "p:spPr" -> inShapeProps = true
                    "a:ln" -> inLine = true
                    "a:off" -> {
                        val ox = p.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f
                        val oy = p.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f
                        if (inGroupProps) { offX = ox; offY = oy } else if (inShapeProps) { x = ox; y = oy; hasBox = true }
                    }
                    "a:ext" -> {
                        val cx = p.getAttributeValue(null, "cx")?.toFloatOrNull()
                        val cy = p.getAttributeValue(null, "cy")?.toFloatOrNull()
                        if (cx != null && cy != null) { if (inGroupProps) { extX = cx; extY = cy } else if (inShapeProps) { w = cx; h = cy } }
                    }
                    "a:chOff" -> if (inGroupProps) { chOffX = p.getAttributeValue(null, "x")?.toFloatOrNull() ?: 0f; chOffY = p.getAttributeValue(null, "y")?.toFloatOrNull() ?: 0f }
                    "a:chExt" -> if (inGroupProps) {
                        val cx = p.getAttributeValue(null, "cx")?.toFloatOrNull() ?: 0f
                        val cy = p.getAttributeValue(null, "cy")?.toFloatOrNull() ?: 0f
                        groupSx = if (cx > 0) extX / cx else 1f
                        groupSy = if (cy > 0) extY / cy else 1f
                        groupX = offX - chOffX * groupSx
                        groupY = offY - chOffY * groupSy
                    }
                    "a:prstGeom" -> if (inShapeProps) geometry = p.getAttributeValue(null, "prst") ?: "rect"
                    "a:srgbClr" -> {
                        val c = p.getAttributeValue(null, "val")?.toIntOrNull(16)
                        if (c != null) when {
                            inBackground -> background = c
                            inRunProps -> colour = c
                            inLine -> line = c
                            inShapeProps -> fill = c
                        }
                    }
                    "a:blip" -> {
                        val target = rels[p.getAttributeValue(null, "r:embed")]
                        if (target != null) {
                            val base = path.substringBeforeLast('/')
                            val resolved = if (target.startsWith("/")) target.removePrefix("/") else normalise("$base/$target")
                            files[resolved]?.let { image = Base64.getEncoder().encodeToString(it) }
                        }
                    }
                    "a:rPr", "a:endParaRPr" -> {
                        inRunProps = p.name == "a:rPr"
                        if (p.name == "a:rPr") {
                            p.getAttributeValue(null, "sz")?.toFloatOrNull()?.let { if (size == 0f) size = it / 75f }
                            if (p.getAttributeValue(null, "b") == "1") bold = true
                            if (p.getAttributeValue(null, "i") == "1") italic = true
                        }
                    }
                    "a:pPr" -> when (p.getAttributeValue(null, "algn")) { "ctr" -> align = "center"; "r" -> align = "right"; "l" -> align = "left" }
                    "a:p" -> { if (!firstPara) text.append('\n'); firstPara = false }
                    "a:t" -> inT = true
                    "a:br" -> text.append('\n')
                }
                XmlPullParser.TEXT -> if (inT) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "p:bg" -> inBackground = false
                    "p:grpSp" -> { inGroup--; if (inGroup == 0) { groupX = 0f; groupY = 0f; groupSx = 1f; groupSy = 1f } }
                    "p:grpSpPr" -> inGroupProps = false
                    "p:spPr" -> inShapeProps = false
                    "a:ln" -> inLine = false
                    "a:rPr" -> inRunProps = false
                    "a:t" -> inT = false
                    "p:sp", "p:pic", "p:cxnSp" -> {
                        count++
                        val id = slideId * 1000 + count
                        val (bx, by, bw, bh) = when {
                            hasBox -> listOf((x * groupSx + groupX) * scale, (y * groupSy + groupY) * scale, w * groupSx * scale, h * groupSy * scale)
                            placeholder == "title" || placeholder == "ctrTitle" -> listOf(80f, 50f, 1120f, 110f)
                            else -> listOf(80f, 180f, 1120f, 460f)
                        }
                        val heading = placeholder == "title" || placeholder == "ctrTitle"
                        val fontSize = if (size > 0f) size else if (heading) 52f else 30f
                        val ink = if (colour >= 0) colour else 0x1B1B1F
                        val body = text.toString().trim('\n')
                        when {
                            kind == "p:pic" -> image?.let { elements += Element(id, "image", bx, by, bw, bh, image = it) }
                            kind == "p:cxnSp" || geometry == "line" -> elements += Element(id, "line", bx, by, bw, bh, colour = if (line >= 0) line else 0x1B1B1F, size = 8f)
                            geometry == "ellipse" -> elements += Element(id, "ellipse", bx, by, bw, bh, body, fontSize, bold, italic, align, ink, fill)
                            fill >= 0 || line >= 0 -> elements += Element(id, "rect", bx, by, bw, bh, body, fontSize, bold || heading, italic, align, ink, fill)
                            body.isNotBlank() -> elements += Element(id, "text", bx, by, bw, bh, body, fontSize, bold || heading, italic, align, ink)
                        }
                        kind = null
                    }
                }
            }
        }
        return Slide(slideId, background, elements)
    }

    private fun normalise(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { part -> if (part == "..") parts.removeLastOrNull() else if (part.isNotEmpty() && part != ".") parts.addLast(part) }
        return parts.joinToString("/")
    }

    private const val THEME = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Squill\"><a:themeElements><a:clrScheme name=\"Squill\"><a:dk1><a:srgbClr val=\"1B1B1F\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"3C3C43\"/></a:dk2><a:lt2><a:srgbClr val=\"F2F2F7\"/></a:lt2><a:accent1><a:srgbClr val=\"4A4FA3\"/></a:accent1><a:accent2><a:srgbClr val=\"0B7A75\"/></a:accent2><a:accent3><a:srgbClr val=\"2E7D32\"/></a:accent3><a:accent4><a:srgbClr val=\"E0A800\"/></a:accent4><a:accent5><a:srgbClr val=\"E65100\"/></a:accent5><a:accent6><a:srgbClr val=\"C62828\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme><a:fontScheme name=\"Squill\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Squill\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
}
