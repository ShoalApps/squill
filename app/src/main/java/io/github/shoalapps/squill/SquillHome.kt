package io.github.shoalapps.squill

import android.content.Intent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.repeatOnLifecycle
import io.github.shoalapps.squill.notes.DeckScreen
import io.github.shoalapps.squill.notes.DrawingScreen
import io.github.shoalapps.squill.notes.GraphScreen
import io.github.shoalapps.squill.notes.NoteEditor
import io.github.shoalapps.squill.notes.NotesScreen
import io.github.shoalapps.squill.notes.NotesViewModel
import io.github.shoalapps.squill.notes.TrashScreen

private enum class Tab(val label: String) { NOTES("Notes"), REMINDERS("Reminders"), GRAPH("Graph") }

/** Notes, reminders and the graph. A bottom bar on a phone, a rail with room for two panes on a tablet. */
@Composable
fun SquillHome(reminders: RemindersViewModel, notes: NotesViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.NOTES) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var trash by rememberSaveable { mutableStateOf(false) }
    var folder by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var openPath by rememberSaveable { mutableStateOf<String?>(null) }
    var graphFocus by rememberSaveable { mutableStateOf<String?>(null) }
    var flushEditor by remember { mutableStateOf<(() -> Unit)?>(null) }
    val library by notes.library.collectAsState()
    val jot by reminders.jot.collectAsState()
    val shared by notes.shared.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(jot) { if (jot != null) { settings = false; trash = false; tab = Tab.REMINDERS } }
    LaunchedEffect(shared) {
        shared?.let { text ->
            settings = false; trash = false; tab = Tab.NOTES
            val title = text.trim().lineSequence().first().take(60).ifBlank { "Shared note" }
            notes.create(folder, title, text.trim()) { openPath = it.path }
            notes.clearShared()
        }
    }
    val quick by notes.quick.collectAsState()
    LaunchedEffect(quick) {
        quick?.let { action ->
            settings = false; trash = false; tab = Tab.NOTES
            val open = { path: String -> openPath = path }
            when (action) {
                MainActivity.ACTION_NEW_NOTE -> notes.create("", "Untitled") { open(it.path) }
                MainActivity.ACTION_NEW_LIST -> notes.create("", "Checklist", "- [ ] ") { open(it.path) }
                MainActivity.ACTION_NEW_DRAWING -> notes.createSketch("", "Drawing") { open(it) }
                MainActivity.ACTION_TODAY -> notes.today { open(it.path) }
            }
            notes.clearQuick()
        }
    }
    LaunchedEffect(openPath) { notes.openFile = openPath?.takeUnless { it.endsWith(".md") } }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { notes.sync(manual = false) }
    }
    if (settings) {
        SettingsScreen(notes, onBack = { settings = false })
        return
    }
    if (trash) {
        TrashScreen(notes, onBack = { trash = false })
        return
    }
    fun openUrl(url: String) {
        // Only ordinary web and mail links: a note shouldn't be able to launch anything else.
        val uri = url.toUri()
        if (uri.scheme?.lowercase() in listOf("http", "https", "mailto")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }
    androidx.compose.material3.Surface(Modifier.fillMaxSize()) { BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val twoPanes = maxWidth >= 840.dp
        val body: @Composable (Modifier) -> Unit = { modifier ->
            androidx.compose.foundation.layout.Box(modifier) {
                when (tab) {
                    Tab.REMINDERS -> SquillScreen(reminders, onSettings = { settings = true })
                    Tab.GRAPH -> GraphScreen(
                        library, graphFocus, onClearFocus = { graphFocus = null },
                        onOpen = { node ->
                            val path = node.path
                            if (path != null) { openPath = path; tab = Tab.NOTES }
                            else notes.openOrCreate(node.title, "") { openPath = it.path; tab = Tab.NOTES }
                        },
                        onSettings = { settings = true },
                    )
                    Tab.NOTES -> {
                        val list: @Composable (Modifier) -> Unit = { m ->
                            androidx.compose.foundation.layout.Box(m) {
                                NotesScreen(
                                    notes, folder, tag, openPath,
                                    onFolder = { folder = it },
                                    onTag = { tag = it },
                                    onOpen = { flushEditor?.invoke(); openPath = it },
                                    onBeforeChange = { changing ->
                                        flushEditor?.invoke()
                                        val open = openPath
                                        if (open != null && (open == changing || (changing.endsWith("/") && open.startsWith(changing)))) openPath = null
                                    },
                                    onTrash = { trash = true },
                                    onSettings = { settings = true },
                                )
                            }
                        }
                        val editor: @Composable (Modifier, String) -> Unit = { m, path ->
                            androidx.compose.foundation.layout.Box(m) {
                                androidx.compose.runtime.key(path) {
                                    if (path.endsWith(".ora")) io.github.shoalapps.squill.paint.PaintScreen(
                                        path, notes,
                                        onClose = { openPath = null },
                                        onPathChanged = { openPath = it },
                                    ) else if (path.endsWith(".deck")) DeckScreen(
                                        path, library, notes,
                                        onClose = { openPath = null },
                                        onPathChanged = { openPath = it },
                                    ) else if (path.endsWith(".svg")) DrawingScreen(
                                        path, library, notes,
                                        onClose = { openPath = null },
                                        onPathChanged = { openPath = it },
                                    ) else NoteEditor(
                                        path, library, notes,
                                        onClose = if (twoPanes) null else ({ openPath = null }),
                                        onPathChanged = { openPath = it.ifEmpty { null } },
                                        onOpenLink = { target, from -> notes.openOrCreate(target, from) { openPath = it.path } },
                                        onUrl = ::openUrl,
                                        onTag = { tag = it; openPath = if (twoPanes) openPath else null },
                                        onRemind = { reminders.jot(it) },
                                        onGraph = { graphFocus = it; tab = Tab.GRAPH },
                                        onFlushReady = { flushEditor = it },
                                        onOpenSketch = { openPath = it },
                                    )
                                }
                            }
                        }
                        val open = openPath
                        if (twoPanes) {
                            Row(Modifier.fillMaxSize()) {
                                list(Modifier.weight(.42f).fillMaxHeight())
                                VerticalDivider()
                                if (open != null) editor(Modifier.weight(.58f).fillMaxHeight(), open)
                                else androidx.compose.foundation.layout.Box(
                                    Modifier.weight(.58f).fillMaxHeight(),
                                    contentAlignment = androidx.compose.ui.Alignment.Center,
                                ) { Text("Pick a note, or start a new one", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        } else if (open != null) editor(Modifier.fillMaxSize(), open)
                        else list(Modifier.fillMaxSize())
                    }
                }
            }
        }
        val select = { t: Tab ->
            if (t == Tab.GRAPH && tab != Tab.GRAPH) graphFocus = null
            flushEditor?.invoke()
            tab = t
        }
        val iconFor: @Composable (Tab) -> Unit = { t ->
            when (t) {
                Tab.NOTES -> Icon(painterResource(R.drawable.ic_note), null)
                Tab.REMINDERS -> Icon(Icons.Default.Notifications, null)
                Tab.GRAPH -> Icon(painterResource(R.drawable.ic_graph), null)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(tab == t, { select(t) }, icon = { iconFor(t) }, label = { Text(t.label) })
                    }
                }
                body(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // A note gets the whole screen, so the keyboard has room.
            val writing = tab == Tab.NOTES && openPath != null
            Scaffold(
                bottomBar = {
                    if (!writing) NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(tab == t, { select(t) }, icon = { iconFor(t) }, label = { Text(t.label) })
                        }
                    }
                },
            ) { padding ->
                // Only the bottom is the bar's. The screens inside still need the room at the top.
                val bottom = androidx.compose.foundation.layout.PaddingValues(bottom = padding.calculateBottomPadding())
                body(Modifier.padding(bottom).consumeWindowInsets(bottom).fillMaxSize())
            }
        }
    } }
}
