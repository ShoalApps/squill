package io.github.shoalapps.squill.notes

import io.github.shoalapps.squill.reminders.ReminderStore

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

private fun looksLikeOra(bytes: ByteArray) = runCatching { "stack.xml" in readZip(java.io.ByteArrayInputStream(bytes)) }.getOrDefault(false)

class VaultException(message: String) : Exception(message)

data class FileStat(val path: String, val modified: Long, val size: Long)

/** Notes as ordinary Markdown files in ordinary folders. Everything goes through here. */
class Vault(val root: File, historyClock: () -> Long = System::currentTimeMillis) {
    private val trash = File(root, TRASH)
    val history = NoteHistory(this, clock = historyClock)

    internal fun readHistory(file: File): ByteArray = load(at(relative(file)))
    internal fun writeHistory(file: File, bytes: ByteArray, maxBytes: Long) {
        val target = at(relative(file))
        val sealed = seal(bytes)
        if (sealed.size.toLong() > maxBytes) return
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        try {
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(target)) throw VaultException("Couldn't keep that version")
        } finally { tmp.delete() }
    }

    private fun remember(file: File, next: ByteArray, force: Boolean = false) {
        runCatching {
            if (file.isFile && file.extension.equals("md", true)) {
                val old = load(file)
                if (!old.contentEquals(next)) {
                    val emptied = NoteFile.read(next.toString(Charsets.UTF_8)).first.isEmpty() &&
                        NoteFile.read(old.toString(Charsets.UTF_8)).first.isNotEmpty()
                    history.keep(relative(file), old, force || emptied)
                }
            }
        }
    }

    /** Removes recovery copies before removing the ordinary note for a locked-vault move. */
    fun moveToLocked(path: String, locked: LockedNotes) {
        val note = read(path)
        locked.save(note.title, note.body)
        history.remove(path)
        if (!at(path).delete()) throw VaultException("Couldn't remove the ordinary note")
    }

    /** When set, everything written is sealed, and sealed files read back as they were. */
    @Volatile var cipher: NoteCipher? = null

    private fun load(file: File): ByteArray {
        val data = file.readBytes()
        if (!NoteCipher.isSealed(data)) return data
        return (cipher ?: throw VaultException("Your notes are locked")).decode(data)
    }

    private fun loadText(file: File) = load(file).toString(Charsets.UTF_8)
    private fun seal(data: ByteArray): ByteArray {
        // Never let a locked vault gain a note in plain text.
        val c = cipher
        if (c == null && File(root, LockedNotes.ALL_NOTES_KEY).exists()) throw VaultException("Your notes are locked")
        return c?.encode(data) ?: data
    }

    init {
        root.mkdirs()
        // Retention uses names and sizes only, so it also works before an encrypted vault opens.
        runCatching { history.prune() }
    }

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
        .filter { it.isFile && it.extension.lowercase() in KINDS }
        .map { FileStat(relative(it), it.lastModified(), it.length()) }.toList()

    fun exists(path: String) = at(path).exists()

    fun readText(path: String) = loadText(at(path))

    fun createSketch(folder: String, name: String, sketch: Sketch = Sketch()): String {
        at(folder).mkdirs()
        val file = free(folder, cleanName(name), "svg")
        writeAtomically(file, sketch.toSvg())
        return relative(file)
    }

    fun saveSketch(path: String, sketch: Sketch) = writeAtomically(at(path), sketch.toSvg())

    fun readBytes(path: String) = load(at(path))

    /** Writes through [write] to a temporary file first, so a failed save can't damage what's there. */
    fun writeFile(path: String, write: (java.io.OutputStream) -> Unit) {
        val file = at(path)
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        if (cipher == null && File(root, LockedNotes.ALL_NOTES_KEY).exists()) throw VaultException("Your notes are locked")
        if (cipher == null) tmp.outputStream().buffered().use(write)
        else tmp.writeBytes(seal(java.io.ByteArrayOutputStream().also(write).toByteArray()))
        if (file.extension.equals("md", true)) runCatching { remember(file, load(tmp), force = true) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) { tmp.delete(); throw VaultException("Couldn't save that file") }
        }
    }

    fun createFile(folder: String, name: String, extension: String, write: (java.io.OutputStream) -> Unit): String {
        at(folder).mkdirs()
        val path = relative(free(folder, cleanName(name), extension))
        writeFile(path, write)
        return path
    }

    private fun moveWithHistory(from: File, to: File, paths: List<Pair<String, String>>) {
        val moved = mutableListOf<Pair<String, String>>()
        try {
            paths.forEach { (old, new) ->
                history.move(old, new)
                moved += old to new
            }
            if (!from.renameTo(to)) throw VaultException("Couldn't move that file")
        } catch (e: Exception) {
            moved.asReversed().forEach { (old, new) -> runCatching { history.move(new, old) } }
            throw VaultException("Couldn't move that file and its history")
        }
    }

    /** Renames or moves any file that nothing links to by name. */
    fun moveFile(path: String, folder: String, name: String): String {
        val from = at(path)
        val extension = path.substringAfterLast('.')
        val clean = cleanName(name.removeSuffix(".$extension"))
        at(folder).mkdirs()
        val newPath = listOf(folder, "$clean.$extension").filter { it.isNotEmpty() }.joinToString("/")
        if (newPath == path) return path
        val to = if (newPath.equals(path, ignoreCase = true)) at(newPath) else free(folder, clean, extension)
        moveWithHistory(from, to, listOf(path to relative(to)))
        return relative(to)
    }

    fun file(path: String): File = at(path)

    fun createDeck(folder: String, name: String, deck: Deck = Deck()): String {
        at(folder).mkdirs()
        val file = free(folder, cleanName(name), "deck")
        writeAtomically(file, deck.toJson())
        return relative(file)
    }

    fun saveDeck(path: String, deck: Deck) = writeAtomically(at(path), deck.toJson())

    fun moveDeck(path: String, folder: String, name: String): String {
        val from = at(path)
        val clean = cleanName(name.removeSuffix(".deck"))
        at(folder).mkdirs()
        val newPath = listOf(folder, "$clean.deck").filter { it.isNotEmpty() }.joinToString("/")
        if (newPath == path) return path
        val to = if (newPath.equals(path, ignoreCase = true)) at(newPath) else free(folder, clean, "deck")
        if (!from.renameTo(to)) throw VaultException("Couldn't move that presentation")
        return relative(to)
    }

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
        val (body, pinned, colour) = NoteFile.read(loadText(file))
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
        val original = if (file.exists()) loadText(file) else null
        file.parentFile?.mkdirs()
        writeAtomically(file, NoteFile.write(original, body, pinned, colour))
        return read(file)
    }

    /** Widget compare-and-flip: keeps the original front matter, BOM and newlines byte-for-byte.
     * The caller serializes this with other writers, just as it does for [save].
     */
    fun flipWidgetTask(path: String, index: Int, fingerprint: String): Boolean {
        if (cipher == null && File(root, LockedNotes.ALL_NOTES_KEY).exists()) return false
        if (!path.endsWith(".md", true) || path.split('/').any { it.startsWith(".") || it.isEmpty() } || '\\' in path) return false
        val file = at(path)
        if (!file.isFile) return false
        val original = loadText(file)
        val body = NoteFile.read(original).first
        // Only a body that's literally the end of the file can be spliced back without touching the rest.
        if (!original.endsWith(body)) return false
        val changed = WidgetLines.flipFingerprint(body, index, fingerprint) ?: return false
        writeAtomically(file, original.substring(0, original.length - body.length) + changed)
        return true
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeBytes(seal(text.toByteArray()))
        remember(file, text.toByteArray())
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
        moveWithHistory(from, to, listOf(path to finalPath))
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
        val historyPaths = before.filter { it.path.startsWith("$path/") }
            .map { it.path to (newPath + it.path.removePrefix(path)) }
        moveWithHistory(from, to, historyPaths)
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
        moveWithHistory(from, to, listOf(path to relative(to)))
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
        .filter { it.isFile && it.extension.lowercase() in KINDS && !it.name.startsWith(".") }
        .map { file ->
            val (body, pinned, colour) = if (!file.extension.equals("md", ignoreCase = true)) Triple("", false, null) else NoteFile.read(loadText(file))
            Note(file.relativeTo(trash).invariantSeparatorsPath, body, file.lastModified(), pinned, colour)
        }.sortedByDescending { it.modified }.toList()

    fun restore(path: String): Note {
        val from = File(trash, path)
        val folder = path.substringBeforeLast('/', "")
        at(folder).mkdirs()
        val extension = path.substringAfterLast('.')
        val to = free(folder, path.substringAfterLast('/').removeSuffix(".$extension"), extension)
        moveWithHistory(from, to, listOf("$TRASH/$path" to relative(to)))
        return if (extension == "md") read(to) else Note(relative(to), "", to.lastModified())
    }

    fun deleteForever(path: String) {
        val file = File(trash, path).canonicalFile
        if (!file.path.startsWith(trash.canonicalPath + File.separator)) throw VaultException("That isn't in the trash")
        history.remove("$TRASH/$path")
        file.delete()
    }

    fun emptyTrash() {
        trash.walkTopDown().filter { it.isFile }.forEach { file ->
            history.remove(relative(file))
        }
        trash.deleteRecursively()
    }

    /** Writes every note to a zip of plain Markdown files, which any Markdown app can open. */
    fun export(out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out).use { zip ->
            root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }
                .filter { it.isFile && !it.name.startsWith(".") }.forEach { file ->
                    zip.putNextEntry(ZipEntry(relative(file)))
                    zip.write(if (file.extension.lowercase() in KINDS) load(file) else file.readBytes())
                    zip.closeEntry()
                    count++
                }
            // The locked vault goes in as it is: still sealed, and only its own password opens it.
            File(root, LockedNotes.DIR).listFiles().orEmpty().filter { it.isFile && (it.extension == "enc" || it.name == "key.json") }.forEach { file ->
                zip.putNextEntry(ZipEntry(LockedNotes.DIR + "/" + file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return count
    }

    /** Seals or unseals every file, one at a time, so an interruption leaves everything readable. */
    private fun convertible(): List<File> = (history.files() + stats().map { at(it.path) } + listOf(at(ReminderStore.PATH)).filter { it.exists() } + (if (trash.exists()) trash.walkTopDown().filter { it.isFile && it.extension.lowercase() in KINDS }.toList() else emptyList()))

    /**
     * Finishes a conversion a crash cut short: once unlocked, anything still plain gets sealed, and
     * half-written copies go. Reading a mix of sealed and plain was already safe, but it shouldn't stay.
     */
    fun finishSealing() = ReminderStore.transaction { finishSealingFiles() }

    private fun finishSealingFiles() {
        val key = cipher ?: return
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".converting.tmp") }.forEach { it.delete() }
        convertible().forEach { file ->
            val bytes = file.readBytes()
            if (NoteCipher.isSealed(bytes)) return@forEach
            val tmp = File(file.parentFile, ".${file.name}.converting.tmp")
            tmp.writeBytes(key.encode(bytes))
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw VaultException("Couldn't encrypt ${file.name}") }
        }
        runCatching { history.prune() }
    }

    fun convert(to: NoteCipher?) = ReminderStore.transaction { convertFiles(to) }

    private fun convertFiles(to: NoteCipher?) {
        val files = convertible()
        // Everything is written beside the originals first, so running out of space or hitting an
        // unreadable file changes nothing at all.
        val ready = ArrayList<Pair<File, File>>(files.size)
        try {
            files.forEach { file ->
                val tmp = File(file.parentFile, ".${file.name}.converting.tmp")
                ready += file to tmp
                tmp.writeBytes(load(file).let { plain -> to?.encode(plain) ?: plain })
            }
        } catch (e: Exception) {
            ready.forEach { it.second.delete() }
            throw e
        }
        // From here on a mix of sealed and plain files can be read, as long as the key stays around.
        if (to != null) cipher = to
        ready.forEach { (file, tmp) ->
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw VaultException("Couldn't convert ${file.name}") }
        }
        cipher = to
        runCatching { history.prune() }
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
        LockedNotes(root).finishRestore()
        return count
    }

    /** Adds one Markdown file at [path]. Other files, and anything hidden such as .obsidian, are skipped. */
    fun importFile(path: String, input: InputStream): Int {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.size == 2 && parts[0] == LockedNotes.DIR && (parts[1].endsWith(".enc") || parts[1] == "key.json") && ".." !in parts[1] && "/" !in parts[1]) {
            // Held to one side until the whole backup is in, as the key can arrive after the notes.
            val target = File(File(root, LockedNotes.INCOMING), parts[1])
            target.parentFile?.mkdirs()
            target.writeBytes(input.readBytes())
            return 0
        }
        if (parts.isEmpty() || parts.any { it == ".." || it.startsWith(".") }) return 0
        val file = parts.last()
        val extension = file.substringAfterLast('.', "").lowercase()
        if (extension !in KINDS) return 0
        val bytes = input.readBytes()
        // Only Squill's own drawings and presentations, and real OpenRaster, not just any file with that ending.
        if (extension == "svg" && Sketch.parse(bytes.toString(Charsets.UTF_8)) == null) return 0
        if (extension == "deck" && Deck.parse(bytes.toString(Charsets.UTF_8)) == null) return 0
        if (extension == "ora" && !looksLikeOra(bytes)) return 0
        val folder = parts.dropLast(1).joinToString("/") { cleanName(it) }
        at(folder).mkdirs()
        val target = free(folder, cleanName(file.dropLast(extension.length + 1)), extension)
        writeFile(relative(target)) { it.write(bytes) }
        return 1
    }

    companion object {
        const val TRASH = ".trash"
        val KINDS = setOf("md", "svg", "deck", "ora")
    }
}
