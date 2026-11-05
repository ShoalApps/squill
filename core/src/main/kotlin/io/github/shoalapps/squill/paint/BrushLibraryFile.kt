package io.github.shoalapps.squill.paint

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Shared serial disk queue: loads after recreation see all previously requested writes. */
class BrushLibraryFile(private val file: File) {
    fun load(): BrushLibrary = disk.submit<BrushLibrary> {
        runCatching { BrushLibrary.decode(file.readText()) }.getOrDefault(BrushLibrary())
    }.get()

    /** Only the newest waiting copy is written, so dragging a slider doesn't write the file on every tick. */
    private val waiting = java.util.concurrent.atomic.AtomicReference<BrushLibrary?>()

    fun save(saving: BrushLibrary, onFailure: (Throwable) -> Unit = {}) {
        waiting.set(saving)
        disk.execute {
            val library = waiting.getAndSet(null) ?: return@execute
            runCatching {
                file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Cannot create brush library folder" } }
                val temp = File(file.parentFile, "${file.name}.tmp")
                FileOutputStream(temp).use { out -> out.write(library.encode().toByteArray(Charsets.UTF_8)); out.fd.sync() }
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    // Android's same-directory rename also atomically replaces the old file.
                    check(temp.renameTo(file)) { "Cannot replace brush library" }
                }
            }.onFailure(onFailure)
        }
    }

    companion object {
        /** Enqueue a shutdown continuation after every library write already requested. */
        fun afterWrites(onDone: () -> Unit) { disk.execute(onDone) }

        private val disk = Executors.newSingleThreadExecutor { r -> Thread(r, "Brush library").apply { isDaemon = true } }
    }
}
