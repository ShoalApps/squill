package io.github.shoalapps.squill.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** A note with what's been worked out from its text, so the screens don't have to keep re-reading it. */
@kotlinx.serialization.Serializable
data class NoteInfo(val note: Note, val links: List<String>, val tags: List<String>, val excerpt: String, val done: Int, val tasks: Int, val size: Long = 0)

/** A drawing, and when its file last changed. */
data class SketchInfo(val path: String, val modified: Long, val size: Long, val sketch: Sketch) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".svg")
    val folder: String get() = path.substringBeforeLast('/', "")
}

/** A presentation, and when its file last changed. */
data class DeckInfo(val path: String, val modified: Long, val size: Long, val deck: Deck) {
    val title: String get() = path.substringAfterLast('/').removeSuffix(".deck")
    val folder: String get() = path.substringBeforeLast('/', "")
}

data class Library(
    val notes: List<NoteInfo> = emptyList(),
    val folders: List<String> = emptyList(),
    val trash: List<Note> = emptyList(),
    val sketches: List<SketchInfo> = emptyList(),
    val decks: List<DeckInfo> = emptyList(),
) {
    val paths: List<String> get() = notes.map { it.note.path }
    val tags: List<String> by lazy { notes.flatMap { it.tags }.distinct().sortedBy { it.lowercase() } }
    val resolver: Links.Resolver by lazy { Links.Resolver(paths) }
    private val linkers: Map<String, List<NoteInfo>> by lazy {
        val found = HashMap<String, MutableList<NoteInfo>>()
        notes.forEach { info ->
            info.links.mapNotNull { resolver.resolve(it) }.distinct().forEach { to ->
                if (to != info.note.path) found.getOrPut(to) { mutableListOf() } += info
            }
        }
        found
    }

    fun backlinks(path: String): List<NoteInfo> = linkers[path].orEmpty().sortedBy { it.note.title.lowercase() }

    fun graph(showUnwritten: Boolean) = Graph.of(notes.associate { it.note.path to it.links }, showUnwritten)

    /** The drawing an embed means, nearest the top if two folders share the name. */
    fun sketch(name: String): SketchInfo? = sketches.filter {
        it.path.equals(name, ignoreCase = true) || it.path.substringAfterLast('/').equals(name.substringAfterLast('/'), ignoreCase = true)
    }.minByOrNull { it.path.count { c -> c == '/' } }
}

