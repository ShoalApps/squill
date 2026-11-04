package io.github.shoalapps.squill.notes

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A note as it is on disk. [path] is relative to the vault, such as "Ideas/Boats.md". */
@kotlinx.serialization.Serializable
data class Note(
    val path: String,
    val body: String,
    val modified: Long,
    val pinned: Boolean = false,
    val colour: String? = null,
) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".md")
    val folder: String get() = path.substringBeforeLast('/', "")
}

/**
 * The note's file: Markdown with optional front matter. Properties Squill doesn't use are kept
 * exactly as found, so a note from another app comes through a round trip unchanged.
 */
object NoteFile {
    fun read(text: String): Triple<String, Boolean, String?> {
        val (front, body) = split(text)
        val props = front.mapNotNull { line -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        return Triple(body, props["pinned"] == "true", props["colour"]?.takeIf { it.isNotEmpty() })
    }

    fun write(original: String?, body: String, pinned: Boolean, colour: String?): String {
        val kept = original?.let { split(it).first }.orEmpty().filterNot { it.startsWith("pinned:") || it.startsWith("colour:") }
        val front = kept + listOfNotNull("pinned: true".takeIf { pinned }, colour?.let { "colour: $it" })
        return if (front.isEmpty()) body else "---\n" + front.joinToString("\n") + "\n---\n" + body
    }

    private fun split(text: String): Pair<List<String>, String> {
        val clean = text.removePrefix("﻿")
        if (!clean.startsWith("---\n") && !clean.startsWith("---\r\n")) return emptyList<String>() to clean
        val start = clean.indexOf('\n') + 1
        val end = Regex("(?m)^---\\s*$").find(clean, start) ?: return emptyList<String>() to clean
        val after = clean.indexOf('\n', end.range.first).let { if (it < 0) clean.length else it + 1 }
        return clean.substring(start, end.range.first).lines().filter { it.isNotBlank() } to clean.substring(after)
    }
}

class VaultException(message: String) : Exception(message)

data class FileStat(val path: String, val modified: Long, val size: Long)

/** Notes as ordinary Markdown files in ordinary folders. Everything goes through here. */
class Vault(val root: File) {
    private val trash = File(root, TRASH)

    init { root.mkdirs() }

    /** Refuses any path that would escape the vault, however it's spelt. */
    private fun at(path: String): File {
        val file = File(root, path).canonicalFile
        val top = root.canonicalFile
        if (file != top && !file.path.startsWith(top.path + File.separator)) throw VaultException("That isn't inside your notes")
        return file
    }

    private fun relative(file: File) = file.relativeTo(root).invariantSeparatorsPath

    fun notes(): List<Note> = root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }
        .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
        .map { read(it) }.toList()

    fun read(path: String): Note = read(at(path))

