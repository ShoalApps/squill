package io.github.shoalapps.squill.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.shoalapps.squill.Reminder
import io.github.shoalapps.squill.Repeat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun RemindersPage(model: Model) {
    val reminders by model.reminders.collectAsState()
    var editing by remember { mutableStateOf<Reminder?>(null) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Reminders", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { editing = Reminder(UUID.randomUUID().mostSignificantBits.and(Long.MAX_VALUE), "", whenMillis = System.currentTimeMillis() + 3_600_000) }) { Text("Add reminder") }
        LazyColumn {
            items(reminders.sortedBy { it.whenMillis }, key = { it.syncId }) { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(r.done, { model.changeReminders { list -> list.map { if (it.syncId == r.syncId) it.copy(done = !r.done, snoozedUntil = 0) else it } } }, modifier = Modifier.semantics { contentDescription = if (r.done) "Mark ${r.title} unfinished" else "Complete ${r.title}" })
                    Column(Modifier.weight(1f)) {
                        Text(r.title)
                        Text(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(r.whenMillis), ZoneId.systemDefault()).format(dateFormat) + " · " + r.repeat.label)
                        if (r.note.isNotBlank()) Text(r.note)
                    }
                    TextButton(onClick = { editing = r }) { Text("Edit") }
                    TextButton(onClick = { model.changeReminders { list -> list.filterNot { it.syncId == r.syncId } } }) { Text("Delete") }
                }
            }
        }
    }
    editing?.let { r ->
        var title by remember(r) { mutableStateOf(r.title) }
        var note by remember(r) { mutableStateOf(r.note) }
        var date by remember(r) { mutableStateOf(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(r.whenMillis), ZoneId.systemDefault()).format(dateFormat)) }
        var repeat by remember(r) { mutableStateOf(r.repeat) }
        val shown = remember(r) { date }
        // The box only shows minutes, so a date nobody touched must keep its exact time, or a title edit would count as moving it.
        val time = if (date == shown) r.whenMillis else runCatching { LocalDateTime.parse(date, dateFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Reminder") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") })
                OutlinedTextField(note, { note = it }, label = { Text("Note") })
                OutlinedTextField(date, { date = it }, label = { Text("When (yyyy-MM-dd HH:mm)") }, isError = time == null)
                Row { Text("Repeat: ${repeat.label}"); TextButton(onClick = { repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size] }) { Text("Change repeat") } }
            }
        }, confirmButton = {
            TextButton(enabled = title.isNotBlank() && time != null, onClick = {
                val saved = r.edited(title.trim(), note, time!!, repeat)
                model.changeReminders { list -> list.filterNot { it.syncId == r.syncId } + saved }
                editing = null
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}
