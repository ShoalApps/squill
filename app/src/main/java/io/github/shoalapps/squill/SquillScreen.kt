@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.shoalapps.squill

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date

private fun whenText(context: android.content.Context, millis: Long): String {
    val day = DateFormat.getMediumDateFormat(context).format(Date(millis))
    val time = DateFormat.getTimeFormat(context).format(Date(millis))
    return "$day, $time"
}

@Composable
fun SquillScreen(vm: RemindersViewModel) {
    val items by vm.items.collectAsState()
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var creating by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val upcoming = items.filter { !it.done }.sortedBy { it.whenMillis }
    val finished = items.filter { it.done }.sortedByDescending { it.whenMillis }
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Squill") }, scrollBehavior = scroll) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Jot down") },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing inked yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Tap Jot down to add your first reminder.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(upcoming, key = { it.id }) { r -> ReminderCard(r, whenText(context, r.whenMillis), vm, { editing = r }) }
                if (finished.isNotEmpty()) {
                    item { Text("Done", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                    items(finished, key = { it.id }) { r -> ReminderCard(r, whenText(context, r.whenMillis), vm, { editing = r }) }
                }
            }
        }
    }

    if (creating) EditDialog(null, onDismiss = { creating = false }) { vm.save(it); creating = false }
    editing?.let { r -> EditDialog(r, onDismiss = { editing = null }) { vm.save(it); editing = null } }
}

@Composable
private fun ReminderCard(r: Reminder, whenLabel: String, vm: RemindersViewModel, onEdit: () -> Unit) {
    Card(
        onClick = onEdit,
        colors = CardDefaults.cardColors(
            containerColor = if (r.done) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = r.done, onCheckedChange = { vm.toggleDone(r) })
            Column(Modifier.weight(1f)) {
                Text(
                    r.title,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (r.done) TextDecoration.LineThrough else null,
                )
                if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodyMedium)
                val repeat = if (r.repeat == Repeat.NONE) "" else " · ${r.repeat.label}"
                Text(whenLabel + repeat, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { vm.delete(r) }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun EditDialog(existing: Reminder?, onDismiss: () -> Unit, onSave: (Reminder) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var repeat by remember { mutableStateOf(existing?.repeat ?: Repeat.NONE) }
    var millis by remember {
        mutableStateOf(existing?.whenMillis ?: Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis)
    }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Jot down" else "Edit reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Reminder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }) { Text(DateFormat.getMediumDateFormat(context).format(Date(millis))) }
                    OutlinedButton(onClick = { pickTime = true }) { Text(DateFormat.getTimeFormat(context).format(Date(millis))) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Repeat.entries.forEach { opt ->
                        FilterChip(selected = repeat == opt, onClick = { repeat = opt }, label = { Text(opt.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        Reminder(
                            id = existing?.id ?: System.currentTimeMillis(),
                            title = title.trim(), note = note.trim(),
                            whenMillis = millis, repeat = repeat,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        // The picker works in UTC; keep the time of day and swap in the chosen date.
                        val picked = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
                        val cal = Calendar.getInstance().apply { timeInMillis = millis }
                        cal.set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH))
                        millis = cal.timeInMillis
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (pickTime) {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val state = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY), initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    cal.set(Calendar.HOUR_OF_DAY, state.hour); cal.set(Calendar.MINUTE, state.minute)
                    cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    millis = cal.timeInMillis
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}
