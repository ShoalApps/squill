package io.github.shoalapps.squill

import io.github.shoalapps.squill.reminders.ReminderEntry

import android.content.Context
import io.github.shoalapps.squill.reminders.ReminderStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A private file holds shared reminders. Arrival suppression and alarm identities stay on this phone. */
object Store {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Reminder.serializer())
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()
    var onEdit: () -> Unit = {}
    private const val WRITER = "self-writer"

    // Editing the title must not turn an overdue arrival back into a missed alarm.
    private fun alarmVersion(r: Reminder) = io.github.shoalapps.squill.sync.sha256(
        "${r.whenMillis}:${r.repeat}:${r.snoozedUntil}".toByteArray()
    )

    /** Who wrote an edit, for settling exact ties. Kept here so it needs nothing from the notes folder. */
    private fun writer(context: Context): String {
        val prefs = context.getSharedPreferences("reminder-ids", Context.MODE_PRIVATE)
        return prefs.getString(WRITER, null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(WRITER, it).commit() }
    }

    /** Never in the notes folder: an alarm must be able to read this with notes locked and after a reboot. */
    fun repository(context: Context): ReminderStore {
        val store = ReminderStore(File(context.filesDir, "reminders-shared.json")) { writer(context) }
        val old = File(context.filesDir, "reminders.json")
        if (old.exists()) {
            // Decode errors must leave the original untouched, rather than migrating an empty list.
            store.migrate(json.decodeFromString(serializer, old.readText()), writer(context))
            check(old.delete()) { "Couldn't finish moving reminders" }
        }
        return store
    }

    @Synchronized fun load(context: Context): List<Reminder> {
        val delivery = context.getSharedPreferences("reminder-delivery", Context.MODE_PRIVATE)
        val ids = context.getSharedPreferences("reminder-ids", Context.MODE_PRIVATE)
        val entries = repository(context).entries()
        val live = entries.filter { it.reminder != null }.map { it.key }.toSet()
        // A deleted reminder gives up its number, so a new one using it isn't quietly renumbered.
        val gone = ids.all.keys.filter { it != WRITER && it !in live }
        if (gone.isNotEmpty()) ids.edit().apply { gone.forEach(::remove) }.commit()
        val used = ids.all.filterKeys { it != WRITER }.values.filterIsInstance<Long>().map { it.toInt() }.toMutableSet()
        return entries.mapNotNull { e -> e.reminder?.let { r ->
            val id = if (ids.contains(e.key)) ids.getLong(e.key, r.id) else {
                var candidate = r.id
                while (candidate.toInt() in used) candidate = java.util.UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE)
                used += candidate.toInt()
                ids.edit().putLong(e.key, candidate).commit()
                candidate
            }
            val local = r.copy(id = id)
            if (delivery.getString(e.key, null) == alarmVersion(r) && local.missed(System.currentTimeMillis()))
                local.arrived(delivery.getLong(e.key + ":arrival", System.currentTimeMillis())) else local
        } }
    }

    @Synchronized fun save(context: Context, list: List<Reminder>) {
        val before = load(context)
        ReminderStore.transaction {
            val projected = load(context).associateBy { it.syncId }
            val canonical = repository(context).items().associateBy { it.syncId }
            repository(context).update {
                list.map { r ->
                    val original = canonical[r.syncId]
                    if (original == null) r else r.copy(
                        id = original.id,
                        rungAt = if (r.rungAt == projected[r.syncId]?.rungAt) original.rungAt else r.rungAt,
                        snoozedUntil = if (r.snoozedUntil == projected[r.syncId]?.snoozedUntil) original.snoozedUntil else r.snoozedUntil,
                    )
                }
            }
        }
        reconcile(context, before)
        _changes.value++
        onEdit()
    }

    fun export(context: Context): String = json.encodeToString(serializer, load(context))
    fun import(context: Context, text: String, now: Long = System.currentTimeMillis()): Int {
        val backup = json.decodeFromString(serializer, text)
        update(context) { mergeBackup(it, backup, now) }
        return backup.size
    }

    @Synchronized fun update(context: Context, change: (List<Reminder>) -> List<Reminder>): List<Reminder> {
        return ReminderStore.transaction {
            val next = change(load(context))
            save(context, next)
            next
        }
    }

    /** Recorded before the shared file is replaced, so a crash cannot cause an overdue burst. */
    fun arriving(context: Context, incoming: List<ReminderEntry>) {
        val current = repository(context).entries().associateBy { it.key }
        val now = System.currentTimeMillis()
        val delivery = context.getSharedPreferences("reminder-delivery", Context.MODE_PRIVATE).edit()
        incoming.forEach { entry ->
            val reminder = entry.reminder
            if (entry != current[entry.key] && reminder != null && reminder.missed(now)) {
                delivery.putString(entry.key, alarmVersion(reminder))
                delivery.putLong(entry.key + ":arrival", now)
            }
        }
        check(delivery.commit()) { "Couldn't save reminder delivery state" }
    }

    /** Call around sync so removed alarms are cancelled too. Past arrivals never ring on restart. */
    @Synchronized fun received(context: Context, before: List<Reminder>) {
        reconcile(context, before)
        _changes.value++
    }

    private fun reconcile(context: Context, before: List<Reminder>) {
        val after = load(context)
        val byKey = after.associateBy { it.syncId }
        before.filter { it != byKey[it.syncId] }.forEach {
            Scheduler.cancel(context, it.id)
            androidx.core.app.NotificationManagerCompat.from(context).cancel(it.id.toInt())
        }
        after.forEach { Scheduler.schedule(context, it) }
    }
}
