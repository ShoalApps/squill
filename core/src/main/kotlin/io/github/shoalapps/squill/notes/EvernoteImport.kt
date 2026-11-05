package io.github.shoalapps.squill.notes

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/** Offline ENEX import. All writes, including duplicate markers, go through the vault. */
object EvernoteImport {
    /** Attachment skips count only notes newly imported. */
    data class Result(val imported: Int, val duplicates: Int, val skippedAttachments: Int, val failed: Int)
    private const val TEXT_LIMIT = 2 * 1024 * 1024
    private const val IMAGE_LIMIT = 20 * 1024 * 1024
    private const val NOTE_LIMIT = 32 * 1024 * 1024
    private const val TOTAL_LIMIT = 256L * 1024 * 1024
    private const val FILE_LIMIT = 512L * 1024 * 1024
    private val images = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    fun import(vault: Vault, input: InputStream, fileName: String, picture: (ByteArray) -> ByteArray = KeepImport::pngPainting): Result {
        val folder = safeName(fileName.replace('\\', '/').substringAfterLast('/').replace(Regex("(?i)\\.enex$"), ""))
        var imported = 0; var duplicates = 0; var skipped = 0; var failed = 0
        var total = 0L
        notes(input) { xml ->
            if (xml == null) { failed++; return@notes }
            val made = mutableListOf<String>()
            try {
                total += xml.toByteArray(Charsets.UTF_8).size
                require(total <= TOTAL_LIMIT) { "That export is too big" }
                val note = tree(xml, "note")
                val title = note.field("title").ifBlank { "Untitled" }
                val enml = note.field("content")
                require(enml.length <= TEXT_LIMIT)
                val content = tree(enml, "en-note")
                val tags = note.children("tag").map { it.text().trim().replace(Regex("[^\\p{L}\\p{N}_/-]+"), "_").trim('_') }
                    .filter { it.isNotEmpty() }.map { "#" + if (it.first().isLetter()) it else "tag_$it" }.distinct().joinToString(" ")
                var skippedHere = 0
                data class Resource(val name: String, val bytes: ByteArray)
                val resources = linkedMapOf<String, Resource>()
                val knownResources = mutableSetOf<String>()
                val keys = mutableListOf<String>()
                note.children("resource").forEach { r ->
                    try {
                        val data = r.children("data").single()
                        require(data.attrs["encoding"] == "base64")
                        val encoded = data.text().filterNot { it in " \t\r\n" }
                        require(encoded.length <= (IMAGE_LIMIT + 2) / 3 * 4)
                        val bytes = Base64.getDecoder().decode(encoded)
                        require(bytes.size <= IMAGE_LIMIT)
                        val hash = digest(bytes, "MD5")
                        knownResources += hash
                        keys += digest(bytes)
                        require(r.field("mime").lowercase(Locale.ROOT) in images)
                        resources.putIfAbsent(hash, Resource(r.children("resource-attributes").firstOrNull()?.field("file-name").orEmpty().substringBeforeLast('.', "image"), bytes))
                    } catch (_: Exception) { skippedHere++; keys += "skipped:${r.field("mime")}:${digest(r.text().toByteArray())}" }
                }
                // Fingerprint source content, before generated image paths or conversions can change it.
                val identity = listOf(title, markdown(content) { hash -> "[image:$hash]" }, tags) + keys
                val fingerprint = digest(identity.joinToString("") { "${it.length}:$it" }.toByteArray())
                val marker = ".evernote-import/$fingerprint.mark"
                if (vault.exists(marker)) { duplicates++; return@notes }
                val saved = mutableMapOf<String, String?>()
                val missing = mutableSetOf<String>()
                val body = markdown(content) { hash ->
                    if (hash !in saved) {
                        val resource = resources[hash]
                        saved[hash] = if (resource == null) {
                            if (hash !in knownResources && missing.add(hash)) skippedHere++
                            null
                        } else try {
                            val ora = picture(resource.bytes)
                            vault.createFile(folder, safeName(resource.name), "ora") { it.write(ora) }.also { made += it }
                        } catch (_: Exception) { skippedHere++; null }
                    }
                    saved[hash]?.let { "![[$it]]" }.orEmpty()
                }
                val created = vault.create(folder, safeName(title), listOf(body, tags).filter { it.isNotBlank() }.joinToString("\n\n"))
                made += created.path
                val modified = timestamp(note.field("updated")) ?: timestamp(note.field("created"))
                modified?.takeIf { it > 0 }?.let { vault.file(created.path).setLastModified(it) }
                vault.createFile(".evernote-import", fingerprint, "mark") { }
                imported++; skipped += skippedHere + Regex("<en-crypt\\b").findAll(enml).count()
            } catch (_: Exception) { made.forEach { vault.file(it).delete() }; failed++ }
        }
        return Result(imported, duplicates, skipped, failed)
    }

