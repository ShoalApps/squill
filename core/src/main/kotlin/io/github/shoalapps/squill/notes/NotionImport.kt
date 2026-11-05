package io.github.shoalapps.squill.notes

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Imports Notion's offline Markdown & CSV export, including one level of export parts. */
object NotionImport {
    /** Attachment skips (including oversized CSVs) count only newly imported content. */
    data class Result(val imported: Int, val duplicates: Int, val skippedAttachments: Int, val failed: Int)
    private const val TEXT_LIMIT = 2 * 1024 * 1024
    private const val IMAGE_LIMIT = 20 * 1024 * 1024
    private const val ZIP_LIMIT = 512L * 1024 * 1024
    private const val TOTAL_LIMIT = 256L * 1024 * 1024
    private const val ENTRY_LIMIT = 20_000
    private val idSuffix = Regex(" [0-9a-fA-F]{32}$")
    private val pictures = setOf("png", "jpg", "jpeg", "gif", "webp")
    private data class Source(val zip: ZipFile, val entry: ZipEntry)
    private class TooLarge : IllegalArgumentException("That file is too big")
    private data class Page(val source: String, val marker: String, var target: String? = null, var fresh: Boolean = false)

    /** [picture] is the same image-to-OpenRaster conversion used by Keep import. */
    fun import(vault: Vault, input: InputStream, picture: (ByteArray) -> ByteArray = KeepImport::pngPainting): Result {
        val temps = mutableListOf<File>()
        val archives = mutableListOf<ZipFile>()
        val entries = linkedMapOf<String, Source>()
        var imported = 0; var duplicates = 0; var skipped = 0; var failed = 0
        var total = 0L
        var count = 0
        fun copy(stream: InputStream, out: java.io.OutputStream, limit: Long, charge: Boolean) {
            val buffer = ByteArray(8192)
            var size = 0L
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                size += n
                if (charge) total += n
                if (size > limit || total > TOTAL_LIMIT) throw TooLarge()
                out.write(buffer, 0, n)
            }
        }
        fun temporary() = File.createTempFile("squill-notion-", ".zip").also { temps += it }
        fun read(path: String, limit: Int, charge: Boolean = true): ByteArray {
            val source = entries[path] ?: error("Missing file")
            if (source.entry.size > limit) throw TooLarge()
            return ByteArrayOutputStream().also { out ->
                source.zip.getInputStream(source.entry).use { copy(it, out, limit.toLong(), charge) }
            }.toByteArray()
        }
        fun index(file: File, prefix: String, nested: Boolean) {
            val zip = ZipFile(file).also { archives += it }
            val all = zip.entries()
            while (all.hasMoreElements()) {
                require(++count <= ENTRY_LIMIT) { "Too many files in that zip" }
                val entry = all.nextElement()
                // Validate directories too, although nothing is extracted to their names.
                val path = safePath(entry.name.removeSuffix("/"))
                if (path == null) { failed++; continue }
                if (entry.isDirectory) continue
                val full = join(prefix, path)
                if (extension(path) == "zip") {
                    if (nested) { skipped++; continue }
                    try {
                        require(entry.size <= ZIP_LIMIT)
                        val part = temporary()
                        zip.getInputStream(entry).use { stream -> part.outputStream().use { copy(stream, it, ZIP_LIMIT, true) } }
                        index(part, full.substringBeforeLast('/', ""), true)
                    } catch (e: IllegalArgumentException) {
                        // Entry and aggregate limits apply to the whole export, including parts.
                        if (count > ENTRY_LIMIT || total > TOTAL_LIMIT) throw e
                        failed++
                    } catch (_: Exception) { failed++ }
                } else if (entries.putIfAbsent(full, Source(zip, entry)) != null) failed++
            }
        }
        try {
            val outer = temporary()
            outer.outputStream().use { copy(input, it, ZIP_LIMIT, false) }
            index(outer, "", false)
            val pages = linkedMapOf<String, Page>()
            val fingerprints = mutableMapOf<String, Page>()
            // Fingerprint the original text and linked images, never generated paths or conversions.
            for (path in entries.keys.filter { extension(it) in setOf("md", "csv") }) {
                try {
                    val bytes = read(path, TEXT_LIMIT)
                    val text = bytes.toString(Charsets.UTF_8)
                    if (extension(path) == "csv") csv(text) // Validate before reserving a name.
                    val keys = mutableListOf(identity(path), digest(bytes))
                    if (extension(path) == "md") transform(text) { _, url, original, _ ->
                        val found = relative(path, url)
                        if (found != null && extension(found) in pictures) {
                            keys += runCatching { digest(read(found, IMAGE_LIMIT)) }.getOrElse { "skipped:$found" }
                        }
                        original
                    }
                    val fingerprint = digest(keys.joinToString("") { "${it.length}:$it" }.toByteArray())
                    val marker = ".notion-import/$fingerprint.mark"
                    val existing = fingerprints[marker]
                    if (existing != null) { pages[path] = existing; duplicates++; continue }
                    val page = Page(path, marker)
                    fingerprints[marker] = page
                    if (vault.exists(marker)) {
                        page.target = vault.readText(marker)
                        duplicates++
                    }
                    pages[path] = page
                } catch (e: Exception) {
                    if (extension(path) == "csv" && e is TooLarge) skipped++
                    else failed++
                }
            }
            val folders = mutableMapOf("" to "")
            fun folder(source: String): String {
                folders[source]?.let { return it }
                // Folder mappings survive later imports of additional pages into the same hierarchy.
                val markerName = "folder-" + digest(source.toByteArray())
                val marker = ".notion-import/$markerName.mark"
                if (vault.exists(marker)) {
                    val saved = vault.readText(marker)
                    if (vault.file(saved).isDirectory) { folders[source] = saved; return saved }
                }
                val parent = folder(source.substringBeforeLast('/', ""))
                val name = safeName(stripId(source.substringAfterLast('/')))
                var candidate = name
                var number = 2
                while (vault.exists(join(parent, candidate))) {
                    val suffix = " ${number++}"
                    candidate = name.take(120 - suffix.length) + suffix
                }
                val created = vault.createFolder(parent, candidate)
                folders[source] = created
                // Replace a stale mapping through the vault (and hence through encryption).
                if (vault.exists(marker)) vault.writeFile(marker) { it.write(created.toByteArray()) }
                else vault.createFile(".notion-import", markerName, "mark") { it.write(created.toByteArray()) }
                return created
            }
            // Reserve names first: forward links and same-named pages need their final full paths.
            for (page in pages.values.distinct().filter { it.target == null }) {
                try {
                    val parent = folder(page.source.substringBeforeLast('/', ""))
                    page.target = vault.create(parent, safeName(stripId(page.source.substringAfterLast('/').substringBeforeLast('.')))).path
                    page.fresh = true
                } catch (_: Exception) { failed++ }
            }
            val byId = pages.values.distinct().groupBy { pageId(it.source) }.filterKeys { it != null }
            for (page in pages.values.distinct().filter { it.fresh }) {
                val made = mutableListOf(page.target!!)
                var skippedHere = 0
                try {
                    val sourceText = read(page.source, TEXT_LIMIT, false).toString(Charsets.UTF_8)
                    val saved = mutableMapOf<String, String>()
                    val missing = linkedSetOf<String>()
                    val tableImages = linkedSetOf<String>()
                    val body = if (extension(page.source) == "csv") csv(sourceText) else transform(sourceText) { label, url, original, inTable ->
                        val path = relative(page.source, url)
                        val target = path?.let { pages[it] ?: if (extension(it) in setOf("md", "csv")) byId[pageId(it)]?.singleOrNull() else null }
                        when {
                            target?.target != null -> {
                                val heading = url.substringAfter('#', "").let { if (it.isEmpty()) "" else "#${decode(it)}" }
                                val destination = target.target!!.removeSuffix(".md") + heading
                                // Squill's table parser splits on pipes, including alias separators.
                                if (inTable) "[[$destination]]" else "[[$destination|${label.replace('|', ' ').replace(']', ' ')}]]"
                            }
                            external(url) || url.startsWith('#') -> original
                            path == null -> {
                                if (missing.add(url)) skippedHere++
                                label.takeUnless { original.startsWith('!') }.orEmpty()
                            }
                            extension(path) in setOf("md", "csv") -> original
                            else -> {
                                val embed = saved[path] ?: if (extension(path) in pictures && path !in missing) runCatching {
                                    val ora = picture(read(path, IMAGE_LIMIT, false))
                                    val image = vault.createFile(page.target!!.substringBeforeLast('/', ""), safeName(path.substringAfterLast('/').substringBeforeLast('.')), "ora") { it.write(ora) }
                                    made += image
                                    "![[$image]]".also { saved[path] = it }
                                }.getOrNull() else null
                                if (embed != null) {
                                    if (inTable) { tableImages += embed; "" } else "\n\n$embed\n\n"
                                } else run {
                                    if (missing.add(path)) skippedHere++
                                    // A separate plain line below names attachments without keeping a dead link.
                                    label.takeUnless { original.startsWith('!') }.orEmpty()
                                }
                            }
                        }
                    }
                    val notices = missing.map { "File not imported: ${it.substringAfterLast('/').replace(Regex("[\\r\\n]"), " ")}" }
                    vault.save(page.target!!, (listOf(body) + tableImages + notices).joinToString("\n\n"), false, null)
                    vault.createFile(".notion-import", page.marker.substringAfterLast('/').removeSuffix(".mark"), "mark") { it.write(page.target!!.toByteArray()) }
                    imported++; skipped += skippedHere
                } catch (_: Exception) {
                    made.forEach { vault.file(it).delete() }
                    failed++
                }
            }
        } finally {
            archives.asReversed().forEach { runCatching { it.close() } }
            temps.forEach { it.delete() }
        }
        return Result(imported, duplicates, skipped, failed)
    }

    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
    private fun extension(path: String) = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun stripId(name: String) = name.replace(idSuffix, "")
    private fun pageId(path: String) = idSuffix.find(path.substringAfterLast('/').substringBeforeLast('.'))?.value?.trim()?.lowercase(Locale.ROOT)
    private fun identity(path: String) = pageId(path) ?: path
    private fun safeName(name: String) = name.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|#^\\[\\]]"), " ")
        .replace(Regex(" +"), " ").trim { it == '.' || it.isWhitespace() }.ifBlank { "Untitled" }.take(120)
    private fun safePath(path: String): String? {
        val clean = path.replace('\\', '/')
        return clean.takeIf { it.isNotBlank() && !it.startsWith('/') && ':' !in it && '\u0000' !in it && it.split('/').let { parts -> parts.size <= 128 && parts.none { p -> p.isEmpty() || p == "." || p == ".." } } }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun decode(text: String) = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    private fun external(url: String) = url.startsWith("//") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)
    private fun relative(from: String, url: String): String? = runCatching {
        if (external(url) || url.startsWith('#')) return null
        val decoded = decode(url.substringBefore('#').substringBefore('?')).replace('\\', '/')
        require(!decoded.startsWith('/') && ':' !in decoded && '\u0000' !in decoded)
        val parts = from.split('/').dropLast(1).toMutableList()
        decoded.split('/').forEach { when (it) {
            "", "." -> Unit
            ".." -> { require(parts.isNotEmpty()); parts.removeAt(parts.lastIndex) }
            else -> parts += it
        } }
        safePath(parts.joinToString("/"))
    }.getOrNull()

    // Notion emits inline links. Keep fenced and inline code byte-for-byte, as ordinary Markdown.
    private fun transform(text: String, replace: (String, String, String, Boolean) -> String): String {
        var fence: Char? = null
        var fenceSize = 0
        return text.splitToSequence('\n').joinToString("\n") { line ->
            val trimmed = line.trimStart()
            val char = trimmed.firstOrNull()
            val size = trimmed.takeWhile { it == char }.length
            if ((char == '`' || char == '~') && size >= 3 && (fence == null || fence == char)) {
                if (fence == null) { fence = char; fenceSize = size }
                else if (size >= fenceSize && trimmed.drop(size).isBlank()) fence = null
                line
            } else if (fence != null || line.startsWith("    ") || line.startsWith('\t')) line
            else {
                val code = mutableListOf<IntRange>()
                var at = 0
                while (at < line.length) {
                    if (line[at] != '`') { at++; continue }
                    val start = at
                    while (at < line.length && line[at] == '`') at++
                    val delimiter = line.substring(start, at)
                    val end = line.indexOf(delimiter, at)
                    if (end >= 0) { code += start until end + delimiter.length; at = end + delimiter.length }
                }
                rewriteLine(line, code, replace)
            }
        }
    }

    /** Scan rather than backtracking over untrusted, potentially very long Markdown lines. */
    private fun rewriteLine(line: String, code: List<IntRange>, replace: (String, String, String, Boolean) -> String): String {
        val out = StringBuilder()
        var at = 0
        var copied = 0
        while (at < line.length) {
            val open = line.indexOf('[', at)
            if (open < 0) break
            var close = open + 1
            var brackets = 1
            while (close < line.length && brackets > 0) {
                when (line[close]) {
                    '\\' -> { close += 2; continue }
                    '[' -> brackets++
                    ']' -> brackets--
                }
                if (brackets > 0) close++
            }
            if (brackets != 0) break
            at = close + 1
            if (at >= line.length || line[at] != '(') continue
            val start = if (open > 0 && line[open - 1] == '!') open - 1 else open
            if (start > 0 && line[start - 1] == '\\') continue
            var depth = 1
            var end = at + 1
            while (end < line.length && depth > 0) {
                when (line[end]) { '(' -> depth++; ')' -> depth-- }
                end++
            }
            if (depth != 0) break
            at = end
            if (code.any { it.first < end && it.last >= start }) continue
            val inside = line.substring(close + 2, end - 1).trim()
            val url = if (inside.startsWith('<')) inside.substringAfter('<').substringBefore('>')
                else inside.takeWhile { !it.isWhitespace() }
            if (url.isEmpty()) continue
            val rest = if (inside.startsWith('<')) inside.substringAfter('>', "invalid").trim() else inside.drop(url.length).trim()
            if (rest.isNotEmpty() && !(rest.length >= 2 && rest.first() == '"' && rest.last() == '"')) continue
            out.append(line, copied, start)
            out.append(replace(line.substring(open + 1, close), url, line.substring(start, end), '|' in line))
            copied = end
        }
        return out.append(line, copied, line.length).toString()
    }

    /** RFC 4180 quoting, including commas, escaped quotes and newlines in a cell. */
    private fun csv(text: String): String {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var closed = false
        var at = 0
        val clean = text.removePrefix("\uFEFF")
        fun field() { row += cell.toString(); cell.setLength(0); closed = false }
        while (at < clean.length) {
            val c = clean[at++]
            if (quoted) {
                if (c == '"') {
                    if (at < clean.length && clean[at] == '"') { cell.append('"'); at++ }
                    else { quoted = false; closed = true }
                } else cell.append(c)
            } else when (c) {
                '"' -> { require(cell.isEmpty() && !closed); quoted = true }
                ',' -> field()
                '\r', '\n' -> {
                    if (c == '\r' && at < clean.length && clean[at] == '\n') at++
                    field(); rows += row; row = mutableListOf()
                }
                else -> { require(!closed); cell.append(c) }
            }
        }
        require(!quoted) { "That CSV couldn't be read" }
        if (cell.isNotEmpty() || row.isNotEmpty() || closed) { field(); rows += row }
        require(rows.isNotEmpty())
        val width = rows.maxOf { it.size }
        fun line(values: List<String>) = "| " + (0 until width).joinToString(" | ") { i ->
            values.getOrElse(i) { "" }.replace("&", "&amp;").replace("|", "&#124;").replace("\r\n", "<br>").replace("\n", "<br>").replace("\r", "<br>")
        } + " |"
        val result = StringBuilder()
        fun append(values: List<String>) {
            // Bound padding too: a ragged CSV must not expand into an enormous table.
            if (width > TEXT_LIMIT / 4 || result.length.toLong() + width * 4L > TEXT_LIMIT) throw TooLarge()
            if (result.isNotEmpty()) result.append('\n')
            result.append(line(values))
            if (result.length > TEXT_LIMIT) throw TooLarge()
        }
        append(rows.first()); append(List(width) { "---" })
        rows.drop(1).forEach(::append)
        if (result.toString().toByteArray().size > TEXT_LIMIT) throw TooLarge()
        return result.toString()
    }
}
