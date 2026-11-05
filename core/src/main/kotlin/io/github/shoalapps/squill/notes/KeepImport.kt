package io.github.shoalapps.squill.notes

import io.github.shoalapps.squill.paint.Blend
import io.github.shoalapps.squill.paint.OraStack
import io.github.shoalapps.squill.paint.Painting
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Imports an offline Keep export. No Google account or network access is needed. */
object KeepImport {
    /** Trashed notes are ignored. Attachment skips count only notes newly imported. */
    data class Result(val imported: Int, val duplicates: Int, val skippedAttachments: Int, val failed: Int)

    private const val JSON_LIMIT = 2 * 1024 * 1024
    private const val IMAGE_LIMIT = 20 * 1024 * 1024
    private const val ZIP_LIMIT = 512 * 1024 * 1024
    private const val TOTAL_LIMIT = 256 * 1024 * 1024
    private const val ENTRY_LIMIT = 20_000
    /** Fields only Keep writes. "title" isn't one: Photos and YouTube exports have those too. */
    private val KEEP_FIELDS = listOf("textContent", "listContent", "attachments", "labels", "isTrashed", "isPinned", "isArchived", "userEditedTimestampUsec", "createdTimestampUsec")

    /** [picture] converts image bytes to OpenRaster; the JVM default supports PNG without AWT. */
    fun import(vault: Vault, input: InputStream, picture: (ByteArray) -> ByteArray = ::pngPainting): Result {
        val temp = File.createTempFile("squill-keep-", ".zip")
        var imported = 0
        var duplicates = 0
        var skipped = 0
        var failed = 0
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    require(size <= ZIP_LIMIT) { "That zip is too big" }
                    out.write(buffer, 0, n)
                }
            }
            ZipFile(temp).use { zip ->
                val entries = linkedMapOf<String, java.util.zip.ZipEntry>()
                val all = zip.entries()
                var count = 0
                while (all.hasMoreElements()) {
                    require(++count <= ENTRY_LIMIT) { "Too many files in that zip" }
                    val entry = all.nextElement()
                    if (entry.isDirectory) continue
                    val path = safePath(entry.name)
                    if (path == null || entries.containsKey(path)) { failed++; continue }
                    entries[path] = entry
                }
                var total = 0L
                fun read(path: String, limit: Int, charge: Boolean = true): ByteArray {
                    val entry = entries[path] ?: error("Missing attachment")
                    require(entry.size <= limit) { "That file is too big" }
                    return zip.getInputStream(entry).use { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            if (charge) total += n
                            require(out.size().toLong() + n <= limit && total <= TOTAL_LIMIT) { "That export is too big" }
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                }
                for (path in entries.keys.filter { it.endsWith(".json", ignoreCase = true) }) {
                    val made = mutableListOf<String>()
                    try {
                        val json = read(path, JSON_LIMIT).toString(Charsets.UTF_8)
                        checkDepth(json)
                        val note = Json.parseToJsonElement(json) as? JsonObject
                            ?: error("That isn't a note")
                        if (note.flag("isTrashed")) continue
                        // A Takeout zip can hold other Google products' JSON too, which isn't a note.
                        if (KEEP_FIELDS.none { it in note }) continue
                        val title = note.text("title").ifBlank { path.substringAfterLast('/').dropLast(5) }.ifBlank { "Untitled" }
                        val folder = if (note.flag("isArchived")) "Archive" else ""
                        val parts = mutableListOf<String>()
                        note.text("textContent").takeIf { it.isNotEmpty() }?.let { parts += it }
                        note.array("listContent").mapNotNull { it as? JsonObject }.map {
                            "- [${if (it.flag("isChecked")) "x" else " "}] " + it.text("text").replace("\n", "\n  ")
                        }.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString("\n") }
                        note.array("labels").mapNotNull { (it as? JsonObject)?.text("name") }.mapNotNull { label ->
                            val tag = label.trim().replace(Regex("[^\\p{L}\\p{N}_/-]+"), "_").trim('_')
                            tag.takeIf { it.isNotEmpty() }?.let { "#" + (if (it.first().isLetter()) it else "tag_$it") }
                        }.distinct().takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" ") }
                        val images = mutableListOf<Pair<String, String>>()
                        val attachmentKeys = mutableListOf<String>()
                        var skippedHere = 0
                        note.array("attachments").forEach { item ->
                            val attachment = item as? JsonObject
                            val source = attachment?.text("filePath").orEmpty()
                            try {
                                val safe = safePath(source) ?: error("Unsafe attachment path")
                                val relative = path.substringBeforeLast('/', "").let { if (it.isEmpty()) safe else "$it/$safe" }
                                val found = if (relative in entries) relative else safe
                                require(attachment?.text("mimetype")?.lowercase(Locale.ROOT) in setOf("image/png", "image/jpeg", "image/gif", "image/webp"))
                                val bytes = read(found, IMAGE_LIMIT)
                                attachmentKeys += digest(bytes)
                                images += source.substringAfterLast('/').substringBeforeLast('.', "image") to found
                            } catch (_: Exception) {
                                attachmentKeys += "skipped:$source"
                                skippedHere++
                            }
                        }
                        val body = parts.joinToString("\n\n")
                        // A JSON array keeps names and content unambiguous; no import marks enter the note.
                        val fingerprint = digest(JsonArray(listOf(title, body)
                            .plus(attachmentKeys).map(::JsonPrimitive)).toString().toByteArray())
                        val marker = ".keep-import/$fingerprint.mark"
                        if (vault.exists(marker)) { duplicates++; continue }
                        val embeds = images.mapNotNull { (name, source) ->
                            try {
                                // These bytes were already charged while fingerprinting.
                                val ora = picture(read(source, IMAGE_LIMIT, charge = false))
                                val image = vault.createFile(folder, safeName(name), "ora") { it.write(ora) }
                                made += image
                                "![[$image]]"
                            } catch (_: Exception) { skippedHere++; null }
                        }
                        val text = (listOf(body).filter { it.isNotEmpty() } + embeds).joinToString("\n\n")
                        val created = vault.create(folder, safeName(title))
                        made += created.path
                        vault.save(created.path, text, note.flag("isPinned"), colour(note.text("color")))
                        val modified = (note["userEditedTimestampUsec"] as? JsonPrimitive)?.longOrNull
                            ?: (note["createdTimestampUsec"] as? JsonPrimitive)?.longOrNull
                        modified?.takeIf { it > 0 }?.let { vault.file(created.path).setLastModified(it / 1000) }
                        vault.createFile(".keep-import", fingerprint, "mark") { }
                        skipped += skippedHere
                        imported++
                    } catch (_: Exception) {
                        made.forEach { vault.file(it).delete() }
                        failed++
                    }
                }
            }
        } finally { temp.delete() }
        return Result(imported, duplicates, skipped, failed)
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.array(key: String) = (get(key) as? JsonArray).orEmpty()
    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]\\n\\r\\t]"), " ")
        .trim().trim('.').trim().ifBlank { "Untitled" }.take(120)
    private fun safePath(path: String): String? {
        val clean = path.replace('\\', '/')
        val parts = clean.split('/')
        return clean.takeIf { it.isNotBlank() && !it.startsWith('/') && ':' !in it && '\u0000' !in it && parts.none { p -> p == ".." || p == "." || p.isEmpty() } }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun colour(value: String): String? = when (val c = value.lowercase(Locale.ROOT)) {
        "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink" -> c
        "cyan" -> "teal"
        "cerulean" -> "blue"
        else -> null
    }

    /** Bound nesting before handing untrusted JSON to the parser. Brackets inside strings don't count. */
    private fun checkDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> require(++depth <= 128) { "That note is too deeply nested" }
                '}', ']' -> depth--
            }
        }
    }

    internal fun pngPainting(bytes: ByteArray): ByteArray {
        require(bytes.size >= 33 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)))
        val header = java.nio.ByteBuffer.wrap(bytes)
        require(header.getInt(8) == 13 && header.getInt(12) == 0x49484452)
        val width = header.getInt(16)
        val height = header.getInt(20)
        require(width in 1..Painting.MAX_SIDE && height in 1..Painting.MAX_SIDE)
        var at = 8
        var image = false
        var ended = false
        while (at < bytes.size) {
            require(bytes.size - at >= 12)
            val size = header.getInt(at)
            require(size >= 0 && size <= bytes.size - at - 12)
            val crc = CRC32().apply { update(bytes, at + 4, size + 4) }.value
            require(crc == header.getInt(at + size + 8).toLong().and(0xFFFFFFFFL))
            when (header.getInt(at + 4)) {
                0x49444154 -> image = true // IDAT
                0x49454E44 -> { require(size == 0); ended = true } // IEND
            }
            at += size + 12
            if (ended) break
        }
        require(image && ended && at == bytes.size)
        val stack = OraStack.write(width, height, listOf(OraStack.Entry("Image", "data/layer0.png", 1f, true, Blend.NORMAL, false, false)))
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                val mime = "image/openraster".toByteArray()
                zip.putNextEntry(ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(mime) }.value
                })
                zip.write(mime)
                zip.closeEntry()
                linkedMapOf("stack.xml" to stack.toByteArray(), "data/layer0.png" to bytes,
                    "mergedimage.png" to bytes, "Thumbnails/thumbnail.png" to bytes).forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}
