package io.github.shoalapps.squill

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Reminders live in one JSON file on the device. Nothing leaves the phone. */
object Store {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Reminder.serializer())
    private val _changes = MutableStateFlow(0)

    /** Ticks on every save, so an open screen sees what the notification buttons did. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun file(context: Context) = File(context.filesDir, "reminders.json")

    @Synchronized
    fun load(context: Context): List<Reminder> = runCatching {
        json.decodeFromString(serializer, file(context).readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(context: Context, list: List<Reminder>) {
        val tmp = File(context.filesDir, "reminders.json.tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        tmp.renameTo(file(context))
        _changes.value++
    }

    @Synchronized
    fun update(context: Context, change: (List<Reminder>) -> List<Reminder>): List<Reminder> {
        val next = change(load(context))
        save(context, next)
        return next
    }
}
