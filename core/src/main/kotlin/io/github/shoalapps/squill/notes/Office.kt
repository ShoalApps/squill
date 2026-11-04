package io.github.shoalapps.squill.notes

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OfficeException(message: String) : Exception(message)

/** Refuses files built to exhaust memory: a zip can expand a thousandfold. */
fun readZip(input: InputStream, limit: Long = 256L * 1024 * 1024): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    var total = 0L
    ZipInputStream(input).use { zip ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val bytes = ByteArrayOutputStream()
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw OfficeException("That file is too big to open")
                bytes.write(buffer, 0, n)
            }
            out[entry.name] = bytes.toByteArray()
        }
    }
    if (out.isEmpty()) throw OfficeException("That file couldn't be read")
    return out
}

fun writeZip(output: OutputStream, files: Map<String, ByteArray>) {
    ZipOutputStream(output).use { zip ->
        files.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}

fun esc(s: String) = buildString(s.length) {
    s.forEach { c ->
        when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            // Control characters are not allowed in XML at all.
            c < ' ' && c != '\n' && c != '\t' && c != '\r' -> Unit
            else -> append(c)
        }
    }
}

fun parser(bytes: ByteArray): XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser().also {
    // DOCTYPEs aren't processed by this parser, so a file can't pull in outside entities.
    it.setInput(StringReader(bytes.toString(Charsets.UTF_8)))
}

