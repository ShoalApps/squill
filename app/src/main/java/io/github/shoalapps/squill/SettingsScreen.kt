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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.shoalapps.squill.paint.frozen
import androidx.lifecycle.repeatOnLifecycle

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

@Composable
fun SettingsScreen(notes: io.github.shoalapps.squill.notes.NotesViewModel, onBack: () -> Unit, onTour: () -> Unit = {}, askFolder: Boolean = false, onAsked: () -> Unit = {}) {
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
    val importKeep = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) notes.bulk({ vault ->
            context.contentResolver.openInputStream(uri)!!.use { input ->
                io.github.shoalapps.squill.notes.KeepImport.import(vault, input) { bytes ->
                    val painting = io.github.shoalapps.squill.paint.OraFile.fromPicture(bytes)
                    java.io.ByteArrayOutputStream().also { out ->
                        io.github.shoalapps.squill.paint.OraFile.save(painting.frozen(), out)
                    }.toByteArray()
                }
            }
        }) { result ->
            toast(result.fold({ r ->
                listOfNotNull(
                    "Added ${r.imported} notes",
                    "${r.duplicates} were already here".takeIf { r.duplicates > 0 },
                    "${r.skippedAttachments} attachments couldn't come in".takeIf { r.skippedAttachments > 0 },
                    "${r.failed} couldn't be read".takeIf { r.failed > 0 },
                ).joinToString(". ")
            },
                { "That Keep zip couldn't be read" }))
        }
    }
    val importNotion = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) notes.bulk({ vault ->
            context.contentResolver.openInputStream(uri)!!.use { input ->
                io.github.shoalapps.squill.notes.NotionImport.import(vault, input) { bytes ->
                    val painting = io.github.shoalapps.squill.paint.OraFile.fromPicture(bytes)
                    java.io.ByteArrayOutputStream().also { out ->
                        io.github.shoalapps.squill.paint.OraFile.save(painting.frozen(), out)
                    }.toByteArray()
                }
            }
        }) { result ->
            toast(result.fold({ r ->
                listOfNotNull(
                    "Added ${r.imported} notes",
                    "${r.duplicates} were already here".takeIf { r.duplicates > 0 },
                    "${r.skippedAttachments} attachments couldn't come in".takeIf { r.skippedAttachments > 0 },
                    "${r.failed} couldn't be read".takeIf { r.failed > 0 },
                ).joinToString(". ")
            },
                { "That Notion zip couldn't be read" }))
        }
    }
    val importEvernote = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) notes.bulk({ vault ->
            var imported = 0; var duplicates = 0; var skipped = 0; var failed = 0
            uris.forEach { uri ->
                try {
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "Evernote.enex"
                    require(name.endsWith(".enex", ignoreCase = true))
                    val r = context.contentResolver.openInputStream(uri)!!.use { input ->
                        io.github.shoalapps.squill.notes.EvernoteImport.import(vault, input, name) { bytes ->
                            val painting = io.github.shoalapps.squill.paint.OraFile.fromPicture(bytes)
                            java.io.ByteArrayOutputStream().also { out ->
                                io.github.shoalapps.squill.paint.OraFile.save(painting.frozen(), out)
                            }.toByteArray()
                        }
                    }
                    imported += r.imported; duplicates += r.duplicates
                    skipped += r.skippedAttachments; failed += r.failed
                } catch (_: Exception) { failed++ }
            }
            io.github.shoalapps.squill.notes.EvernoteImport.Result(imported, duplicates, skipped, failed)
        }) { result ->
            toast(result.fold({ r ->
                listOfNotNull(
                    "Added ${r.imported} notes",
                    "${r.duplicates} were already here".takeIf { r.duplicates > 0 },
                    "${r.skippedAttachments} attachments couldn't come in".takeIf { r.skippedAttachments > 0 },
                    "${r.failed} couldn't be read".takeIf { r.failed > 0 },
                ).joinToString(". ")
            }, { "Those Evernote files couldn't be read" }))
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
                headlineContent = { Text("Take the tour") },
                supportingContent = { Text("What each part of Squill does") },
                modifier = Modifier.clickable(onClick = onTour),
            )
            PlaceRow(notes, askFolder, onAsked)
            EncryptRow(notes)
            SyncRow(notes)
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
                headlineContent = { Text("Google Keep") },
                supportingContent = { Text("Download your Keep notes as a zip from Google Takeout, then open it here") },
                modifier = Modifier.clickable { importKeep.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            )
            ListItem(
                headlineContent = { Text("Notion") },
                supportingContent = { Text("Export as Markdown & CSV from Notion, then open the zip here") },
                modifier = Modifier.clickable { importNotion.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            )
            ListItem(
                headlineContent = { Text("Evernote") },
                supportingContent = { Text("Open your exported .enex files. Each file becomes a folder of notes.") },
                modifier = Modifier.clickable { importEvernote.launch(arrayOf("application/enex", "application/x-evernote", "application/xml", "text/xml", "application/octet-stream")) },
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
                supportingContent = { Text("Everything stays on your devices. No account, no cloud and no tracking. Squill only connects to a computer you pair it with.") },
                modifier = Modifier.clickable { open(context, "https://shoalapps.github.io/privacy.html") },
            )
        }
    }
}