/** One note at a time goes through a single-file queue, so a save can't overtake a rename. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(app: Application) : AndroidViewModel(app) {
    private val root = File(app.filesDir, "vault")
    val vault = Vault(root)
    private val queue = Dispatchers.IO.limitedParallelism(1)

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** Something the user should be told went wrong. */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    fun clearProblem() { _problem.value = null }

    private val _shared = MutableStateFlow<String?>(null)
    /** Text shared into Squill, waiting to become a note. */
    val shared: StateFlow<String?> = _shared.asStateFlow()
    fun share(text: String) { _shared.value = text }
    fun clearShared() { _shared.value = null }

    private val _quick = MutableStateFlow<String?>(null)
    /** A launcher shortcut waiting to be acted on. */
    val quick: StateFlow<String?> = _quick.asStateFlow()
    fun quick(action: String) { _quick.value = action }
    fun clearQuick() { _quick.value = null }

    private val indexFile = File(app.cacheDir, "notes-index.json")
    private val indexJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val indexSerializer = kotlinx.serialization.builtins.ListSerializer(NoteInfo.serializer())
    private var known = HashMap<String, NoteInfo>()
    private var knownSketches = HashMap<String, SketchInfo>()
    private var knownDecks = HashMap<String, DeckInfo>()
    private var indexWrite: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch(queue) {
            // Show what was here last time straight away, then quietly catch up with any changes.
            runCatching {
                val saved = indexJson.decodeFromString(indexSerializer, indexFile.readText())
                known = saved.associateByTo(HashMap(saved.size * 2)) { it.note.path }
                _library.value = Library(saved)
            }
            seedOnce()
            reload()
        }
    }

    private fun seedOnce() {
        val marker = File(root, ".seeded")
        if (marker.exists()) return
        if (vault.notes().isEmpty()) {
            vault.create("", "Welcome to Squill", WELCOME)
            vault.create("", "How links work", LINKS_NOTE)
        }
        marker.writeText("")
    }

    /** Re-reads only what's changed, so saving one note in a big vault stays instant. */
    private fun reload() {
        val stats = vault.stats()
        val seen = HashMap<String, NoteInfo>(stats.size * 2)
        val seenSketches = HashMap<String, SketchInfo>()
        var changed = false
        val seenDecks = HashMap<String, DeckInfo>()
        stats.forEach { stat ->
            if (stat.path.endsWith(".deck", ignoreCase = true)) {
                val old = knownDecks[stat.path]
                if (old != null && old.modified == stat.modified && old.size == stat.size) seenDecks[stat.path] = old
                else Deck.parse(vault.readText(stat.path))?.let { seenDecks[stat.path] = DeckInfo(stat.path, stat.modified, stat.size, it) }
                return@forEach
            }
            if (stat.path.endsWith(".svg", ignoreCase = true)) {
                val old = knownSketches[stat.path]
                if (old != null && old.modified == stat.modified && old.size == stat.size) seenSketches[stat.path] = old
                else Sketch.parse(vault.readText(stat.path))?.let { seenSketches[stat.path] = SketchInfo(stat.path, stat.modified, stat.size, it) }
                return@forEach
            }
            val old = known[stat.path]
            if (old != null && old.note.modified == stat.modified && old.size == stat.size) {
                seen[stat.path] = old
            } else runCatching {
                val note = vault.read(stat.path)
                val (links, tags) = Links.scan(note.body)
                val (done, tasks) = Markdown.taskCount(note.body)
                seen[stat.path] = NoteInfo(note, links, tags, Markdown.excerpt(note.body), done, tasks, stat.size)
                changed = true
            }
        }
        if (seen.size != known.size) changed = true
        known = seen
        knownSketches = seenSketches
        knownDecks = seenDecks
        _library.value = Library(seen.values.toList(), vault.folders(), vault.trashed(), seenSketches.values.toList(), seenDecks.values.toList())
        if (changed) writeIndexSoon()
    }

    /** Written a moment later, not after every keystroke's save. */
    private fun writeIndexSoon() {
        indexWrite?.cancel()
        indexWrite = viewModelScope.launch(queue) {
            kotlinx.coroutines.delay(3000)
            runCatching {
                val tmp = File(indexFile.path + ".tmp")
                tmp.writeText(indexJson.encodeToString(indexSerializer, known.values.toList()))
                tmp.renameTo(indexFile)
            }
        }
    }

    private fun <T> act(onDone: (T) -> Unit = {}, block: () -> T) {
        viewModelScope.launch(queue) {
            runCatching(block).onSuccess { result ->
                reload()
                withContext(Dispatchers.Main) { onDone(result) }
            }.onFailure { e ->
                _problem.value = (e as? VaultException)?.message ?: "Something went wrong saving that"
            }
        }
    }

    fun refresh() = act<Unit> { }

    fun create(folder: String, name: String, body: String = "", onDone: (Note) -> Unit) = act(onDone) { vault.create(folder, name, body) }

    /** Today's note, made if it isn't there yet. */
    fun today(onDone: (Note) -> Unit) = act(onDone) {
        val name = LocalDate.now().toString()
        runCatching { vault.read("$DAILY/$name.md") }.getOrElse { vault.create(DAILY, name, "") }
    }

    /** Opens the note a link means, writing it first if it doesn't exist yet. */
    fun openOrCreate(target: String, fromFolder: String, onDone: (Note) -> Unit) = act(onDone) {
        val path = Links.resolve(target, vault.notes().map { it.path })
        if (path != null) vault.read(path)
        else {
            val clean = target.substringBefore('#').trim()
            val folder = if ("/" in clean) clean.substringBeforeLast('/') else fromFolder
            vault.create(folder, clean.substringAfterLast('/'))
        }
    }

    /** A note deleted or moved meanwhile stays that way: it isn't brought back by a late save. */
    fun save(path: String, body: String, pinned: Boolean, colour: String?, onDone: (Note) -> Unit = {}) =
        act<Note?>({ it?.let(onDone) }) { if (File(root, path).exists()) vault.save(path, body, pinned, colour) else null }

    /** For when the screen is going away: saved before this returns, so nothing typed is lost. */
    fun saveNow(path: String, body: String, pinned: Boolean, colour: String?) {
        runBlocking(queue) {
            if (File(root, path).exists()) runCatching { vault.save(path, body, pinned, colour) }
            runCatching { reload() }
        }
    }

    fun createSketch(folder: String, name: String, onDone: (String) -> Unit) = act(onDone) { vault.createSketch(folder, name) }

    /** Saved in the queue, unless the drawing's gone. */
    fun saveSketch(path: String, sketch: Sketch) = act<Unit> { if (vault.exists(path)) vault.saveSketch(path, sketch) }

    fun saveSketchNow(path: String, sketch: Sketch) {
        runBlocking(queue) {
            if (vault.exists(path)) runCatching { vault.saveSketch(path, sketch) }
            runCatching { reload() }
        }
    }

    fun createDeck(folder: String, name: String, deck: Deck = Deck(), onDone: (String) -> Unit) = act(onDone) { vault.createDeck(folder, name, deck) }
    fun saveDeck(path: String, deck: Deck) = act<Unit> { if (vault.exists(path)) vault.saveDeck(path, deck) }
    fun saveDeckNow(path: String, deck: Deck) {
        runBlocking(queue) {
            if (vault.exists(path)) runCatching { vault.saveDeck(path, deck) }
            runCatching { reload() }
        }
    }
    fun moveDeck(path: String, folder: String, name: String, onDone: (String) -> Unit = {}) = act(onDone) { vault.moveDeck(path, folder, name) }

    fun moveSketch(path: String, folder: String, name: String, onDone: (String) -> Unit = {}) = act(onDone) { vault.moveSketch(path, folder, name) }

    fun move(path: String, folder: String, name: String, onDone: (Note) -> Unit = {}) = act(onDone) { vault.move(path, folder, name) }
    fun trash(path: String, onDone: () -> Unit = {}) = act<Unit>({ onDone() }) { vault.trash(path) }
    fun restore(path: String) = act { vault.restore(path) }
    fun deleteForever(path: String) = act { vault.deleteForever(path) }
    fun emptyTrash() = act { vault.emptyTrash() }
    fun createFolder(parent: String, name: String) = act { vault.createFolder(parent, name) }
    fun renameFolder(path: String, name: String, onDone: (String) -> Unit = {}) = act(onDone) { vault.renameFolder(path, name) }
    fun trashFolder(path: String) = act { vault.trashFolder(path) }

    /** A Word file becomes a note and a PowerPoint file a presentation, beside what's here. The original isn't touched. */
    fun importOffice(context: android.content.Context, uri: android.net.Uri, folder: String, onDone: (Result<String>) -> Unit) {
        viewModelScope.launch(queue) {
            val result = runCatching {
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "Imported"
                val stem = name.substringBeforeLast('.').ifBlank { "Imported" }
                val open = { context.contentResolver.openInputStream(uri) ?: throw OfficeException("That file couldn't be opened") }
                when {
                    name.endsWith(".pptx", ignoreCase = true) -> vault.createDeck(folder, stem, open().use { Pptx.import(it) })
                    name.endsWith(".docx", ignoreCase = true) -> vault.create(folder, stem, open().use { Docx.import(it) }).path
                    else -> throw OfficeException("Squill can open .docx and .pptx files")
                }
            }
            runCatching { reload() }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    /** Runs bulk file work (backup, import) in the queue, then reports the count. */
    fun bulk(work: (Vault) -> Int, onDone: (Result<Int>) -> Unit) {
        viewModelScope.launch(queue) {
            val result = runCatching { work(vault) }
            runCatching { reload() }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    companion object {
        const val DAILY = "Daily"
    }
}

private const val WELCOME = """# Your notes, your files

Every note in Squill is an ordinary **Markdown** file in an ordinary folder, kept on this phone. Back them up from Settings and any Markdown app can open them.

## Try these

- [ ] Tick this box in reading view
- [ ] Press the pencil at the top to edit, and the eye to read again
- [ ] Pin this note, or give it a colour, from the menu
- [ ] Follow a link: [[How links work]]
- [ ] Open the Graph tab to see how your notes join up

## Formatting

You can write *italics*, **bold**, ~~crossed out~~ and `code`, and tag a note like this: #getting-started

> Quotes look like this.

| Type this | To get |
|---|---|
| `# Title` | A heading |
| `- item` | A list |
| `- [ ] item` | A checkbox |
"""

private const val LINKS_NOTE = """Type two square brackets to link to another note: [[Welcome to Squill]].

Linking to a note you haven't written yet is fine: [[An idea for later]] shows up in the graph, and following it makes the note.

Rename a note and every link to it is updated for you. Each note shows what links to it at the bottom.

#getting-started
"""