/** Word documents to and from Markdown: headings, emphasis, lists, quotes, code, tables and links. */
object Docx {
    fun export(markdown: String, out: OutputStream) {
        val links = mutableListOf<String>()
        val body = StringBuilder()
        fun run(text: String, i: Inline) = buildString {
            append("<w:r><w:rPr>")
            if (i.bold) append("<w:b/>")
            if (i.italic) append("<w:i/>")
            if (i.strike) append("<w:strike/>")
            if (i.code) append("<w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\"/>")
            if (i.url != null) append("<w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/>")
            append("</w:rPr><w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r>")
        }
        fun runs(line: String): String = Markdown.inlines(line).joinToString("") { i ->
            val url = i.url
            if (url != null) {
                links += url
                "<w:hyperlink r:id=\"rIdLink${links.size}\">${run(i.text, i)}</w:hyperlink>"
            } else run(i.text, i)
        }
        fun para(style: String?, content: String, extra: String = "") =
            "<w:p><w:pPr>" + (style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: "") + extra + "</w:pPr>$content</w:p>"
        Markdown.parse(markdown).forEach { b ->
            body.append(
                when (b) {
                    is Block.Heading -> para("Heading${b.level.coerceAtMost(6)}", runs(b.text))
                    is Block.Paragraph -> para(null, runs(b.text))
                    is Block.Bullet -> para("ListParagraph", runs(b.text), numbering(1, b.indent))
                    is Block.Numbered -> para("ListParagraph", runs(b.text), numbering(2, b.indent))
                    is Block.Task -> para("ListParagraph", run(if (b.done) "☑ " else "☐ ", Inline("")) + runs(b.text), numbering(0, b.indent).ifEmpty { "" })
                    is Block.Quote -> b.text.split('\n').joinToString("") { para("Quote", runs(it)) }
                    is Block.Code -> b.text.split('\n').joinToString("") { para("Code", run(it, Inline("", code = true))) }
                    is Block.Rule -> "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr></w:pPr></w:p>"
                    is Block.Embed -> para(null, run("[Drawing: ${b.target.removeSuffix(".svg")}]", Inline("", italic = true)))
                    is Block.Table -> {
                        val cols = (listOf(b.header) + b.rows).maxOf { it.size }
                        val grid = (0 until cols).joinToString("") { "<w:gridCol w:w=\"${9000 / cols}\"/>" }
                        fun row(cells: List<String>, head: Boolean) = "<w:tr>" + (0 until cols).joinToString("") { c ->
                            val content = cells.getOrElse(c) { "" }
                            "<w:tc><w:tcPr><w:tcW w:w=\"${9000 / cols}\" w:type=\"dxa\"/></w:tcPr><w:p>" +
                                (if (head) run(Markdown.inlines(content).joinToString("") { it.text }, Inline("", bold = true)) else runs(content)) + "</w:p></w:tc>"
                        } + "</w:tr>"
                        "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>" +
                            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BBBBBB\"/>" } +
                            "</w:tblBorders></w:tblPr><w:tblGrid>$grid</w:tblGrid>" + row(b.header, true) + b.rows.joinToString("") { row(it, false) } + "</w:tbl><w:p/>"
                    }
                },
            )
        }
        val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val document = "$XML<w:document $W><w:body>$body<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        val headings = (1..6).joinToString("") { n ->
            val size = listOf(40, 32, 28, 24, 22, 22)[n - 1]
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"$size\"/></w:rPr></w:style>"
        }
        val styles = "$XML<w:styles $W><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>$headings" +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:ind w:left=\"360\"/></w:pPr><w:rPr><w:i/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Courier New\" w:hAnsi=\"Courier New\"/><w:sz w:val=\"20\"/></w:rPr></w:style></w:styles>"
        fun level(i: Int, format: String, text: String) = "<w:lvl w:ilvl=\"$i\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$format\"/><w:lvlText w:val=\"$text\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 * (i + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        val numbering = "$XML<w:numbering $W>" +
            "<w:abstractNum w:abstractNumId=\"1\">" + (0..5).joinToString("") { level(it, "bullet", "•") } + "</w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"2\">" + (0..5).joinToString("") { level(it, "decimal", "%${it + 1}.") } + "</w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"2\"/></w:num></w:numbering>"
        val rels = "$XML<Relationships xmlns=\"$RELS\">" +
            "<Relationship Id=\"rIdStyles\" Type=\"$OFFICE/styles\" Target=\"styles.xml\"/><Relationship Id=\"rIdNum\" Type=\"$OFFICE/numbering\" Target=\"numbering.xml\"/>" +
            links.withIndex().joinToString("") { (i, url) -> "<Relationship Id=\"rIdLink${i + 1}\" Type=\"$OFFICE/hyperlink\" Target=\"${esc(url)}\" TargetMode=\"External\"/>" } + "</Relationships>"
        writeZip(
            out,
            mapOf(
                "[Content_Types].xml" to "$XML<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/><Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>",
                "_rels/.rels" to "$XML<Relationships xmlns=\"$RELS\"><Relationship Id=\"rId1\" Type=\"$OFFICE/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
                "word/_rels/document.xml.rels" to rels,
                "word/document.xml" to document,
                "word/styles.xml" to styles,
                "word/numbering.xml" to numbering,
            ).mapValues { it.value.toByteArray() },
        )
    }

    private fun numbering(id: Int, level: Int) = if (id == 0) "" else "<w:numPr><w:ilvl w:val=\"${level.coerceIn(0, 5)}\"/><w:numId w:val=\"$id\"/></w:numPr>"

    /** A Word file as Markdown. What Markdown can't hold, such as fonts and page layout, is left behind. */
    fun import(input: InputStream): String {
        val files = readZip(input)
        val doc = files["word/document.xml"] ?: throw OfficeException("That isn't a Word document")
        val links = HashMap<String, String>()
        files["word/_rels/document.xml.rels"]?.let { rels ->
            val p = parser(rels)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" && p.getAttributeValue(null, "TargetMode") == "External") {
                    links[p.getAttributeValue(null, "Id")] = p.getAttributeValue(null, "Target")
                }
            }
        }
        val headingNames = HashMap<String, Int>()
        // Word often makes a list through the paragraph's style, not the paragraph itself.
        val bulletStyles = HashSet<String>()
        val numberStyles = HashSet<String>()
        val styleNumbers = HashMap<String, String>()
        val orderedNumbers = HashSet<String>()
        files["word/styles.xml"]?.let { styles ->
            val p = parser(styles)
            var id: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                if (p.name == "w:style") id = p.getAttributeValue(null, "w:styleId")
                if (p.name == "w:name" && id != null) Regex("heading (\\d)", RegexOption.IGNORE_CASE).matchEntire(p.getAttributeValue(null, "w:val").orEmpty())?.let { headingNames[id!!] = it.groupValues[1].toInt() }
                if (p.name == "w:name" && id != null) {
                    val name = p.getAttributeValue(null, "w:val").orEmpty().lowercase()
                    if (name.startsWith("list bullet")) bulletStyles += id!!
                    if (name.startsWith("list number")) numberStyles += id!!
                }
                if (p.name == "w:numId" && id != null) p.getAttributeValue(null, "w:val")?.let { styleNumbers[id!!] = it }
            }
        }
        files["word/numbering.xml"]?.let { num ->
            val p = parser(num)
            val decimal = HashSet<String>()
            var abstract: String? = null
            var firstLevel = false
            var num2: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "w:abstractNum" -> abstract = p.getAttributeValue(null, "w:abstractNumId")
                    "w:lvl" -> firstLevel = p.getAttributeValue(null, "w:ilvl") == "0"
                    "w:numFmt" -> if (firstLevel && p.getAttributeValue(null, "w:val") != "bullet") abstract?.let { decimal += it }
                    "w:num" -> num2 = p.getAttributeValue(null, "w:numId")
                    "w:abstractNumId" -> if (p.getAttributeValue(null, "w:val") in decimal) num2?.let { orderedNumbers += it }
                }
            }
        }
        val out = StringBuilder()
        val p = parser(doc)
        var style: String? = null
        var numId: String? = null
        var indent = 0
        var bold = false; var italic = false; var strike = false; var code = false
        var inRun = false; var inProps = false; var inText = false
        var url: String? = null
        val line = StringBuilder()
        val text = StringBuilder()
        var counter = HashMap<Int, Int>()
        var row: MutableList<String>? = null
        var table: MutableList<List<String>>? = null
        fun flushRun() {
            if (text.isEmpty()) return
            var t = text.toString()
            text.clear()
            if (!code) t = t.replace(Regex("([\\\\*_`~\\[\\]])"), "\\\\$1")
            val lead = t.takeWhile { it == ' ' }
            val trail = t.takeLastWhile { it == ' ' }.takeIf { lead.length != t.length }.orEmpty()
            var core = t.trim(' ')
            if (core.isNotEmpty()) {
                if (code) core = "`$core`"
                if (strike) core = "~~$core~~"
                if (bold && italic) core = "***$core***" else if (bold) core = "**$core**" else if (italic) core = "*$core*"
                url?.let { core = "[$core]($it)" }
            }
            line.append(lead).append(core).append(trail)
        }
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { style = null; numId = null; indent = 0; line.clear() }
                    "w:pStyle" -> style = p.getAttributeValue(null, "w:val")
                    "w:numId" -> numId = p.getAttributeValue(null, "w:val")
                    "w:ilvl" -> indent = p.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 0
                    "w:r" -> { inRun = true; bold = false; italic = false; strike = false; code = false }
                    "w:t" -> inText = true
                    "w:rPr" -> inProps = inRun
                    "w:b" -> if (inProps) bold = p.getAttributeValue(null, "w:val") !in listOf("0", "false")
                    "w:i" -> if (inProps) italic = p.getAttributeValue(null, "w:val") !in listOf("0", "false")
                    "w:strike" -> if (inProps) strike = p.getAttributeValue(null, "w:val") !in listOf("0", "false")
                    "w:rFonts" -> if (inProps) code = p.getAttributeValue(null, "w:ascii")?.contains("Courier", ignoreCase = true) == true || p.getAttributeValue(null, "w:ascii")?.contains("Consolas", ignoreCase = true) == true
                    "w:hyperlink" -> url = links[p.getAttributeValue(null, "r:id")]
                    "w:br", "w:cr" -> if (inRun) text.append(' ')
                    "w:tab" -> if (inRun) text.append(' ')
                    "w:tbl" -> table = mutableListOf()
                    "w:tr" -> row = mutableListOf()
                }
                XmlPullParser.TEXT -> if (inText && p.text != null) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:rPr" -> inProps = false
                    "w:t" -> inText = false
                    "w:r" -> { flushRun(); inRun = false }
                    "w:hyperlink" -> url = null
                    "w:p" -> {
                        val content = line.toString().trim()
                        val r = row
                        if (r != null) {
                            if (r.isNotEmpty() && r.last().endsWith("\u0000")) r[r.lastIndex] = r.last().dropLast(1) + " " + content + "\u0000"
                            else r += content + "\u0000"
                        } else {
                            val styled = style
                            if (numId == null && styled != null) numId = styleNumbers[styled]
                            val bullet = styled in bulletStyles
                            val numbered = styled in numberStyles
                            val h = style?.let { headingNames[it] ?: Regex("Heading(\\d)", RegexOption.IGNORE_CASE).matchEntire(it)?.groupValues?.get(1)?.toInt() }
                            when {
                                content.isEmpty() -> Unit
                                h != null -> { out.append("#".repeat(h.coerceIn(1, 6))).append(' ').append(stripBold(content)).append("\n\n"); counter = HashMap() }
                                bullet || numbered || (numId != null && numId != "0") -> {
                                    val pad = "  ".repeat(indent.coerceAtLeast(0))
                                    if (numbered || (!bullet && numId in orderedNumbers)) {
                                        val n = (counter[indent] ?: 0) + 1
                                        counter[indent] = n
                                        out.append(pad).append(n).append(". ").append(content).append('\n')
                                    } else out.append(pad).append("- ").append(content).append('\n')
                                }
                                style.equals("Quote", ignoreCase = true) -> out.append("> ").append(content).append("\n\n")
                                else -> {
                                    if (out.isNotEmpty() && out.last() == '\n' && !out.endsWith("\n\n")) out.append('\n')
                                    out.append(content).append("\n\n")
                                    counter = HashMap()
                                }
                            }
                        }
                    }
                    "w:tc" -> row?.let { r -> if (r.isNotEmpty()) r[r.lastIndex] = r.last().removeSuffix("\u0000") }
                    "w:tr" -> { row?.let { table?.add(it.map { c -> c.replace("|", "\\|") }) }; row = null }
                    "w:tbl" -> {
                        val rows = table.orEmpty()
                        table = null
                        if (rows.isNotEmpty()) {
                            if (!out.endsWith("\n\n") && out.isNotEmpty()) out.append('\n')
                            val cols = rows.maxOf { it.size }
                            fun line(cells: List<String>) = "| " + (0 until cols).joinToString(" | ") { stripBold(cells.getOrElse(it) { "" }) } + " |\n"
                            out.append(line(rows.first())).append("|" + "---|".repeat(cols) + "\n")
                            rows.drop(1).forEach { out.append(line(it)) }
                            out.append('\n')
                        }
                    }
                }
            }
        }
        return out.toString().trimEnd() + "\n"
    }

    private fun stripBold(s: String) = if (s.startsWith("**") && s.endsWith("**") && s.length > 4 && "**" !in s.substring(2, s.length - 2)) s.substring(2, s.length - 2) else s

    private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
}