    private fun timestamp(value: String): Long? = runCatching {
        Instant.from(DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssX").withResolverStyle(java.time.format.ResolverStyle.STRICT).parse(value)).toEpochMilli()
    }.getOrNull()
    private fun safeName(value: String) = value.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|#^\\[\\]]"), " ").trim { it == '.' || it.isWhitespace() }.ifBlank { "Untitled" }.take(120)
    private fun digest(bytes: ByteArray, algorithm: String = "SHA-256") = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Node(val name: String, val attrs: Map<String, String>, val parts: MutableList<Any> = mutableListOf()) {
        fun children(name: String) = parts.filterIsInstance<Node>().filter { it.name == name }
        fun text(): String = parts.joinToString("") { if (it is Node) it.text() else it.toString() }
        fun field(name: String) = children(name).firstOrNull()?.text().orEmpty()
    }

    private fun tree(xml: String, rootName: String): Node {
        val p = parser(xml.toByteArray())
        // Android can disable this feature explicitly. kxml2 never supports it at all,
        // and throws even when asked to set false; verify it is off in either case.
        runCatching { p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        require(!p.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL))
        // Common ENML whitespace entities are fixed locally; never read a DTD to resolve them.
        if (rootName == "en-note") {
            mapOf("nbsp" to "\u00a0", "ensp" to "\u2002", "emsp" to "\u2003", "ndash" to "–", "mdash" to "—",
                "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "hellip" to "…").forEach { (name, value) ->
                p.defineEntityReplacementText(name, value)
            }
        }
        val stack = mutableListOf<Node>()
        var root: Node? = null
        var count = 0
        var textSize = 0
        while (p.nextToken() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    require(stack.size < 128 && ++count <= 100_000)
                    val n = Node(p.name.lowercase(Locale.ROOT), (0 until p.attributeCount).associate { p.getAttributeName(it) to p.getAttributeValue(it) })
                    if (stack.isEmpty()) { require(root == null && n.name == rootName); root = n }
                    else stack.last().parts += n
                    stack += n
                }
                XmlPullParser.END_TAG -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex) }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val text = p.text ?: error("Unknown entity")
                    if (stack.lastOrNull()?.name != "data") { textSize += text.length; require(textSize <= TEXT_LIMIT) }
                    if (stack.isNotEmpty()) {
                        require(text.length <= if (stack.last().name == "data") NOTE_LIMIT else TEXT_LIMIT)
                        stack.last().parts += text
                    } else require(text.isBlank())
                }
                // kxml2 does not process declarations or fetch their system identifiers.
                XmlPullParser.DOCDECL -> require(root == null)
            }
        }
        require(stack.isEmpty())
        return root ?: error("That isn't a note")
    }

    private fun markdown(root: Node, media: (String) -> String): String {
        fun escape(s: String) = s.replace(Regex("([\\\\*_`~\\[\\]])"), "\\\\$1")
        fun render(n: Node, lists: List<String> = emptyList(), pre: Boolean = false): String {
            fun body(raw: Boolean = pre) = n.parts.joinToString("") { if (it is Node) render(it, lists, raw) else if (raw) it.toString() else escape(it.toString().replace(Regex("[\\s\u00a0\u2002\u2003]+"), " ")) }
            fun wrapped(mark: String): String {
                val b = body(); return b.takeWhile { it == ' ' } + mark + b.trim() + mark + b.takeLastWhile { it == ' ' }
            }
            return when (n.name) {
                "script", "style", "iframe", "object" -> ""
                "en-crypt" -> "\n\n*Encrypted text from Evernote wasn't brought in. It's still in your Evernote account.*\n\n"
                "en-media" -> "\n\n" + media(n.attrs["hash"].orEmpty().lowercase(Locale.ROOT)) + "\n\n"
                "en-todo" -> "- [${if (n.attrs["checked"] == "true") "x" else " "}] "
                // Squill folds single newlines into spaces, so separate visible lines as paragraphs.
                "br" -> if (pre) "\n" else "\n\n"
                "b", "strong" -> wrapped("**")
                "i", "em" -> wrapped("*")
                "code", "tt" -> if (pre) body(true) else "`" + n.text().replace('`', '\'') + "`"
                "pre" -> "\n\n```\n" + n.text().replace("```", "`` `") + "\n```\n\n"
                "a" -> {
                    val url = n.attrs["href"].orEmpty()
                    if (Regex("(?i)^(https?://|mailto:)").containsMatchIn(url) && url.none { it.isWhitespace() || it < ' ' })
                        "[${body()}](${url.replace("(", "%28").replace(")", "%29")})" else body()
                }
                "ul", "ol" -> "\n" + n.parts.joinToString("") { if (it is Node) render(it, lists + n.name, pre) else "" } + "\n"
                "li" -> {
                    val b = body().trim()
                    "  ".repeat((lists.size - 1).coerceAtLeast(0)) + (if (b.startsWith("- [")) "" else if (lists.lastOrNull() == "ol") "1. " else "- ") + b + "\n"
                }
                "table" -> {
                    fun rows(node: Node): List<Node> = node.parts.filterIsInstance<Node>().flatMap { if (it.name == "tr") listOf(it) else rows(it) }
                    val rows = rows(n).map { row -> row.parts.filterIsInstance<Node>().filter { it.name in listOf("td", "th") }.map { render(it, lists).trim().replace("\n", " ").replace("|", "\\|") } }
                    val cols = rows.maxOfOrNull { it.size } ?: 0
                    require(cols.toLong() * rows.size <= TEXT_LIMIT / 4) { "That table is too big" }
                    fun line(row: List<String>) = "| " + (0 until cols).joinToString(" | ") { row.getOrElse(it) { "" } } + " |\n"
                    if (cols == 0) "" else "\n\n" + line(rows.first()) + "|" + "---|".repeat(cols) + "\n" + rows.drop(1).joinToString("") { line(it) } + "\n"
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> "\n\n" + "#".repeat(n.name.last().digitToInt()) + " " + body().trim() + "\n\n"
                "div", "p" -> if (Regex("-en-codeblock\\s*:\\s*true", RegexOption.IGNORE_CASE).containsMatchIn(n.attrs["style"].orEmpty())) {
                    "\n\n```\n" + body(true).trim().replace(Regex("\\n{2,}"), "\n").replace("```", "`` `") + "\n```\n\n"
                } else if (pre) "\n" + body().trim() + "\n" else "\n\n" + body().trim() + "\n\n"
                else -> body()
            }
        }
        val rendered = render(root)
        require(rendered.length <= 4 * TEXT_LIMIT) { "That note is too big" }
        return rendered.replace(Regex("\n[ \\t]+\n"), "\n\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Frame notes without interpreting XML. CDATA, comments and quoted declarations cannot create notes.
     * A malformed note is parsed separately, so it cannot hide the next complete note. Oversized frames
     * are discarded while scanning continues; no whole-export buffer or temporary plaintext is needed. */
    private fun notes(input: InputStream, accept: (String?) -> Unit) {
        var bytes = 0L
        val bounded = object : java.io.FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) require(++bytes <= FILE_LIMIT) { "That export is too big" } }
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) { bytes += it; require(bytes <= FILE_LIMIT) { "That export is too big" } } }
        }
        val reader = bounded.reader(Charsets.UTF_8).buffered()
        var note: StringBuilder? = null
        var oversized = false
        var seenRoot = false
        var count = 0
        fun append(c: Char) { note?.let { if (it.length < NOTE_LIMIT) it.append(c) else oversized = true } }
        while (true) {
            val v = reader.read(); if (v < 0) break
            val c = v.toChar()
            if (c != '<') { append(c); continue }
            val token = StringBuilder("<")
            var quote: Char? = null
            var brackets = 0
            var end = false
            var previous = '<'
            var beforePrevious = ' '
            var mode = 0 // 1: comment, 2: CDATA
            while (!end) {
                val next = reader.read(); if (next < 0) break
                val ch = next.toChar()
                if (token.length < NOTE_LIMIT) token.append(ch) else oversized = true
                if (token.length == 4 && token.toString() == "<!--") mode = 1
                if (token.length == 9 && token.toString() == "<![CDATA[") mode = 2
                end = when {
                    mode == 2 -> beforePrevious == ']' && previous == ']' && ch == '>'
                    mode == 1 -> beforePrevious == '-' && previous == '-' && ch == '>'
                    quote != null -> { if (ch == quote) quote = null; false }
                    ch == '\'' || ch == '"' -> { quote = ch; false }
                    ch == '[' -> { brackets++; false }
                    ch == ']' -> { brackets--; false }
                    else -> ch == '>' && brackets == 0
                }
                beforePrevious = previous
                previous = ch
            }
            val tag = token.toString()
            if (Regex("<en-export(?:\\s[^<>]*)?>").matches(tag)) seenRoot = true
            if (Regex("<note(?:\\s[^<>]*)?>").matches(tag)) {
                require(seenRoot) { "That isn't an Evernote export" }
                require(++count <= 20_000) { "Too many notes in that file" }
                if (note != null) accept(null)
                note = StringBuilder(); oversized = false
            }
            tag.forEach(::append)
            if (tag == "</note>" && note != null) {
                accept(if (oversized) null else note.toString()); note = null; oversized = false
            }
        }
        if (note != null) accept(null)
        require(seenRoot) { "That isn't an Evernote export" }
    }
}
