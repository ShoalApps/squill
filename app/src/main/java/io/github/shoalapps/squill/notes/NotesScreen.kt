@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.github.shoalapps.squill.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.shoalapps.squill.R

/** What a dialog on this screen is asking for. */
private sealed interface Ask {
    data object NewFolder : Ask
    data class RenameFolder(val path: String) : Ask
    data class RenameNote(val note: Note) : Ask
    data class Colour(val note: Note) : Ask
    data class Move(val note: Note) : Ask
}

@Composable
fun NotesScreen(
    vm: NotesViewModel,
    folder: String,
    tag: String?,
    openPath: String?,
    onFolder: (String) -> Unit,
    onTag: (String?) -> Unit,
    onOpen: (String) -> Unit,
    onBeforeChange: (String) -> Unit,
    onTrash: () -> Unit,
    onSettings: () -> Unit,
) {
    val library by vm.library.collectAsState()
    val problem by vm.problem.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var cardMenu by remember { mutableStateOf<String?>(null) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(problem) {
        problem?.let { snackbars.showSnackbar(it); vm.clearProblem() }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val importOffice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importOffice(context, uri, folder) { result ->
            result.onSuccess { onOpen(it) }
            result.onFailure { android.widget.Toast.makeText(context, it.message ?: "That file couldn't be opened", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    if (searching) BackHandler { searching = false; query = "" }
    else if (tag != null) BackHandler { onTag(null) }
    else if (folder.isNotEmpty()) BackHandler { onFolder(folder.substringBeforeLast('/', "")) }

    val filtering = query.isNotBlank() || tag != null
    val shown = library.notes.filter { info ->
        when {
            query.isNotBlank() -> info.note.title.contains(query, ignoreCase = true) || info.note.body.contains(query, ignoreCase = true)
            tag != null -> info.tags.any { it == tag || it.startsWith("$tag/") }
            else -> info.note.folder == folder
        }
    }.sortedWith(compareBy({ !it.note.pinned }, { -it.note.modified }))
    val subfolders = if (filtering) emptyList() else library.folders.filter { it.substringBeforeLast('/', "") == folder }
    val drawings = library.sketches.filter { s ->
        when {
            query.isNotBlank() -> s.title.contains(query, ignoreCase = true)
            tag != null -> false
            else -> s.folder == folder
        }
    }.sortedByDescending { it.modified }
    val presentations = library.decks.filter { d ->
        when {
            query.isNotBlank() -> d.title.contains(query, ignoreCase = true)
            tag != null -> false
            else -> d.folder == folder
        }
    }.sortedByDescending { it.modified }
    val pinned = shown.filter { it.note.pinned }
    val others = shown.filterNot { it.note.pinned }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    when {
                        searching -> IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
                        tag != null -> IconButton(onClick = { onTag(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                        folder.isNotEmpty() -> IconButton(onClick = { onFolder(folder.substringBeforeLast('/', "")) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
                title = {
                    if (searching) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("Search your notes") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    } else {
                        Text(tag?.let { "#$it" } ?: folder.substringAfterLast('/').ifEmpty { "Notes" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (searching) {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                    } else {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, contentDescription = "Search") }
                        IconButton(onClick = { vm.today { onOpen(it.path) } }) { Icon(painterResource(R.drawable.ic_today), contentDescription = "Today's note") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Open a Word or PowerPoint file") }, onClick = { menu = false; runCatching { importOffice.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/octet-stream")) } })
                                DropdownMenuItem({ Text("New folder") }, onClick = { menu = false; ask = Ask.NewFolder }, leadingIcon = { Icon(painterResource(R.drawable.ic_folder), null) })
                                val trashed = library.trash.size
                                DropdownMenuItem({ Text(if (trashed == 0) "Trash" else "Trash ($trashed)") }, onClick = { menu = false; onTrash() })
                                DropdownMenuItem({ Text("Settings") }, onClick = { menu = false; onSettings() }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    SmallFloatingActionButton(onClick = { newMenu = true }) { Icon(Icons.Default.Add, contentDescription = "New checklist, drawing or presentation") }
                    DropdownMenu(newMenu, { newMenu = false }) {
                        DropdownMenuItem({ Text("New checklist") }, onClick = { newMenu = false; vm.create(folder, "Checklist", "- [ ] ") { onOpen(it.path) } }, leadingIcon = { Icon(painterResource(R.drawable.ic_checklist), null) })
                        DropdownMenuItem({ Text("New drawing") }, onClick = { newMenu = false; vm.createSketch(folder, "Drawing") { onOpen(it) } }, leadingIcon = { Icon(painterResource(R.drawable.ic_brush), null) })
                        DropdownMenuItem({ Text("New presentation") }, onClick = { newMenu = false; vm.createDeck(folder, "Presentation") { onOpen(it) } }, leadingIcon = { Icon(painterResource(R.drawable.ic_slides), null) })
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { vm.create(folder, "Untitled") { onOpen(it.path) } },
                    modifier = Modifier.semantics { contentDescription = "New note" },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New note") },
                )
            }
        },
    ) { padding ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(160.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 160.dp),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tags = library.tags
            if (tags.isNotEmpty() && !searching) item(span = StaggeredGridItemSpan.FullLine, key = "tags") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t -> FilterChip(t == tag, { onTag(if (t == tag) null else t) }, label = { Text("#$t") }) }
                }
            }
            if (subfolders.isNotEmpty()) items(subfolders, key = { "folder:$it" }, span = { StaggeredGridItemSpan.FullLine }) { path ->
                Box {
                    val count = library.notes.count { it.note.path.startsWith("$path/") }
                    ListItem(
                        headlineContent = { Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (count == 1) "1 note" else "$count notes") },
                        leadingContent = { Icon(painterResource(R.drawable.ic_folder), null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.combinedClickable(onClick = { onFolder(path) }, onLongClick = { folderMenu = path }),
                        trailingContent = {
                            IconButton(onClick = { folderMenu = path }) { Icon(Icons.Default.MoreVert, contentDescription = "Folder options") }
                        },
                    )
                    DropdownMenu(folderMenu == path, { folderMenu = null }) {
                        DropdownMenuItem({ Text("Rename") }, onClick = { folderMenu = null; onBeforeChange("$path/"); ask = Ask.RenameFolder(path) })
                        DropdownMenuItem({ Text("Delete, with its notes") }, onClick = { folderMenu = null; onBeforeChange("$path/"); vm.trashFolder(path) })
                    }
                }
            }
            if (pinned.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "pinned") { Label("Pinned") }
                items(pinned, key = { it.note.path }) { info ->
                    NoteCard(info, info.note.path == openPath, cardMenu == info.note.path, filtering, onOpen, { cardMenu = it }, vm, onBeforeChange) { ask = it }
                }
                if (others.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "others") { Label("Others") }
            }
            items(others, key = { it.note.path }) { info ->
                NoteCard(info, info.note.path == openPath, cardMenu == info.note.path, filtering, onOpen, { cardMenu = it }, vm, onBeforeChange) { ask = it }
            }
            if (drawings.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "drawings") { Label("Drawings") }
                items(drawings, key = { it.path }) { d ->
                    Box {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            border = if (d.path == openPath) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(d.path) }, onLongClick = { cardMenu = d.path }),
                        ) {
                            SketchPicture(d.sketch, Modifier.fillMaxWidth().height(120.dp))
                            Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp))
                        }
                        DropdownMenu(cardMenu == d.path, { cardMenu = null }) {
                            DropdownMenuItem({ Text("Delete") }, onClick = { cardMenu = null; onBeforeChange(d.path); vm.trash(d.path) })
                        }
                    }
                }
            }
            if (presentations.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine, key = "presentations") { Label("Presentations") }
                items(presentations, key = { it.path }) { d ->
                    Box {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            border = if (d.path == openPath) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(d.path) }, onLongClick = { cardMenu = d.path }),
                        ) {
                            SlideView(d.deck.slides.first(), Modifier.fillMaxWidth())
                            Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp))
                            Text(if (d.deck.slides.size == 1) "1 slide" else "${d.deck.slides.size} slides", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 10.dp, bottom = 10.dp))
                        }
                        DropdownMenu(cardMenu == d.path, { cardMenu = null }) {
                            DropdownMenuItem({ Text("Delete") }, onClick = { cardMenu = null; onBeforeChange(d.path); vm.trash(d.path) })
                        }
                    }
                }
            }
            if (shown.isEmpty() && subfolders.isEmpty() && drawings.isEmpty() && presentations.isEmpty()) item(span = StaggeredGridItemSpan.FullLine, key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(R.drawable.ic_launcher_mono), null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (filtering) "Nothing found" else "Nothing inked here yet", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }

    when (val a = ask) {
        Ask.NewFolder -> NameDialog("New folder", "", onDismiss = { ask = null }) { vm.createFolder(folder, it); ask = null }
        is Ask.RenameFolder -> NameDialog("Rename folder", a.path.substringAfterLast('/'), onDismiss = { ask = null }) { vm.renameFolder(a.path, it); ask = null }
        is Ask.RenameNote -> NameDialog("Rename note", a.note.title, onDismiss = { ask = null }) { vm.move(a.note.path, a.note.folder, it); ask = null }
        is Ask.Colour -> ColourDialog(a.note.colour, onDismiss = { ask = null }) { vm.save(a.note.path, a.note.body, a.note.pinned, it); ask = null }
        is Ask.Move -> FolderPicker(library.folders, a.note.folder, onDismiss = { ask = null }) { vm.move(a.note.path, it, a.note.title); ask = null }
        null -> Unit
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun NoteCard(
    info: NoteInfo,
    open: Boolean,
    menu: Boolean,
    showFolder: Boolean,
    onOpen: (String) -> Unit,
    onMenu: (String?) -> Unit,
    vm: NotesViewModel,
    onBeforeChange: (String) -> Unit,
    onAsk: (Ask) -> Unit,
) {
    val note = info.note
    val colour = NoteColours.background(note.colour)
    Box {
        Card(
            colors = CardDefaults.cardColors(containerColor = colour ?: MaterialTheme.colorScheme.surfaceContainerLow),
            border = if (open) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(note.path) }, onLongClick = { onMenu(note.path) }),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (note.pinned) Icon(painterResource(R.drawable.ic_pin), "Pinned", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (showFolder && note.folder.isNotEmpty()) Text(note.folder, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (info.excerpt.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(info.excerpt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
                if (info.tasks > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("${info.done} of ${info.tasks} done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        DropdownMenu(menu, { onMenu(null) }) {
            DropdownMenuItem({ Text(if (note.pinned) "Unpin" else "Pin") }, onClick = {
                onMenu(null); onBeforeChange(note.path)
                vm.save(note.path, vm.vault.read(note.path).body, !note.pinned, note.colour)
            })
            DropdownMenuItem({ Text("Colour") }, onClick = { onMenu(null); onBeforeChange(note.path); onAsk(Ask.Colour(vm.vault.read(note.path))) })
            DropdownMenuItem({ Text("Rename") }, onClick = { onMenu(null); onBeforeChange(note.path); onAsk(Ask.RenameNote(note)) })
            DropdownMenuItem({ Text("Move to folder") }, onClick = { onMenu(null); onBeforeChange(note.path); onAsk(Ask.Move(note)) })
            DropdownMenuItem({ Text("Delete") }, onClick = { onMenu(null); onBeforeChange(note.path); vm.trash(note.path) })
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Notes you've deleted, until you say otherwise. */
@Composable
fun TrashScreen(vm: NotesViewModel, onBack: () -> Unit) {
    val library by vm.library.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (library.trash.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("Empty") } },
            )
        },
    ) { padding ->
        androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (library.trash.isEmpty()) item {
                Text("Nothing in the trash", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            items(library.trash.size, key = { library.trash[it].path }) { i ->
                val note = library.trash[i]
                ListItem(
                    headlineContent = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(note.folder.ifEmpty { "Notes" }) },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { vm.restore(note.path) }) { Text("Restore") }
                            TextButton(onClick = { vm.deleteForever(note.path) }) { Text("Delete") }
                        }
                    },
                )
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Empty the trash?") },
        text = { Text("These notes will be gone for good.") },
        confirmButton = { TextButton(onClick = { vm.emptyTrash(); confirm = false }) { Text("Empty") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
