package io.github.shoalapps.squill.notes

import java.io.File

/** Moves a whole vault to a new folder, hidden parts included. Originals only go once everything has arrived. */
object VaultMover {
    /** Returns how many files moved. Anything already at the destination stays; a differing file arrives beside it. */
    fun move(from: File, to: File): Int {
        val source = from.canonicalFile
        val dest = to.canonicalFile
        if (dest == source) return 0
        if (dest.path.startsWith(source.path + File.separator)) throw VaultException("That folder is inside your notes")
        dest.mkdirs()
        if (!dest.isDirectory || !dest.canWrite()) throw VaultException("Squill can't write to that folder")
        val files = source.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
        val copied = mutableListOf<File>()
        files.forEach { file ->
            val rel = file.relativeTo(source).invariantSeparatorsPath
            var target = File(dest, rel)
            if (target.exists()) {
                if (target.length() == file.length() && target.readBytes().contentEquals(file.readBytes())) { copied += file; return@forEach }
                // Sync's own records aren't worth keeping twice; the destination's are as good.
                if (rel.startsWith(".sync/")) { copied += file; return@forEach }
                val ext = target.extension
                val stem = target.path.removeSuffix(".$ext")
                var n = 2
                while (target.exists()) target = File("$stem (moved ${n++}).$ext")
            }
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.moving")
            file.copyTo(tmp, overwrite = true)
            if (tmp.length() != file.length() || !tmp.renameTo(target)) { tmp.delete(); throw VaultException("Couldn't copy ${file.name}, so nothing was removed") }
            copied += file
        }
        copied.forEach { it.delete() }
        source.walkBottomUp().filter { it.isDirectory && it != source }.forEach { if (it.list()?.isEmpty() == true) it.delete() }
        return copied.size
    }
}
