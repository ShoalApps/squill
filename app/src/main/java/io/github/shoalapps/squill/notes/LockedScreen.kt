@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.shoalapps.squill.notes

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

@Composable
fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    )
}

/** Choosing a password: asked twice, with the catch spelt out. */
@Composable
fun NewPassword(explain: String, action: String, onDone: (String) -> Unit, busy: Boolean = false) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(explain, style = MaterialTheme.typography.bodyMedium)
        Text("If you forget this password, nobody can get these notes back. Not even Squill.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        PasswordField(first, "Password") { first = it }
        PasswordField(second, "Password again") { second = it }
        if (second.isNotEmpty() && first != second) Text("Those don't match yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (first.isNotEmpty() && first.length < 8) Text("Use at least 8 characters", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy && first.length >= 8 && first == second, onClick = { onDone(first) }) { Text(action) }
    }
}

/** Shown before anything else when all notes are encrypted. */
@Composable
fun UnlockScreen(vm: NotesViewModel) {
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your notes are locked", style = MaterialTheme.typography.headlineSmall)
                PasswordField(password, "Password") { password = it; wrong = false }
                if (wrong) Text("That password isn't right", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && password.isNotEmpty(), onClick = {
                    busy = true
                    vm.unlockAll(password) { ok -> busy = false; wrong = !ok; if (ok) password = "" }
                }) { Text(if (busy) "Unlocking…" else "Unlock") }
            }
        }
    }
}

/** The locked vault: its own password, locked again the moment you leave. */
@Composable
fun LockedScreen(vm: NotesViewModel, moveIn: String?, onMoved: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val window = remember(context) { (context as? android.app.Activity)?.window }
    // Keeps these notes out of screenshots and the recent-apps preview.
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); vm.lockVault() }
    }
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { vm.lockVault(); tick++ } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<LockedNote?>(null) }
    var notes by remember { mutableStateOf<List<LockedNote>>(emptyList()) }
    var changing by remember { mutableStateOf(false) }
    fun refresh() { vm.lockedNotes { notes = it } }
    tick.let { }
    val locked = vm.locked

    if (locked.isOpen) {
        LaunchedEffect(moveIn) { if (moveIn != null) vm.moveToLocked(moveIn) { refresh(); onMoved() } else refresh() }
    }
    editing?.let { n ->
        if (locked.isOpen) { LockedEditor(n, vm, onClose = { editing = null; refresh() }, onMovedOut = { editing = null; refresh() }); return }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locked notes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (locked.isOpen) {
                        TextButton(onClick = { changing = true }) { Text("Password") }
                        TextButton(onClick = { vm.lockVault(); tick++ }) { Text("Lock") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (locked.isOpen) Button(onClick = { editing = LockedNote("", "", "", 0) }) { Text("New locked note") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            when {
                !locked.isSetUp -> NewPassword(
                    "Locked notes are encrypted with their own password. They stay out of your list, search and graph, aren't synced, and lock again when you leave.",
                    "Create locked vault", busy = busy,
                    onDone = { p -> busy = true; vm.setUpVault(p) { busy = false; tick++ } },
                )
                !locked.isOpen -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (moveIn != null) Text("Unlock to move this note in. It'll be removed from your ordinary notes.", style = MaterialTheme.typography.bodyMedium)
                    PasswordField(password, "Password") { password = it; wrong = false }
                    if (wrong) Text("That password isn't right", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && password.isNotEmpty(), onClick = {
                        busy = true
                        vm.unlockVault(password) { ok -> busy = false; wrong = !ok; if (ok) { password = ""; tick++ } }
                    }) { Text(if (busy) "Unlocking…" else "Unlock") }
                }
                notes.isEmpty() -> Text("Nothing locked away yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(notes, key = { it.id }) { n ->
                        ListItem(
                            headlineContent = { Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(Markdown.excerpt(n.body, 120).replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { editing = n },
                        )
                    }
                }
            }
        }
    }
    if (changing) {
        var old by remember { mutableStateOf("") }
        var new by remember { mutableStateOf("") }
        var failed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { changing = false },
            title = { Text("Change password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(old, "Current password") { old = it; failed = false }
                    PasswordField(new, "New password") { new = it }
                    if (failed) Text("That password isn't right", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(enabled = new.length >= 8, onClick = { vm.changeVaultPassword(old, new) { ok -> if (ok) changing = false else failed = true } }) { Text("Change") } },
            dismissButton = { TextButton(onClick = { changing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LockedEditor(note: LockedNote, vm: NotesViewModel, onClose: () -> Unit, onMovedOut: () -> Unit) {
    var id by remember { mutableStateOf(note.id) }
    var title by remember { mutableStateOf(note.title) }
    var body by remember { mutableStateOf(note.body) }
    var reading by remember { mutableStateOf(note.id.isNotEmpty()) }
    var menu by remember { mutableStateOf(false) }
    fun save(then: () -> Unit = {}) {
        if (title.isBlank() && body.isBlank() && id.isEmpty()) { then(); return }
        vm.saveLocked(id, title, body) { id = it.id; then() }
    }
    LaunchedEffect(title, body) { delay(800); if (title != note.title || body != note.body || id.isNotEmpty()) save() }
    BackHandler { save(onClose) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locked note") },
                navigationIcon = { IconButton(onClick = { save(onClose) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = { reading = !reading }) { Text(if (reading) "Edit" else "Read") }
                    TextButton(onClick = { menu = true }) { Text("More") }
                    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem({ Text("Move back to notes") }, onClick = { menu = false; save { vm.moveOutOfLocked(id, title, body, onMovedOut) } })
                        androidx.compose.material3.DropdownMenuItem({ Text("Delete for good") }, onClick = { menu = false; if (id.isEmpty()) onClose() else vm.deleteLocked(id, onClose) })
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (reading) MarkdownView(body, MarkdownActions(onNote = {}, onUrl = {}, onTag = {}, onTask = { line -> body = Markdown.toggleTask(body, line) }), { false }, Modifier.fillMaxWidth())
            else OutlinedTextField(body, { body = it }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Locked note text" }, minLines = 10)
        }
    }
}