    /** Every note's file details, without reading them, for spotting what's changed. */
    fun stats(): List<FileStat> = root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }
        .filter { it.isFile && (it.extension.equals("md", ignoreCase = true) || it.extension.equals("svg", ignoreCase = true)) }
        .map { FileStat(relative(it), it.lastModified(), it.length()) }.toList()

    fun exists(path: String) = at(path).exists()

    fun readText(path: String) = at(path).readText()

    fun createSketch(folder: String, name: String, sketch: Sketch = Sketch()): String {
        at(folder).mkdirs()
        val file = free(folder, cleanName(name), "svg")
        writeAtomically(file, sketch.toSvg())
        return relative(file)
    }

    fun saveSketch(path: String, sketch: Sketch) = writeAtomically(at(path), sketch.toSvg())

    /** Renames or moves a drawing and updates the notes that show it. */
    fun moveSketch(path: String, folder: String, name: String): String {
        val from = at(path)
        val clean = cleanName(name.removeSuffix(".svg"))
        at(folder).mkdirs()
        val newPath = listOf(folder, "$clean.svg").filter { it.isNotEmpty() }.joinToString("/")
        if (newPath == path) return path
        val to = if (newPath.equals(path, ignoreCase = true)) at(newPath) else free(folder, clean, "svg")
        if (!from.renameTo(to)) throw VaultException("Couldn't move that drawing")
        val oldName = path.substringAfterLast('/')
        val newName = to.name
        notes().forEach { note ->
            val updated = Links.retargetEmbed(note.body, oldName, newName)
            if (updated != note.body) save(note.path, updated, note.pinned, note.colour)
        }
        return relative(to)
    }

    private fun read(file: File): Note {
        val (body, pinned, colour) = NoteFile.read(file.readText())
        return Note(relative(file), body, file.lastModified(), pinned, colour)
    }

    fun folders(): List<String> = root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }
        .filter { it.isDirectory && it != root }.map { relative(it) }.toList().sorted()

    /** A name that's safe as a file on any system, or an error saying what's wrong with it. */
    private fun cleanName(name: String): String {
        val clean = name.trim().trimEnd('.').replace(Regex("[\\\\/:*?\"<>|#^\\[\\]\\n\\r\\t]"), " ").replace(Regex(" +"), " ").trim()
        if (clean.isEmpty() || clean.startsWith(".")) throw VaultException("That name won't work")
        return clean.take(120)
    }

    private fun free(folder: String, name: String, extension: String = "md"): File {
        val dir = at(folder)
        var candidate = File(dir, "$name.$extension")
        var n = 2
        while (candidate.exists()) candidate = File(dir, "$name ${n++}.$extension")
        return candidate
    }

    fun create(folder: String, name: String, body: String = ""): Note {
        at(folder).mkdirs()
        val file = free(folder, cleanName(name))
        writeAtomically(file, NoteFile.write(null, body, false, null))
        return read(file)
    }

    /** Saves a note's text and properties. If the file has gone, it's written afresh rather than lost. */
    fun save(path: String, body: String, pinned: Boolean, colour: String?): Note {
        val file = at(path)
        val original = if (file.exists()) file.readText() else null
        file.parentFile?.mkdirs()
        writeAtomically(file, NoteFile.write(original, body, pinned, colour))
        return read(file)
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) { tmp.delete(); throw VaultException("Couldn't save that note") }
        }
    }

    /** Renames or moves a note and points every link at it at the new place. */
    fun move(path: String, folder: String, name: String): Note {
        val from = at(path)
        val clean = cleanName(name)
        val newPath = if (folder.isEmpty()) "$clean.md" else "$folder/$clean.md"
        if (newPath == path) return read(from)
        at(folder).mkdirs()
        val to = if (newPath.equals(path, ignoreCase = true)) at(newPath) else free(folder, clean)
        val finalPath = relative(to)
        val before = notes()
        val paths = before.map { it.path }
        if (!from.renameTo(to)) throw VaultException("Couldn't move that note")
        before.filter { it.path != path }.forEach { rewrite(it, path, finalPath, paths) }
        before.firstOrNull { it.path == path }?.let { rewrite(it.copy(path = finalPath), path, finalPath, paths) }
        return read(to)
    }

    private fun rewrite(note: Note, oldPath: String, newPath: String, paths: List<String>) {
        val updated = Links.retarget(note.body, oldPath, newPath, paths)
        if (updated != note.body) save(note.path, updated, note.pinned, note.colour)
    }

    fun createFolder(parent: String, name: String): String {
        val path = listOf(parent, cleanName(name)).filter { it.isNotEmpty() }.joinToString("/")
        val dir = at(path)
        if (dir.exists()) throw VaultException("There's already a folder called that")
        dir.mkdirs()
        return path
    }

    /** Renames a folder, keeping every link to the notes inside it working. */
    fun renameFolder(path: String, name: String): String {
        val from = at(path)
        val parent = path.substringBeforeLast('/', "")
        val newPath = listOf(parent, cleanName(name)).filter { it.isNotEmpty() }.joinToString("/")
        if (newPath == path) return path
        val to = at(newPath)
        if (to.exists()) throw VaultException("There's already a folder called that")
        val before = notes()
        val paths = before.map { it.path }
        if (!from.renameTo(to)) throw VaultException("Couldn't rename that folder")
        before.forEach { note ->
            val moved = if (note.path.startsWith("$path/")) newPath + note.path.removePrefix(path) else note.path
            var body = note.body
            before.filter { it.path.startsWith("$path/") }.forEach { target ->
                body = Links.retarget(body, target.path, newPath + target.path.removePrefix(path), paths)
            }
            if (body != note.body) save(moved, body, note.pinned, note.colour)
        }
        return newPath
    }

    /** Puts a note in the trash, keeping its folder so it can go back. */
    fun trash(path: String) {
        val from = at(path)
        val to = freeInTrash(path)
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw VaultException("Couldn't delete that note")
    }

    /** Puts a folder and everything in it in the trash. */
    fun trashFolder(path: String) {
        val dir = at(path)
        if (dir == root.canonicalFile) throw VaultException("That isn't a folder you can delete")
        dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList().forEach { trash(relative(it)) }
        dir.deleteRecursively()
    }

    private fun freeInTrash(path: String): File {
        var candidate = File(trash, path)
        var n = 2
        val extension = path.substringAfterLast('.')
        val stem = path.removeSuffix(".$extension")
        while (candidate.exists()) candidate = File(trash, "$stem ${n++}.$extension")
        return candidate
    }

    /** What's in the trash, by the path it would go back to. */
    fun trashed(): List<Note> = if (!trash.exists()) emptyList() else trash.walkTopDown()
        .filter { it.isFile && (it.extension.equals("md", ignoreCase = true) || it.extension.equals("svg", ignoreCase = true)) && !it.name.startsWith(".") }
        .map { file ->
            val (body, pinned, colour) = if (file.extension.equals("svg", ignoreCase = true)) Triple("", false, null) else NoteFile.read(file.readText())
            Note(file.relativeTo(trash).invariantSeparatorsPath, body, file.lastModified(), pinned, colour)
        }.sortedByDescending { it.modified }.toList()

    fun restore(path: String): Note {
        val from = File(trash, path)
        val folder = path.substringBeforeLast('/', "")
        at(folder).mkdirs()
        val extension = path.substringAfterLast('.')
        val to = free(folder, path.substringAfterLast('/').removeSuffix(".$extension"), extension)
        if (!from.renameTo(to)) throw VaultException("Couldn't restore that note")
        return if (extension == "md") read(to) else Note(relative(to), "", to.lastModified())
    }

    fun deleteForever(path: String) {
        val file = File(trash, path).canonicalFile
        if (!file.path.startsWith(trash.canonicalPath + File.separator)) throw VaultException("That isn't in the trash")
        file.delete()
    }

    fun emptyTrash() { trash.deleteRecursively() }

    /** Writes every note to a zip of plain Markdown files, which any Markdown app can open. */
    fun export(out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out).use { zip ->
            root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }
                .filter { it.isFile && !it.name.startsWith(".") }.forEach { file ->
                    zip.putNextEntry(ZipEntry(relative(file)))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
        }
        return count
    }

    /** Adds the notes in a zip. Never overwrites: a name that's taken gets a number. Returns how many came in. */
    fun importZip(input: InputStream): Int {
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) count += importFile(entry.name, zip)
                zip.closeEntry()
            }
        }
        return count
    }

    /** Adds one Markdown file at [path]. Other files, and anything hidden such as .obsidian, are skipped. */
    fun importFile(path: String, input: InputStream): Int {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.startsWith(".") }) return 0
        val file = parts.last()
        val isNote = file.endsWith(".md", ignoreCase = true)
        if (!isNote && !file.endsWith(".svg", ignoreCase = true)) return 0
        val text = input.readBytes().toString(Charsets.UTF_8)
        if (!isNote && Sketch.parse(text) == null) return 0 // Only Squill's own drawings.
        val folder = parts.dropLast(1).joinToString("/") { cleanName(it) }
        at(folder).mkdirs()
        val target = free(folder, cleanName(file.dropLast(if (isNote) 3 else 4)), if (isNote) "md" else "svg")
        writeAtomically(target, text)
        return 1
    }

    companion object {
        const val TRASH = ".trash"
    }
}
