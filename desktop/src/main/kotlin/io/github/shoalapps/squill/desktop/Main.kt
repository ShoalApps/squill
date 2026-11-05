package io.github.shoalapps.squill.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.shoalapps.squill.notes.Deck
import io.github.shoalapps.squill.notes.Docx
import io.github.shoalapps.squill.notes.Edit
import io.github.shoalapps.squill.notes.Formatting
import io.github.shoalapps.squill.notes.Library
import io.github.shoalapps.squill.notes.Markdown
import io.github.shoalapps.squill.notes.Note
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

private val Light = lightColorScheme(primary = Color(0xFF4A4FA3), onPrimary = Color.White, primaryContainer = Color(0xFFE0E0FF), onPrimaryContainer = Color(0xFF05006C), secondaryContainer = Color(0xFFE1E0F9), background = Color(0xFFFBF8FF), surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21))
private val Dark = darkColorScheme(primary = Color(0xFFBEC2FF), onPrimary = Color(0xFF181C6F), primaryContainer = Color(0xFF313587), onPrimaryContainer = Color(0xFFE0E0FF), secondaryContainer = Color(0xFF424559), background = Color(0xFF131318), surface = Color(0xFF131318), onSurface = Color(0xFFE4E1E9))

private enum class Page(val label: String) { NOTES("Notes"), GRAPH("Graph"), DEVICES("Devices") }

/** Where notes live, remembered between runs. */
private object Settings {
    private val file = File(System.getProperty("user.home"), ".config/squill/folder")
    fun folder(): File = runCatching { File(file.readText().trim()) }.getOrNull()?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"), "Squill")
    fun setFolder(f: File) { file.parentFile?.mkdirs(); file.writeText(f.path) }
}

fun main() = application {
    var root by remember { mutableStateOf(Settings.folder()) }
    val state = rememberWindowState(width = 1280.dp, height = 820.dp)
    Window(onCloseRequest = ::exitApplication, title = "Squill", state = state) {
        val model = remember(root) { Model(root).also { it.start() } }
        DisposableEffect(model) { onDispose { model.stop() } }
        App(model, onChooseFolder = {
            val chooser = javax.swing.JFileChooser(root).apply { fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Choose a folder for your notes" }
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) { Settings.setFolder(chooser.selectedFile); root = chooser.selectedFile }
        })
    }
}