/** Restarts Squill so that it opens from the new place, with nothing left over from the old one. */
private fun restart(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

private fun canUseSharedFolder(context: Context) = if (android.os.Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
    else androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** Private to Squill, or an ordinary folder other apps can open. */
@Composable
private fun PlaceRow(notes: io.github.shoalapps.squill.notes.NotesViewModel, askFolder: Boolean, onAsked: () -> Unit) {
    val context = LocalContext.current
    val isPrivate = notes.root == notes.privateRoot
    val shared = remember { java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "Squill") }
    var choosing by remember { mutableStateOf(askFolder) }
    var moving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var waitingForAccess by remember { mutableStateOf(false) }
    fun moveTo(target: java.io.File, savedPath: String?) {
        moving = true
        notes.moveVault(target) { result ->
            result.onSuccess { Prefs.setNotesFolder(context, savedPath); restart(context) }
                .onFailure { moving = false; failure = it.message ?: "Couldn't move your notes. They're still where they were." }
        }
    }
    fun goShared() {
        if (canUseSharedFolder(context)) moveTo(shared, shared.path)
        else if (android.os.Build.VERSION.SDK_INT >= 30) {
            waitingForAccess = true
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else failure = "Allow Squill to use your files in your phone's app settings, then try again."
    }
    // Coming back from the system's permission screen.
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.LaunchedEffect(owner, waitingForAccess) {
        if (waitingForAccess) owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            if (canUseSharedFolder(context)) { waitingForAccess = false; moveTo(shared, shared.path) }
        }
    }
    ListItem(
        headlineContent = { Text("Where your notes live") },
        supportingContent = { Text(if (isPrivate) "Private: only Squill can reach them" else io.github.shoalapps.squill.notes.NotesPlace.of(notes.root, notes.privateRoot) + ". Other apps can see them") },
        modifier = Modifier.clickable { choosing = true },
    )
    if (choosing) androidx.compose.material3.AlertDialog(
        onDismissRequest = { choosing = false; onAsked() },
        title = { Text("Where your notes live") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Private keeps them where no other app can reach them. They're removed if you uninstall Squill.")
                Text("Documents / Squill keeps them as ordinary files that file managers, Obsidian and backup apps can open, and they stay if you uninstall. Android will ask you to allow Squill access to your files.")
                if (moving) Text("Moving your notes…", fontWeight = FontWeight.Bold)
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (isPrivate) TextButton(enabled = !moving, onClick = { failure = null; goShared() }) { Text("Use Documents / Squill") }
            else TextButton(enabled = !moving, onClick = { failure = null; moveTo(notes.privateRoot, null) }) { Text("Make private") }
        },
        dismissButton = { TextButton(onClick = { choosing = false; onAsked() }) { Text("Cancel") } },
    )
}

/** Encrypting every note under a password, or taking that off again. */
@Composable
private fun EncryptRow(notes: io.github.shoalapps.squill.notes.NotesViewModel) {
    val encrypted by notes.encrypted.collectAsState()
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    ListItem(
        headlineContent = { Text("Encrypt all notes") },
        supportingContent = { Text(if (encrypted) "On. Squill asks for your password when it opens, and locks after five minutes away" else "Off. Ask for a password to open your notes, drawings and everything else") },
        trailingContent = { androidx.compose.material3.Switch(encrypted, { failure = null; asking = true }) },
    )
    if (encrypted) ListItem(
        headlineContent = { Text("Lock now") },
        modifier = Modifier.clickable { notes.lockAll() },
    )
    if (asking) androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) asking = false },
        title = { Text(if (encrypted) "Stop encrypting?" else "Encrypt all notes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (encrypted) {
                    var password by remember { mutableStateOf("") }
                    Text("Your notes will be stored unencrypted again.")
                    io.github.shoalapps.squill.notes.PasswordField(password, "Password") { password = it; failure = null }
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = !busy && password.isNotEmpty(), onClick = {
                        busy = true
                        notes.decryptAll(password) { r -> busy = false; r.onSuccess { asking = false }.onFailure { failure = "That password isn't right" } }
                    }) { Text(if (busy) "Working…" else "Stop encrypting") }
                } else {
                    io.github.shoalapps.squill.notes.NewPassword(
                        "Everything's sealed on this device, though file names stay visible. Backups you make aren't encrypted, and copies synced to your computer are only encrypted if you encrypt that folder too. Reminders stay unencrypted, so they can still ring while your notes are locked. For a note's name to be hidden too, use Locked notes.",
                        if (busy) "Working…" else "Encrypt", busy = busy,
                        onDone = { p -> busy = true; notes.encryptAll(p) { r -> busy = false; r.onSuccess { asking = false }.onFailure { failure = it.message } } },
                    )
                    failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { asking = false }) { Text("Cancel") } },
    )
}

