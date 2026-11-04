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
data class NoteInfo(val note: Note, val links: List<String>, val tags: List<String>, val excerpt: String, val done: Int, val tasks: Int)

data class Library(val notes: List<NoteInfo> = emptyList(), val folders: List<String> = emptyList(), val trash: List<Note> = emptyList()) {
    val paths: List<String> get() = notes.map { it.note.path }
    val tags: List<String> get() = notes.flatMap { it.tags }.distinct().sortedBy { it.lowercase() }

    fun backlinks(path: String): List<NoteInfo> = notes.filter { info ->
        info.note.path != path && info.links.any { Links.resolve(it, paths) == path }
    }.sortedBy { it.note.title.lowercase() }

    fun graph(showUnwritten: Boolean) = Graph.of(notes.associate { it.note.path to it.links }, showUnwritten)
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

    init {
        viewModelScope.launch(queue) {
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

    private fun reload() {
        val notes = vault.notes().map { note ->
            val (links, tags) = Links.scan(note.body)
            val (done, tasks) = Markdown.taskCount(note.body)
            NoteInfo(note, links, tags, Markdown.excerpt(note.body), done, tasks)
        }
        _library.value = Library(notes, vault.folders(), vault.trashed())
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

    fun move(path: String, folder: String, name: String, onDone: (Note) -> Unit = {}) = act(onDone) { vault.move(path, folder, name) }
    fun trash(path: String, onDone: () -> Unit = {}) = act<Unit>({ onDone() }) { vault.trash(path) }
    fun restore(path: String) = act { vault.restore(path) }
    fun deleteForever(path: String) = act { vault.deleteForever(path) }
    fun emptyTrash() = act { vault.emptyTrash() }
    fun createFolder(parent: String, name: String) = act { vault.createFolder(parent, name) }
    fun renameFolder(path: String, name: String, onDone: (String) -> Unit = {}) = act(onDone) { vault.renameFolder(path, name) }
    fun trashFolder(path: String) = act { vault.trashFolder(path) }

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