/** The whole app, separate from its window so it can be tested without one. */
@Composable
fun App(model: Model, dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(), onChooseFolder: () -> Unit = {}, initialOpen: String? = null, initialPage: String = "NOTES") {
    val scheme: ColorScheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            var page by remember { mutableStateOf(Page.valueOf(initialPage)) }
            var open by remember { mutableStateOf(initialOpen) }
            val library by model.library.collectAsState()
            val problem by model.problem.collectAsState()
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Page.entries.forEach { p -> NavigationRailItem(page == p, { page = p }, icon = { Text(when (p) { Page.NOTES -> "✎"; Page.GRAPH -> "◉"; Page.DEVICES -> "⇄" }) }, label = { Text(p.label) }) }
                }
                when (page) {
                    Page.NOTES -> NotesPage(model, library, open) { open = it }
                    Page.GRAPH -> GraphPane(library, { node ->
                        val path = node.path
                        page = Page.NOTES
                        if (path != null) open = path else model.create("", node.title) { open = it.path }
                    }, Modifier.weight(1f).fillMaxHeight(), onLink = { from, target, done -> model.linkNotes(from, target, done) }, onUnlink = { from, target -> model.unlink(from, target) })
                    Page.DEVICES -> DevicesPage(model, onChooseFolder)
                }
            }
            problem?.let { message ->
                AlertDialog(onDismissRequest = { model.clearProblem() }, title = { Text("Something went wrong") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { model.clearProblem() }) { Text("OK") } })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesPage(model: Model, library: Library, open: String?, onOpen: (String?) -> Unit) {
    var folder by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    val paintings by model.paintings.collectAsState()
    val searching = query.isNotBlank() || tag != null
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(320.dp).fillMaxHeight().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search your notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { model.create(folder, "Untitled") { onOpen(it.path) } }) { Text("New note") }
                OutlinedButton(onClick = { asking = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("Folder", maxLines = 1) }
                OutlinedButton(onClick = {
                    val d = FileDialog(null as Frame?, "Open a Word file", FileDialog.LOAD).apply { isVisible = true }
                    val name = d.file
                    if (name != null) model.create(folder, name.substringBeforeLast('.'), runCatching { File(d.directory, name).inputStream().use { Docx.import(it) } }.getOrDefault("That file couldn't be read.")) { onOpen(it.path) }
                }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text("Import", maxLines = 1) }
            }
            if (library.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                library.tags.take(24).forEach { t -> FilterChip(t == tag, { tag = if (t == tag) null else t }, label = { Text("#$t") }) }
            }
            if (folder.isNotEmpty() && !searching) TextButton(onClick = { folder = folder.substringBeforeLast('/', "") }) { Text("← " + folder.substringBeforeLast('/', "All notes").ifEmpty { "All notes" }) }
            LazyColumn(Modifier.weight(1f)) {
                if (!searching) items(library.folders.filter { it.substringBeforeLast('/', "") == folder }, key = { "f$it" }) { f ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { folder = f }.padding(10.dp)) { Text("▸ " + f.substringAfterLast('/'), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                val notes = library.notes.filter { info ->
                    when {
                        query.isNotBlank() -> info.note.title.contains(query, true) || info.note.body.contains(query, true)
                        tag != null -> info.tags.any { it == tag || it.startsWith("$tag/") }
                        else -> info.note.folder == folder
                    }
                }.sortedWith(compareBy({ !it.note.pinned }, { -it.note.modified }))
                items(notes, key = { it.note.path }) { info ->
                    ListRow(info.note.title + if (info.note.pinned) " 📌" else "", info.excerpt, info.note.path == open) { onOpen(info.note.path) }
                }
                fun <T> others(label: String, list: List<T>, path: (T) -> String, title: (T) -> String, f: (T) -> String) {
                    val shown = list.filter { if (query.isNotBlank()) title(it).contains(query, true) else tag == null && f(it) == folder }
                    if (shown.isNotEmpty()) item(key = label) { Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp, top = 10.dp)) }
                    items(shown, key = { path(it) }) { x -> ListRow(title(x), "", path(x) == open) { onOpen(path(x)) } }
                }
                others("Drawings", library.sketches, { it.path }, { it.title }, { it.folder })
                others("Paintings", paintings, { it.path }, { it.title }, { it.folder })
                others("Presentations", library.decks, { it.path }, { it.title }, { it.folder })
            }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                open == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Pick a note, or start a new one", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                open.endsWith(".svg") -> library.sketches.firstOrNull { it.path == open }?.let { s ->
                    Viewer(s.title, "Draw and edit this in Squill on your phone or tablet.") { SketchPane(s.sketch, Modifier.fillMaxSize()) }
                }
                open.endsWith(".deck") -> library.decks.firstOrNull { it.path == open }?.let { d ->
                    Viewer(d.title, null) { androidx.compose.runtime.key(d.path) { DeckEditor(d.deck, { model.saveDeck(d.path, it) }, Modifier.fillMaxSize()) } }
                }
                open.endsWith(".ora") -> paintings.firstOrNull { it.path == open }?.let { p ->
                    val picture = remember(p.modified, p.size) { paintingPicture(model.vault.readBytes(p.path)) }
                    Viewer(p.title, "Paint in Squill on your phone or tablet, or open this .ora file in Krita.") {
                        if (picture != null) Image(picture, p.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Text("That painting couldn't be shown.")
                    }
                }
                else -> androidx.compose.runtime.key(open) { NoteEditor(model, library, open, onOpen) { folder = it; tag = null; query = "" } }
            }
        }
    }
    if (asking) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { asking = false }, title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { model.createFolder(folder, name); asking = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ListRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).clickable(onClick = onClick).padding(10.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotBlank()) Text(sub.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Viewer(title: String, hint: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

private enum class View(val label: String) { BOTH("Write and read"), WRITE("Write"), READ("Read") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteEditor(model: Model, library: Library, path: String, onOpen: (String?) -> Unit, onTag: (String) -> Unit) {
    val note: Note = library.notes.firstOrNull { it.note.path == path }?.note ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("That note has gone") }
        return
    }
    var text by remember { mutableStateOf(TextFieldValue(note.body)) }
    var saved by remember { mutableStateOf(note.body) }
    var name by remember { mutableStateOf(note.title) }
    var view by remember { mutableStateOf(View.BOTH) }
    var pinned by remember { mutableStateOf(note.pinned) }

    fun flush() {
        if (text.text != saved) { saved = text.text; runCatching { model.saveNow(path, saved, pinned, note.colour) } }
    }
    LaunchedEffect(text.text) { delay(500); flush() }
    // A change from sync or another program, when nothing here is unsaved.
    LaunchedEffect(note.body) {
        if (note.body != saved && text.text == saved) { saved = note.body; text = TextFieldValue(note.body, TextRange(text.selection.start.coerceAtMost(note.body.length))) }
    }
    DisposableEffect(path) { onDispose { flush() } }
    fun format(change: (Edit) -> Edit) {
        val s = text.selection
        val e = change(Edit(text.text, s.min, s.max))
        text = TextFieldValue(e.text, TextRange(e.start, e.end))
    }
    val c = MaterialTheme.colorScheme
    val resolver = library.resolver
    val known = remember(resolver) { { t: String -> resolver.resolve(t) != null } }
    val actions = remember(path, resolver, library) {
        ReadActions(
            onNote = { target -> flush(); val to = resolver.resolve(target); if (to != null) onOpen(to) else model.create(note.folder, target.substringBefore('#').substringAfterLast('/')) { onOpen(it.path) } },
            onUrl = { url -> if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) runCatching { Desktop.getDesktop().browse(URI(url)) } },
            onTag = onTag,
            onTask = { line -> text = text.copy(text = Markdown.toggleTask(text.text, line)) },
            sketch = { n -> library.sketch(n)?.sketch },
            painting = { n -> if (n.endsWith(".ora", ignoreCase = true)) runCatching { model.vault.readBytes(n) }.getOrNull() else null },
        )
    }
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val mod = e.isCtrlPressed || e.isMetaPressed
            if (e.type != KeyEventType.KeyDown || !mod) return@onPreviewKeyEvent false
            when (e.key) {
                Key.B -> { format { Formatting.wrap(it, "**") }; true }
                Key.I -> { format { Formatting.wrap(it, "*") }; true }
                Key.K -> { format { Formatting.wrap(it, "[[", "]]") }; true }
                Key.S -> { flush(); true }
                else -> false
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                name, { name = it.replace("\n", " ") }, singleLine = true, cursorBrush = SolidColor(c.primary),
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = c.onSurface),
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && name.isNotBlank() && name != note.title) {
                        flush(); model.move(path, note.folder, name.trim()) { onOpen(it.path) }; true
                    } else false
                },
            )
            View.entries.forEach { v -> FilterChip(view == v, { view = v }, label = { Text(v.label) }) }
            TextButton(onClick = { pinned = !pinned; saved = text.text; runCatching { model.saveNow(path, saved, pinned, note.colour) }; model.reloadNow() }) { Text(if (pinned) "Unpin" else "Pin") }
            TextButton(onClick = {
                val d = FileDialog(null as Frame?, "Save as Word", FileDialog.SAVE).apply { file = "${note.title}.docx"; isVisible = true }
                val out = d.file
                if (out != null) runCatching { File(d.directory, out).outputStream().use { Docx.export(text.text, it) } }
            }) { Text("Word") }
            TextButton(onClick = { flush(); model.trash(path) { onOpen(null) } }) { Text("Delete") }
        }
        HorizontalDivider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (view != View.READ) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    BasicTextField(
                        text, { text = it }, cursorBrush = SolidColor(c.primary),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && text.selection.collapsed) {
                                when (val change = Formatting.continueList(text.text, text.selection.start)) {
                                    is Formatting.ListEnter.Continue -> {
                                        val at = text.selection.start
                                        val ins = "\n" + change.mark
                                        text = TextFieldValue(text.text.replaceRange(at, at, ins), TextRange(at + ins.length)); true
                                    }
                                    is Formatting.ListEnter.End -> {
                                        val at = text.selection.start
                                        text = TextFieldValue(text.text.removeRange(change.lineStart, at), TextRange(change.lineStart)); true
                                    }
                                    null -> false
                                }
                            } else false
                        },
                    )
                }
            }
            if (view == View.BOTH) VerticalDivider()
            if (view != View.WRITE) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Column(Modifier.widthIn(max = 760.dp)) {
                        MarkdownPane(text.text, actions, known, Modifier.fillMaxWidth())
                        val links = library.backlinks(path)
                        if (links.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp)); HorizontalDivider()
                            Text(if (links.size == 1) "1 note links here" else "${links.size} notes link here", style = MaterialTheme.typography.titleSmall, color = c.primary, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                            links.forEach { l -> ListRow(l.note.title, l.excerpt, false) { flush(); onOpen(l.note.path) } }
                        }
                    }
                }
            }
        }
        val words = text.text.split(Regex("\\s+")).count { it.isNotBlank() }
        HorizontalDivider()
        Text(
            "$words ${if (words == 1) "word" else "words"}   Ctrl+B bold   Ctrl+I italic   Ctrl+K link",
            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DevicesPage(model: Model, onChooseFolder: () -> Unit) {
    val peers by model.peerList.collectAsState()
    val code by model.pairingCode.collectAsState()
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Devices", style = MaterialTheme.typography.headlineSmall)
        Text("Your phone syncs directly with this computer over your own network, encrypted. There's no account and no cloud, and both need Squill open. If you edit the same note in both places before they sync, both versions are kept.", color = c.onSurfaceVariant)
        val current = code
        if (current == null) Button(onClick = { model.beginPairing() }) { Text("Pair a device") }
        else Surface(color = c.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("On your phone, open Squill, go to Settings, then Sync with your computer, and type:")
                Text(current, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("If it doesn't find this computer, its address is: " + model.addresses().joinToString("  or  ").ifEmpty { "unknown" }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.endPairing() }) { Text("Cancel") }
            }
        }
        LaunchedEffect(peers.size) { if (code != null && peers.isNotEmpty()) model.endPairing() }
        if (peers.isEmpty()) Text("Nothing paired yet.", color = c.onSurfaceVariant)
        peers.forEach { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold)
                    Text(if (p.lastSync == 0L) "Not synced yet" else "Last synced " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(p.lastSync)), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                TextButton(onClick = { model.forget(p) }) { Text("Forget") }
            }
        }
        HorizontalDivider()
        Text("Your notes are ordinary files in this folder, so other Markdown apps can open it too:", color = c.onSurfaceVariant)
        Text(model.root.path, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(model.root) } }) { Text("Open folder") }
            OutlinedButton(onClick = onChooseFolder) { Text("Choose another folder") }
        }
    }
}
