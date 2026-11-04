@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.shoalapps.squill

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
    val notificationsOn by vm.notificationsOn.collectAsState()
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val jot by vm.jot.collectAsState()
    var settings by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(jot) { if (jot != null) settings = false }
    if (settings) {
        SettingsScreen(onBack = { settings = false })
        return
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // A repeating reminder is shown at its next ring, not the day it was first set.
    val now = System.currentTimeMillis()
    fun shownAt(r: Reminder) = if (r.done) r.whenMillis else r.nextAfter(now) ?: r.whenMillis
    val sections = items.filter { !it.done }.sortedBy { shownAt(it) }.groupBy { it.section(now) }
    val finished = items.filter { it.done }.sortedByDescending { it.whenMillis }
    val context = LocalContext.current
    val delete = { r: Reminder ->
        vm.delete(r)
        scope.launch {
            snackbars.currentSnackbarData?.dismiss()
            if (snackbars.showSnackbar("Reminder deleted", actionLabel = "Undo") == SnackbarResult.ActionPerformed) vm.save(r)
        }
        Unit
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Squill") },
                actions = {
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.SettingsIcon, contentDescription = "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.jot("") },
                // The button's own label is hidden from screen readers, so name it for them.
                modifier = Modifier.semantics { contentDescription = "Jot down" },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Jot down") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!notificationsOn) NotificationsOffCard {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(painterResource(R.drawable.ic_launcher_mono), null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Nothing inked yet", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Tap Jot down to add your first reminder.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section.entries.forEach { section ->
                    val list = sections[section].orEmpty()
                    if (list.isNotEmpty()) {
                        item(key = section.name) { Heading(section.label, overdue = section == Section.OVERDUE) }
                        items(list, key = { it.id }) { r -> ReminderCard(r, whenText(context, shownAt(r)), vm, { editingId = r.id }, { delete(r) }) }
                    }
                }
                if (finished.isNotEmpty()) {
                    item(key = "done") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Heading("Done") }
                            TextButton(onClick = {
                                val cleared = vm.clearDone()
                                scope.launch {
                                    snackbars.currentSnackbarData?.dismiss()
                                    if (snackbars.showSnackbar("Cleared ${cleared.size}", actionLabel = "Undo") == SnackbarResult.ActionPerformed) vm.restore(cleared)
                                }
                            }) { Text("Clear") }
                        }
                    }
                    items(finished, key = { it.id }) { r -> ReminderCard(r, whenText(context, shownAt(r)), vm, { editingId = r.id }, { delete(r) }) }
                }
            }
        }
        }
    }

    jot?.let { title -> EditDialog(null, initialTitle = title, onDismiss = { vm.closeJot() }) { vm.save(it); vm.closeJot() } }
    items.firstOrNull { it.id == editingId }?.let { r ->
        EditDialog(r, onDismiss = { editingId = null }) { vm.save(it); editingId = null }
    }
}

@Composable
private fun Heading(text: String, overdue: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun NotificationsOffCard(onOpenSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Notifications are off", style = MaterialTheme.typography.titleMedium)
            Text("Your reminders can't ring until you turn them on.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenSettings) { Text("Turn on") }
        }
    }
}

@Composable
private fun ReminderCard(r: Reminder, whenLabel: String, vm: RemindersViewModel, onEdit: () -> Unit, onDelete: () -> Unit) {
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
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun EditDialog(existing: Reminder?, initialTitle: String = "", onDismiss: () -> Unit, onSave: (Reminder) -> Unit) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(existing?.title ?: initialTitle) }
    var note by rememberSaveable { mutableStateOf(existing?.note ?: "") }
    var repeat by rememberSaveable { mutableStateOf(existing?.repeat ?: Repeat.NONE) }
    var millis by rememberSaveable {
        mutableStateOf(existing?.whenMillis ?: Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis)
    }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    // A one-off set in the past would never ring, so don't let it look saved. Renaming an old one is fine.
    val moved = existing == null || millis != existing.whenMillis || repeat != existing.repeat
    val inPast = repeat == Repeat.NONE && moved && millis <= System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Jot down" else "Edit reminder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Reminder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                if (existing == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickTimes(System.currentTimeMillis()).forEach { (label, at) ->
                            AssistChip(onClick = { millis = at }, label = { Text(label) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }) { Text(DateFormat.getMediumDateFormat(context).format(Date(millis))) }
                    OutlinedButton(onClick = { pickTime = true }) { Text(DateFormat.getTimeFormat(context).format(Date(millis))) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Repeat.entries.forEach { opt ->
                        FilterChip(selected = repeat == opt, onClick = { repeat = opt }, label = { Text(opt.label) })
                    }
                }
                if (inPast) Text("That time has already passed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && !inPast,
                onClick = {
                    onSave(
                        existing?.edited(title.trim(), note.trim(), millis, repeat)
                            ?: Reminder(System.currentTimeMillis(), title.trim(), note.trim(), millis, repeat),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = localDayAsUtc(millis))
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis = withPickedDay(millis, it) }
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