/** Pairing with a computer, and how that's going. */
@Composable
private fun SyncRow(notes: io.github.shoalapps.squill.notes.NotesViewModel) {
    val peers by notes.peerList.collectAsState()
    val status by notes.syncStatus.collectAsState()
    var pairing by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Sync with your computer") },
        supportingContent = { Text(status ?: if (peers.isEmpty()) "Directly over your own network, encrypted, with no account or cloud" else "Paired with " + peers.joinToString { it.name }) },
        modifier = Modifier.clickable { if (peers.isEmpty()) pairing = true else notes.sync() },
    )
    peers.forEach { peer ->
        ListItem(
            headlineContent = { Text(peer.name) },
            supportingContent = { Text(if (peer.lastSync == 0L) "Not synced yet" else "Last synced " + android.text.format.DateUtils.getRelativeTimeSpanString(peer.lastSync)) },
            trailingContent = { Row { TextButton(onClick = { notes.sync() }) { Text("Sync now") }; TextButton(onClick = { notes.forget(peer) }) { Text("Forget") } } },
        )
    }
    if (peers.isNotEmpty()) TextButton(onClick = { pairing = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Pair another computer") }
    if (pairing) PairDialog(notes) { pairing = false }
}

@Composable
private fun PairDialog(notes: io.github.shoalapps.squill.notes.NotesViewModel, onClose: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableIntStateOf(io.github.shoalapps.squill.sync.SyncProtocol.PORT) }
    var message by remember { mutableStateOf("Looking for your computer…") }
    var working by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        notes.discover { found ->
            found.firstOrNull()?.let { address = it.address; port = it.port; message = "Found " + it.name }
                ?: run { message = "Couldn't find it automatically. Type the address it shows." }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Pair with your computer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open Squill on your computer, choose Pair a device, and type the code it shows.")
                androidx.compose.material3.OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pairing code" })
                androidx.compose.material3.OutlinedTextField(address, { address = it.substringBefore(':') }, label = { Text("Computer's address") }, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Computer's address" })
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !working && address.isNotBlank() && io.github.shoalapps.squill.sync.SyncCrypto.normalise(code).length == 16, onClick = {
                working = true
                message = "Pairing…"
                notes.pair(address, port, code) { result ->
                    working = false
                    result.onSuccess { onClose() }.onFailure { message = "That didn't work. Check the code, and that Squill is showing it." }
                }
            }) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
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
