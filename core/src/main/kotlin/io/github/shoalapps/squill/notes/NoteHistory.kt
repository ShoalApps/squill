package io.github.shoalapps.squill.notes

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Local recovery copies in a hidden folder, separate from notes, sync and trash.
 * Keep five minute checkpoints, all checkpoints for a day, then the latest per UTC day for 30 days.
 * The byte limits count stored bytes (including encryption), removing the oldest copies first.
 */
class NoteHistory(
    private val vault: Vault,
    private val clock: () -> Long = System::currentTimeMillis,
    private val interval: Long = 5 * 60_000L,
    private val perNoteBytes: Long = 10 * 1024 * 1024L,
    private val totalBytes: Long = 100 * 1024 * 1024L,
) {
    data class Version(val id: String, val time: Long)
    private val root get() = vault.file(DIR)
    private fun folder(path: String): File {
        val normalized = vault.file(path).relativeTo(vault.root.canonicalFile).invariantSeparatorsPath
        val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
        return vault.file("$DIR/$hash")
    }
    private fun versions(dir: File) = dir.listFiles().orEmpty().filter { it.isFile && it.extension == "version" }
    private fun time(file: File) = file.name.substringBefore('-').toLongOrNull() ?: 0L

    /** Called before replacement; failures here must never prevent saving the note. */
    @Synchronized internal fun keep(path: String, old: ByteArray, force: Boolean = false) {
        if (!path.endsWith(".md", true) || path.split('/').any { it.startsWith(".") }) return
        val dir = folder(path)
        val now = clock()
        val newest = versions(dir).maxOfOrNull { time(it) }
        if (!force && newest != null && now - newest < interval) return
        prune()
        dir.mkdirs()
        // Restores and sync can make several copies in the same millisecond. Keep their order.
        val timestamp = maxOf(now, (newest ?: (now - 1)) + 1)
        vault.writeHistory(File(dir, "$timestamp-${UUID.randomUUID()}.version"), old, minOf(perNoteBytes, totalBytes))
        prune()
    }

    @Synchronized fun list(path: String): List<Version> {
        prune()
        return versions(folder(path)).sortedByDescending { time(it) }.map { Version(it.name, time(it)) }
    }

    @Synchronized fun read(path: String, version: Version): String {
        require(version.id.matches(Regex("[0-9]+-[a-f0-9-]+\\.version"))) { "That version isn't available" }
        return vault.readHistory(File(folder(path), version.id)).toString(Charsets.UTF_8)
    }

    /** Unlike typing, each restore keeps the version it replaces, even inside the five minute window. */
    @Synchronized fun restore(path: String, version: Version): Note {
        if (!vault.exists(path) || !path.endsWith(".md", true) || path.split('/').any { it.startsWith(".") }) {
            throw VaultException("That note has gone")
        }
        val text = read(path, version)
        vault.writeFile(path) { it.write(text.toByteArray()) }
        return vault.read(path)
    }

    @Synchronized internal fun move(from: String, to: String) {
        val source = folder(from)
        if (!source.exists()) return
        val dest = folder(to)
        if (source == dest) return
        if (!dest.exists() && source.renameTo(dest)) return
        check(dest.mkdirs() || dest.isDirectory) { "Couldn't move note history" }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            source.listFiles().orEmpty().forEach { file ->
                val target = File(dest, file.name)
                check(!target.exists() && file.renameTo(target)) { "Couldn't move note history" }
                moved += file to target
            }
            check(source.delete()) { "Couldn't move note history" }
        } catch (e: Exception) {
            moved.asReversed().forEach { (old, new) -> new.renameTo(old) }
            throw e
        }
    }

    /** Deliberately strict for a move to locked notes: copies must be removed before it succeeds. */
    @Synchronized fun remove(path: String) {
        check(folder(path).deleteRecursively()) { "Couldn't remove note history" }
    }

    @Synchronized fun prune() {
        val now = clock()
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val days = HashSet<Long>()
            versions(dir).sortedByDescending { time(it) }.forEach { file ->
                val age = now - time(file)
                if (age >= 30 * DAY || (age >= DAY && !days.add(time(file) / DAY))) file.delete()
            }
            cap(versions(dir), perNoteBytes)
            if (dir.list()?.isEmpty() == true) dir.delete()
        }
        cap(files(), totalBytes)
    }

    private fun cap(files: List<File>, limit: Long) {
        var size = files.sumOf { it.length() }
        files.sortedBy { time(it) }.forEach { file ->
            if (size > limit) { val bytes = file.length(); if (file.delete()) size -= bytes }
        }
    }

    internal fun files(): List<File> = root.walkTopDown()
        .filter { it.isFile && !it.name.endsWith(".converting.tmp") }.toList()

    companion object {
        const val DIR = ".history"
        private const val DAY = 24 * 60 * 60_000L
    }
}
