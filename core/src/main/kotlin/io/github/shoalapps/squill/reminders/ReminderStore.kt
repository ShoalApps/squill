package io.github.shoalapps.squill.reminders

import io.github.shoalapps.squill.Reminder
import io.github.shoalapps.squill.notes.Vault
import io.github.shoalapps.squill.sync.PeerStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Deleted entries stay here so an offline device cannot bring them back. */
@Serializable
data class ReminderEntry(val key: String, val reminder: Reminder?, val edited: Long, val writer: String)

object ReminderMerge {
    /**
     * Advance past all observed edits even if the clock moves backwards. Concurrent edits use
     * their wall clocks; no direct protocol can know their real order when offline clocks disagree.
     */
    fun nextTime(now: Long, entries: List<ReminderEntry>): Long =
        maxOf(now, entries.maxOfOrNull { it.edited }?.let { if (it == Long.MAX_VALUE) it else it + 1 } ?: now)

    fun merge(here: List<ReminderEntry>, there: List<ReminderEntry>): List<ReminderEntry> =
        (here + there).groupBy { it.key }.toSortedMap().values.map { versions ->
            versions.maxWith(compareBy<ReminderEntry> { it.edited }.thenBy { it.writer }
                .thenBy { if (it.reminder == null) 1 else 0 }.thenBy { it.reminder.toString() })
        }
}

/**
 * Separate from notes and their trash. A computer keeps these in its folder, sealed with everything
 * else. A phone keeps them in a private file of their own, because an alarm has to read its reminder
 * after a reboot, when no password has been given and encrypted notes can't be opened.
 */
class ReminderStore private constructor(
    private val load: () -> ByteArray?,
    private val store: (ByteArray) -> Unit,
    private val writerId: () -> String,
) {
    constructor(vault: Vault) : this(
        { if (vault.exists(PATH)) vault.readBytes(PATH) else null },
        { bytes -> vault.file(PATH).parentFile.mkdirs(); vault.writeFile(PATH) { it.write(bytes) } },
        { PeerStore(vault.root).selfId },
    )

    constructor(file: File, writerId: () -> String) : this(
        { if (file.exists()) file.readBytes() else null },
        { bytes ->
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "Couldn't save reminders" } }
        },
        writerId,
    )

    companion object {
        const val PATH = ".reminders/data.json"
        // Store operations and sync share this lock, including across Vault instances for the same folder.
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val serializer = ListSerializer(ReminderEntry.serializer())
        fun <T> transaction(block: () -> T): T = synchronized(lock, block)
        fun encode(entries: List<ReminderEntry>): ByteArray = json.encodeToString(serializer, entries).toByteArray()
        fun decode(data: ByteArray): List<ReminderEntry> = json.decodeFromString(serializer, data.toString(Charsets.UTF_8))
    }

    fun entries(): List<ReminderEntry> = synchronized(lock) {
        load()?.let(::decode) ?: emptyList()
    }

    fun items(): List<Reminder> = entries().mapNotNull { it.reminder }

    private fun write(entries: List<ReminderEntry>) {
        store(encode(entries))
    }

    /** Repeated migration is harmless, including after a crash before the old file is removed. */
    fun migrate(old: List<Reminder>, source: String) = synchronized(lock) {
        val current = entries()
        val keys = current.map { it.key }.toSet()
        val imported = old.map { r ->
            val key = r.syncId.ifEmpty { "$source:${r.id}" }
            ReminderEntry(key, r.copy(syncId = key), 0, source)
        }.filterNot { it.key in keys }
        if (imported.isNotEmpty()) write(current + imported)
    }

    fun update(now: Long = System.currentTimeMillis(), change: (List<Reminder>) -> List<Reminder>): List<Reminder> = synchronized(lock) {
        val current = entries()
        val before = current.mapNotNull { it.reminder }
        val after = change(before).map { if (it.syncId.isEmpty()) it.copy(syncId = UUID.randomUUID().toString()) else it }
        require(after.map { it.syncId }.distinct().size == after.size) { "A reminder appears twice" }
        val writer = writerId()
        val time = ReminderMerge.nextTime(now, current)
        val wanted = after.associateBy { it.syncId }
        val changed = current.map { entry ->
            val item = wanted[entry.key]
            if (item == entry.reminder) entry else ReminderEntry(entry.key, item, time, writer)
        } + after.filter { r -> current.none { it.key == r.syncId } }.map { ReminderEntry(it.syncId, it, time, writer) }
        if (changed != current) write(changed)
        after
    }

    /** Merge against the latest local edits, even if they happened during a sync. */
    fun receive(incoming: List<ReminderEntry>, beforeWrite: (List<ReminderEntry>) -> Unit = {}): List<ReminderEntry> = synchronized(lock) {
        val current = entries()
        val merged = ReminderMerge.merge(current, incoming)
        if (merged != current) { beforeWrite(merged); write(merged) }
        merged
    }
}
