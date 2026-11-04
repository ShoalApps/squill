@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.shoalapps.squill

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

@Composable
fun SettingsScreen(notes: io.github.shoalapps.squill.notes.NotesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    var snooze by remember { mutableIntStateOf(Prefs.snoozeMinutes(context)) }
    val toast = { text: String -> Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Store.export(context).toByteArray()) }
        }.onSuccess { toast("Backup saved") }.onFailure { toast("Couldn't save the backup") }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            Store.import(context, text).also { Scheduler.scheduleAll(context) }
        }.onSuccess { toast("Restored $it reminders") }.onFailure { toast("That isn't a Squill backup") }
    }
    val backupNotes = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) notes.bulk({ vault -> context.contentResolver.openOutputStream(uri, "wt")!!.use { vault.export(it) } }) { result ->
            toast(result.fold({ "Backed up $it files" }, { "Couldn't save the backup" }))
        }
    }
    val restoreNotes = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) notes.bulk({ vault -> context.contentResolver.openInputStream(uri)!!.use { vault.importZip(it) } }) { result ->
            toast(result.fold({ if (it == 0) "No notes in that file" else "Added $it notes" }, { "That file couldn't be read" }))
        }
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) notes.bulk({ vault ->
            var count = 0
            fun walk(dir: androidx.documentfile.provider.DocumentFile, prefix: String) {
                for (file in dir.listFiles()) {
                    val name = file.name ?: continue
                    if (name.startsWith(".")) continue
                    if (file.isDirectory) walk(file, "$prefix$name/")
                    else context.contentResolver.openInputStream(file.uri)?.use { count += vault.importFile(prefix + name, it) }
                }
            }
            walk(androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)!!, "")
            count
        }) { result ->
            toast(result.fold({ if (it == 0) "No Markdown files in that folder" else "Added $it notes" }, { "That folder couldn't be read" }))
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SupportCard { open(context, "https://liberapay.com/ShoalApps") }
            ListItem(
                headlineContent = { Text("Snooze for") },
                supportingContent = {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Prefs.SNOOZE_CHOICES.forEach { minutes ->
                            FilterChip(
                                selected = snooze == minutes,
                                onClick = { snooze = minutes; Prefs.setSnoozeMinutes(context, minutes) },
                                label = { Text("$minutes min") },
                            )
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Sound and vibration") },
                supportingContent = { Text("Choose how reminders ring") },
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, AlarmReceiver.CHANNEL),
                        )
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Add widget to home screen") },
                supportingContent = { Text("Your next reminders, and a button to jot one down") },
                modifier = Modifier.clickable {
                    val widgets = context.getSystemService(AppWidgetManager::class.java)
                    if (widgets.isRequestPinAppWidgetSupported) widgets.requestPinAppWidget(ComponentName(context, SquillWidgetReceiver::class.java), null, null)
                    else toast("Add it from your home screen's widget list")
                },
            )
            ListItem(
                headlineContent = { Text("Back up notes") },
                supportingContent = { Text("Save them all as a zip of Markdown files, which any Markdown app can open. Uninstalling Squill deletes them otherwise.") },
                modifier = Modifier.clickable { backupNotes.launch("squill-notes.zip") },
            )
            ListItem(
                headlineContent = { Text("Restore notes") },
                supportingContent = { Text("Adds the notes in a zip beside what's here. Nothing is overwritten.") },
                modifier = Modifier.clickable { restoreNotes.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            )
            ListItem(
                headlineContent = { Text("Import a folder of notes") },
                supportingContent = { Text("Copies the Markdown files from a folder, such as an Obsidian vault, keeping its subfolders") },
                modifier = Modifier.clickable { runCatching { importFolder.launch(null) } },
            )
            ListItem(
                headlineContent = { Text("Back up reminders") },
                supportingContent = { Text("Save them to a file you choose. Uninstalling Squill deletes them otherwise.") },
                modifier = Modifier.clickable { backup.launch("squill-backup.json") },
            )
            ListItem(
                headlineContent = { Text("Restore a backup") },
                supportingContent = { Text("Adds the reminders in a backup file to your list") },
                modifier = Modifier.clickable { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )
            ListItem(
                headlineContent = { Text("Check for updates") },
                supportingContent = { Text("Opens Shoal if you have it, or the releases page") },
                modifier = Modifier.clickable { ShoalManager.checkForUpdates(context) },
            )
            ListItem(
                headlineContent = { Text("Source code") },
                supportingContent = { Text("Free software under the GPL-3.0") },
                modifier = Modifier.clickable { open(context, "https://github.com/ShoalApps/squill") },
            )
            ListItem(
                headlineContent = { Text("Privacy") },
                supportingContent = { Text("Your notes and reminders stay on this phone. Squill has no account, no network access and no tracking.") },
                modifier = Modifier.clickable { open(context, "https://shoalapps.github.io/privacy.html") },
            )
        }
    }
}

/** Supporting Shoal, at the top of Settings as in every Shoal app. */
@Composable
private fun SupportCard(onDonate: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp), color = c.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.clickable(onClickLabel = "Donate on Liberapay", onClick = onDonate)
                .background(Brush.linearGradient(listOf(c.primary, c.tertiary))).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Favorite, null, Modifier.size(36.dp), tint = c.onPrimary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Support Shoal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.onPrimary)
                Text("Free, open source and ad-free. Donations on Liberapay keep it going.", style = MaterialTheme.typography.bodyMedium, color = c.onPrimary.copy(alpha = .9f))
                Button(
                    onClick = onDonate, modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.onPrimary, contentColor = c.primary),
                ) { Text("Donate", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
