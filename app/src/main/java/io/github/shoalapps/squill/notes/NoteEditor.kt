@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)

package io.github.shoalapps.squill.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.shoalapps.squill.R
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/** Starts a new list item on Enter, and ends the list on Enter after an empty item. */
private object ContinueLists : InputTransformation {
    override fun androidx.compose.foundation.text.input.TextFieldBuffer.transformInput() {
        if (length != originalText.length + 1 || !selection.collapsed) return
        val cursor = selection.start
        if (cursor == 0 || asCharSequence()[cursor - 1] != '\n') return
        val change = Formatting.continueList(originalText.toString(), cursor - 1) ?: return
        when (change) {
            is Formatting.ListEnter.Continue -> {
                insert(cursor, change.mark)
                placeCursorAfterCharAt(cursor + change.mark.length - 1)
            }
            is Formatting.ListEnter.End -> {
                delete(change.lineStart, cursor)
                selection = TextRange(change.lineStart)
            }
        }
    }
}

/** One note, open for reading or writing. Everything typed saves itself. */
@Composable
fun NoteEditor(
    path: String,
    library: Library,
    vm: NotesViewModel,
    onClose: (() -> Unit)?,
    onPathChanged: (String) -> Unit,
    onOpenLink: (String, String) -> Unit,
    onUrl: (String) -> Unit,
    onTag: (String) -> Unit,
    onRemind: (String) -> Unit,
    onGraph: (String) -> Unit,
    onFlushReady: ((() -> Unit)?) -> Unit,
) {
    val info = remember(path) { library.notes.firstOrNull { it.note.path == path } }
    if (info == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("That note has gone") }
        LaunchedEffect(path) { onClose?.invoke() }
        return
    }
    val context = LocalContext.current
    val note = info.note
    val state = remember(path) { TextFieldState(note.body) }
    var pinned by remember(path) { mutableStateOf(note.pinned) }
    var colour by remember(path) { mutableStateOf(note.colour) }
    var saved by remember(path) { mutableStateOf(note.body) }
    // A note with something in it opens for reading; an empty one is ready to write in.
    var reading by rememberSaveable(path) { mutableStateOf(note.body.isNotBlank()) }
    var name by remember(path) { mutableStateOf(note.title) }
    var menu by remember { mutableStateOf(false) }
    var choosingColour by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun flush() {
        val text = state.text.toString()
        if (text != saved) {
            saved = text
            vm.saveNow(path, text, pinned, colour)
        }
    }
    fun rename() {
        val clean = name.trim()
        if (clean.isEmpty() || clean == note.title) { name = note.title; return }
        flush()
        vm.move(path, note.folder, clean) { onPathChanged(it.path) }
    }

    // Saves a moment after you stop typing, when the app goes to the background, and on the way out.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.drop(1).debounce(500).collect { text ->
            if (text != saved) {
                saved = text
                vm.save(path, text, pinned, colour)
            }
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, path) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) flush() }
        owner.lifecycle.addObserver(observer)
        onFlushReady { flush() }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            onFlushReady(null)
            flush()
        }
    }
    if (onClose != null) BackHandler { onClose() }

    val tint = NoteColours.background(colour)
    val paths = library.paths
    val known = remember(paths) { { target: String -> Links.resolve(target, paths) != null } }
    val actions = remember(path, known) {
        MarkdownActions(
            onNote = { flush(); onOpenLink(it, note.folder) },
            onUrl = onUrl,
            onTag = onTag,
            onTask = { line ->
                val next = Markdown.toggleTask(state.text.toString(), line)
                state.edit { replace(0, length, next) }
            },
        )
    }

    Scaffold(
        containerColor = tint ?: MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = tint ?: MaterialTheme.colorScheme.surface),
                title = { Text(note.folder.ifEmpty { "Notes" }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { reading = !reading }) {
                        if (reading) Icon(Icons.Default.Edit, contentDescription = "Edit")
                        else Icon(painterResource(R.drawable.ic_read), contentDescription = "Read")
                    }
                    IconButton(onClick = {
                        pinned = !pinned
                        saved = state.text.toString()
                        vm.save(path, saved, pinned, colour)
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_pin), contentDescription = if (pinned) "Unpin" else "Pin",
                            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Colour") }, onClick = { menu = false; choosingColour = true })
                            DropdownMenuItem({ Text("Move to folder") }, onClick = { menu = false; moving = true })
                            DropdownMenuItem(
                                { Text("Remind me") }, onClick = { menu = false; flush(); onRemind(note.title) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_alarm), null) },
                            )
                            DropdownMenuItem(
                                { Text("Show in graph") }, onClick = { menu = false; flush(); onGraph(path) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_graph), null) },
                            )
                            DropdownMenuItem(
                                { Text("Share") },
                                onClick = {
                                    menu = false
                                    context.startActivity(
                                        android.content.Intent.createChooser(
                                            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                                .putExtra(android.content.Intent.EXTRA_SUBJECT, note.title)
                                                .putExtra(android.content.Intent.EXTRA_TEXT, state.text.toString()),
                                            note.title,
                                        ),
                                    )
                                },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                            )
                            DropdownMenuItem(
                                { Text("Delete") },
                                onClick = {
                                    menu = false
                                    flush()
                                    vm.trash(path) { onClose?.invoke() ?: onPathChanged("") }
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!reading) Column(Modifier.imePadding().navigationBarsPadding()) {
                LinkSuggestions(state, library, note.path)
                FormatBar(state)
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", " ") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { rename() }),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        .semantics { contentDescription = "Note name" }
                        .onLostFocus { rename() },
                )
                if (reading) {
                    if (state.text.isBlank()) {
                        Text(
                            "Nothing here yet. Tap to write.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { reading = false }.padding(vertical = 16.dp),
                        )
                    } else {
                        MarkdownView(state.text.toString(), actions, known, Modifier.fillMaxWidth().padding(bottom = 16.dp))
                    }
                } else {
                    val highlight = MarkdownHighlight.remember()
                    BasicTextField(
                        state = state,
                        inputTransformation = ContinueLists,
                        outputTransformation = highlight,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Default),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).focusRequester(focus).semantics { contentDescription = "Note text" },
                    )
                    LaunchedEffect(Unit) { if (state.text.isEmpty()) runCatching { focus.requestFocus() } }
                }
                Backlinks(library.backlinks(path), onOpen = { flush(); onOpenLink(it, "") })
            }
        }
    }

    if (choosingColour) ColourDialog(colour, onDismiss = { choosingColour = false }) { picked ->
        colour = picked
        choosingColour = false
        saved = state.text.toString()
        vm.save(path, saved, pinned, picked)
    }
    if (moving) FolderPicker(library.folders, note.folder, onDismiss = { moving = false }) { folder ->
        moving = false
        flush()
        vm.move(path, folder, note.title) { onPathChanged(it.path) }
    }
}

private fun Modifier.onLostFocus(action: () -> Unit): Modifier = composed {
    var had by remember { mutableStateOf(false) }
    onFocusChanged { state ->
        if (had && !state.isFocused) action()
        had = state.isFocused
    }
}

@Composable
private fun Backlinks(notes: List<NoteInfo>, onOpen: (String) -> Unit) {
    if (notes.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp)) {
        HorizontalDivider()
        Text(
            if (notes.size == 1) "1 note links here" else "${notes.size} notes link here",
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        notes.forEach { info ->
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpen(info.note.path.removeSuffix(".md")) }.padding(vertical = 10.dp)) {
                Text(info.note.title, style = MaterialTheme.typography.bodyLarge)
                if (info.excerpt.isNotBlank()) Text(
                    info.excerpt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** While you're typing a [[link]], the notes it could be. */
@Composable
private fun LinkSuggestions(state: TextFieldState, library: Library, self: String) {
    val open = Formatting.openLink(state.text.toString(), state.selection.start) ?: return
    val matches = library.notes.filter { it.note.path != self && it.note.title.contains(open.typed, ignoreCase = true) }
        .sortedBy { !it.note.title.startsWith(open.typed, ignoreCase = true) }.take(12)
    if (matches.isEmpty() && open.typed.isBlank()) return
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        fun complete(name: String) {
            val cursor = state.selection.start
            state.edit {
                replace(open.first, cursor, "$name]]")
                placeCursorAfterCharAt(open.first + name.length + 1)
            }
        }
        matches.forEach { info -> AssistChip(onClick = { complete(info.note.title) }, label = { Text(info.note.title, maxLines = 1) }) }
        if (open.typed.isNotBlank() && matches.none { it.note.title.equals(open.typed, ignoreCase = true) }) {
            AssistChip(onClick = { complete(open.typed.trim()) }, label = { Text("New: ${open.typed.trim()}", maxLines = 1) })
        }
    }
}

@Composable
private fun FormatBar(state: TextFieldState) {
    fun apply(change: (Edit) -> Edit) {
        val e = change(Edit(state.text.toString(), state.selection.min, state.selection.max))
        state.edit {
            replace(0, length, e.text)
            selection = TextRange(e.start, e.end)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { state.undoState.undo() }, enabled = state.undoState.canUndo) { Icon(painterResource(R.drawable.ic_undo), contentDescription = "Undo") }
            IconButton(onClick = { state.undoState.redo() }, enabled = state.undoState.canRedo) { Icon(painterResource(R.drawable.ic_redo), contentDescription = "Redo") }
            Mark("H", "Heading") { apply { Formatting.linePrefix(it, "## ") } }
            Mark("B", "Bold", bold = true) { apply { Formatting.wrap(it, "**") } }
            Mark("I", "Italic") { apply { Formatting.wrap(it, "*") } }
            Mark("S", "Strikethrough") { apply { Formatting.wrap(it, "~~") } }
            IconButton(onClick = { apply { Formatting.linePrefix(it, "- [ ] ") } }) { Icon(painterResource(R.drawable.ic_checklist), contentDescription = "Checklist") }
            Mark("•", "Bullet list") { apply { Formatting.linePrefix(it, "- ") } }
            Mark("1.", "Numbered list") { apply { Formatting.linePrefix(it, "1. ") } }
            Mark("[[", "Link to a note") { apply { Formatting.wrap(it, "[[", "]]") } }
            Mark("❝", "Quote") { apply { Formatting.linePrefix(it, "> ") } }
            Mark("</>", "Code") { apply { Formatting.wrap(it, "`") } }
        }
    }
}

@Composable
private fun Mark(label: String, description: String, bold: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Text(label, fontWeight = if (bold) FontWeight.Black else FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ColourDialog(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note colour") },
        text = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (listOf<String?>(null) + NoteColours.keys).forEach { key ->
                    val fill = NoteColours.background(key) ?: MaterialTheme.colorScheme.surfaceContainerHigh
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(fill).clickable { onPick(key) }
                            .semantics { contentDescription = key ?: "No colour" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == current) Icon(Icons.Default.Check, null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun FolderPicker(folders: List<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf("") + folders).forEach { folder ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = folder != current) { onPick(folder) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_folder), null, Modifier.padding(end = 12.dp))
                        Text(folder.ifEmpty { "Notes (top level)" }, color = if (folder == current) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Keep-style note colours, soft enough for text in both light and dark. */
object NoteColours {
    private val light = mapOf(
        "red" to 0xFFFAD9D5, "orange" to 0xFFFBE4C8, "yellow" to 0xFFFFF2B8, "green" to 0xFFD6EFD0,
        "teal" to 0xFFCDEEE9, "blue" to 0xFFD3E4FB, "purple" to 0xFFE6DAF8, "pink" to 0xFFF9DBE9,
    )
    private val dark = mapOf(
        "red" to 0xFF4F2B28, "orange" to 0xFF4D3719, "yellow" to 0xFF494016, "green" to 0xFF27402A,
        "teal" to 0xFF1E403C, "blue" to 0xFF233A55, "purple" to 0xFF3A2C52, "pink" to 0xFF4C2A3C,
    )
    val keys: List<String> get() = light.keys.toList()

    @Composable
    fun background(key: String?): Color? {
        val table = if (androidx.compose.foundation.isSystemInDarkTheme()) dark else light
        return key?.let { table[it] }?.let { Color(it) }
    }
}
